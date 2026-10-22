/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.rwpp.appKoin
import io.github.rwpp.config.CoreData
import io.github.rwpp.config.Settings
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.DisconnectEvent
import io.github.rwpp.api.event.events.GameLoadedEvent
import io.github.rwpp.api.event.events.ModCheckEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.game.ready
import io.github.rwpp.api.game.PlayerIds
import io.github.rwpp.game.reloadMods
import io.github.rwpp.api.util.zipFolderToByte
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.io.SizeUtils
import io.github.rwpp.modDir
import io.github.rwpp.net.packets.ModPacket
import io.github.rwpp.projectVersion
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.generalProportion
import io.github.rwpp.widget.RWTextButton
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import io.github.rwpp.api.net.sendPacketToClient
import io.github.rwpp.api.Initialization
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.api.net.Net
import java.util.*
import io.github.rwpp.api.logging.logger
import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.net.registerPacketListener
import io.github.rwpp.api.server.ServerInfoResponder
import io.github.rwpp.net.getLatestVersionProfile
import io.github.rwpp.net.packets.ProductPackets

//typealias TargetPositionWithUnits = Triple<Double, Double, List<GameUnit>>

object Logic : Initialization {
    private val modDownloadMutex = Mutex()
    private var modQueue: LinkedList<String>? = null
    private var requiredMods: List<String>? = null

    override fun init() {
        registerListeners()

        val owner = EventOwner(RwppExtensionRuntime.extensionContext)
        GlobalEventChannel.filter(ModCheckEvent::class).subscribeAlways(owner) { event ->
            val room = appKoin.get<Game>().gameRoom
            if (room.option.canTransferMod) {
                val required = event.requiredMods.toList()
                GameScope.launch {
                    val net = appKoin.get<Net>()
                    val allMods = GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>()
                    val missingMods = required.toMutableList().apply { removeAll(allMods.map { it.name }) }
                    if (missingMods.isEmpty()) {
                        net.sendPacketToServer(ModPacket.RequestPacket())
                        allMods.forEach { mod ->
                            if ((mod.getTitle() ?: mod.name.orEmpty()) in required) mod.disabled = false
                        }
                        reloadMods()
                        net.sendPacketToServer(ModPacket.ModReloadFinishPacket())
                    } else {
                        modQueue = LinkedList(missingMods)
                        requiredMods = required
                        net.sendPacketToServer(ModPacket.RequestPacket().apply { this.mods = missingMods.joinToString(",") })
                        showDownloadingDialog()
                        setDownloadingTitle(0)
                    }
                }
                event.cancel()
            }
        }
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(owner) {
            GameScope.launch {
                modQueue = null
                requiredMods = null
            }
        }

        GlobalEventChannel.filter(GameLoadedEvent::class).subscribeAlways(owner) {
            GameScope.launch {
            val game = appKoin.get<Game>()
            val settings = appKoin.get<Settings>()

            when (settings.effectLimitForAllEffects) {
                "Zero" -> game.setEffectLimitForAllEffects(0)
                "Unlimited" -> game.setEffectLimitForAllEffects(Int.MAX_VALUE)
                else -> {}
            }
            }
        }

    }

    fun getNextPlayerId(): Int = PlayerIds.next()

    fun registerListeners() {
        val owner = EventOwner(RwppExtensionRuntime.extensionContext)
        val game = appKoin.get<Game>()
        val net = appKoin.get<Net>()
        ServerInfoResponder.install(owner, net, RwppClientServerInfo)
        net.registerPacketListener(owner, ProductPackets.MOD_REQUEST) { client, packet ->
            val requested = packet.mods.split(",").toSet()
            GameScope.launch {
                val room = game.gameRoom
                if (!room.isHost || !room.option.canTransferMod || client == null) return@launch
                runCatching {
                    val player = room.getPlayerByClient(client) ?: return@runCatching
                    player.ready = false
                    val mods = GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>()
                        .filter { it.isEnabled && (it.getTitle() ?: it.name.orEmpty()) in requested }
                    // Copy paths/names before filesystem work leaves the game dispatcher.
                    val sources = mods.map { (it.getTitle() ?: it.name.orEmpty()) to (it.getSourcePath() ?: it.path.orEmpty()) }
                    withContext(Dispatchers.IO) {
                        sources.forEachIndexed { index, (name, path) ->
                            val file = File(path)
                            client.sendPacketToClient(ModPacket.ModPackPacket().apply {
                                this.index = index
                                this.name = name
                                modBytes = if (file.isDirectory) file.zipFolderToByte() else file.readBytes()
                            })
                        }
                    }
                }.onFailure { logger().error(it.stackTraceToString()) }
            }
            true
        }
        net.registerPacketListener(owner, ProductPackets.MOD_DATA) { _, packet ->
            val name = packet.name
            val data = packet.modBytes
            val index = packet.index
            GameScope.launch {
                modDownloadMutex.withLock {
                val room = game.gameRoom
                val queue = modQueue ?: return@withLock
                if (room.isHost) return@withLock
                runCatching {
                    require(name == queue.peek()) { "Unexpected downloaded mod: $name" }
                    require(name.isNotBlank() && '/' !in name && '\\' !in name && ':' !in name) { "Invalid mod name" }
                    queue.poll()
                    setDownloadingTitle(index + 1)
                    withContext(Dispatchers.IO) {
                        File(modDir, "$name.rwmod").writeBytes(data)
                    }
                    // The room may have disconnected while the file was being written.
                    if (modQueue !== queue) return@runCatching
                    if (queue.isEmpty()) {
                        val required = requiredMods ?: return@runCatching
                        GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>().forEach {
                            it.disabled = (it.getTitle() ?: it.name.orEmpty()) !in required
                        }
                        reloadMods()
                        val available = GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>()
                            .map { it.getTitle() ?: it.name.orEmpty() }
                        require(required.all { it in available }) { "Required mods were not found" }
                        net.sendPacketToServer(ModPacket.ModReloadFinishPacket())
                        withContext(Dispatchers.Main) { UI.showDialog(null) }
                    }
                }.onFailure {
                    room.disconnect("Mod download failed.")
                    GameScope.launch(Dispatchers.Main) { UI.showWarning("Mod download failed: ${it.message}", true) }
                }
                }
            }
            true
        }
        net.registerPacketListener(owner, ProductPackets.MOD_READY) { client, _ ->
            GameScope.launch {
                if (game.gameRoom.isHost && client != null) game.gameRoom.getPlayerByClient(client)?.ready = true
            }
            true
        }
    }
    suspend fun checkUpdate(checkImmediately: Boolean = false) {
        val coreData = appKoin.get<CoreData>()
        val settings = appKoin.get<Settings>()
        val net = appKoin.get<Net>()
        val now = System.currentTimeMillis()
        var profile: LatestVersionProfile? = null
        var checkUpdateDialogVisible = false
        coreData.lastPlayTime = now
        // per day
        if ((now - (coreData.lastAutoCheckUpdateTime + 1000 * 60 * 60 * 24) > 0) || coreData.debug) {
            withContext(Dispatchers.IO) {
                if (settings.autoCheckUpdate || checkImmediately) {
                    profile = net.getLatestVersionProfile()

                    if (profile != null) {
                        coreData.lastAutoCheckUpdateTime = now
                        if (profile.version != projectVersion && settings.ignoreVersion != profile.version || coreData.debug) {
                            checkUpdateDialogVisible = true
                        }
                    }
                }
            }
        }

        if (checkUpdateDialogVisible) {
            UI.showDialog { dismiss ->
                BorderCard(
                    modifier = Modifier.fillMaxWidth(generalProportion()).verticalScroll(rememberScrollState())
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(5.dp)) {
                        HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = Color.DarkGray)
                        Box {
                            Icon(
                                Icons.Default.Warning,
                                null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(50.dp).offset(5.dp, 5.dp).blur(2.dp)
                            )
                            Icon(
                                Icons.Default.Warning,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(50.dp)
                            )
                        }
                        HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = Color.DarkGray)
                    }

                    Text(
                        readI18n(
                            "rwpp.settings.newVersion",
                            (if (profile!!.prerelease) "Prerelease " else "") + profile!!.version
                        ),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(5.dp),
                        style = MaterialTheme.typography.headlineMedium
                    )

                    val annotatedString = buildAnnotatedString {
                        if (!profile.prerelease) {
                            withLink(
                                link = LinkAnnotation
                                    .Clickable(
                                        tag = "github",
                                        linkInteractionListener = { net.openUriInBrowser("https://github.com/Minxyzgo/RWPP-Team/releases") },
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = Color(0, 0, 238),
                                                textDecoration = TextDecoration.Underline
                                            )
                                        )
                                    )
                            ) {
                                append(readI18n("rwpp.settings.goToDownload", "github"))
                            }

                            append("\n")

                            withLink(
                                link = LinkAnnotation
                                    .Clickable(
                                        tag = "123pan",
                                        linkInteractionListener = { net.openUriInBrowser("https://www.123684.com/s/6Rijjv-79Fi?提取码:VtDG") },
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = Color(0, 0, 238),
                                                textDecoration = TextDecoration.Underline
                                            )
                                        )
                                    )
                            ) {
                                append(readI18n("rwpp.settings.goToDownload", "123pan"))
                            }
                        } else {
                            append(readI18n("rwpp.settings.prereleaseNote"))

                            withLink(
                                link = LinkAnnotation
                                    .Clickable(
                                        tag = "afdian",
                                        linkInteractionListener = { net.openUriInBrowser("https://afdian.com/a/minxyzgo") },
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = Color(0, 0, 238),
                                                textDecoration = TextDecoration.Underline
                                            )
                                        )
                                    )
                            ) {
                                append(readI18n("rwpp.settings.goToDownload", "爱发电"))
                            }
                        }
                    }

                    Text(
                        annotatedString,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(2.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    RWTextButton(
                        readI18n("rwpp.settings.ignoreVersion"),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(5.dp)
                    ) {
                        settings.ignoreVersion = profile.version
                        dismiss()
                    }

                    BorderCard(backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)) {
                        Markdown(
                            profile!!.body,
                            modifier = Modifier.padding(5.dp),
                            colors = markdownColor(),
                            typography = markdownTypography()
                        )
                    }
                }
            }
        }
    }

    private var receivingNetworkDialogTitle by mutableStateOf("")
    private var showNetworkDialog by mutableStateOf(false)

    private fun showDownloadingDialog() {
        GameScope.launch(Dispatchers.Main) {
        UI.showDialog { dismiss ->
            BorderCard(modifier = Modifier.size(500.dp)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LineSpinFadeLoaderIndicator(MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        receivingNetworkDialogTitle,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(20.dp)
                            .offset(y = 50.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        }
    }

    private fun setDownloadingTitle(index: Int) {
        val totalSize = appKoin.get<Game>().gameRoom.option.allModsSize
        val modCount = requiredMods!!.size
        val title =
            "Downloading ${modQueue!!.peek()}. total: ${SizeUtils.byteToMB(totalSize.toLong())}. ($index/$modCount)"
        GameScope.launch(Dispatchers.Main) {
            receivingNetworkDialogTitle = title
            showNetworkDialog = true
        }
    }
}

//经过测试，分组效果不佳，暂时不使用
/*
fun onPathfindingOptimization(targetX: Float, targetY: Float, selectedUnits: List<GameUnit>): List<TargetPositionWithUnits> {
//        val leftX = selectedUnits.minOf { it.x }
//        val rightX = selectedUnits.maxOf { it.x }
//        val topY = selectedUnits.minOf { it.y }
//        val bottomY = selectedUnits.maxOf { it.y }
    // 简单距离迭代，计算每个单位到其他单位的距离，并将距离较短的单位放入同一组
    val groups = mutableListOf<TargetPositionWithUnits>()
    val unassignedUnits = selectedUnits.toMutableList()
    val maxDistance = 10
    while (unassignedUnits.isNotEmpty()) {
        val group = mutableListOf<GameUnit>()
        groups.add(Triple(targetX.toDouble(), targetY.toDouble(), group))
        group.add(unassignedUnits.removeAt(0))
        for (i in unassignedUnits.indices) {
            val unit = unassignedUnits[i]
            val distance = sqrt((unit.x - group.last().x) * (unit.x - group.last().x) + (unit.y - group.last().y) * (unit.y - group.last().y))
            if (distance <= maxDistance) {
                group.add(unassignedUnits.removeAt(i))
            }
        }
    }

    return groups
}*/
