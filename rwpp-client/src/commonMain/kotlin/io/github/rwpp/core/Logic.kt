/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.DisconnectEvent
import io.github.rwpp.api.event.events.ModCheckEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.PlayerIds
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.io.SizeUtils
import io.github.rwpp.api.net.Net
import io.github.rwpp.api.net.packets.ModPacket
import io.github.rwpp.api.net.packets.ModPackets
import io.github.rwpp.api.net.registerPacketListener
import io.github.rwpp.api.server.ServerInfoResponder
import io.github.rwpp.appKoin
import io.github.rwpp.config.CoreData
import io.github.rwpp.config.Settings
import io.github.rwpp.game.reloadMods
import io.github.rwpp.modDir
import io.github.rwpp.net.*
import io.github.rwpp.projectVersion
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.*

//typealias TargetPositionWithUnits = Triple<Double, Double, List<GameUnit>>

private sealed interface ReleaseUpdateUiState {
    data object Ready : ReleaseUpdateUiState
    data class Downloading(val progress: ReleaseDownloadProgress) : ReleaseUpdateUiState
    data object Cancelled : ReleaseUpdateUiState
    data class Failed(val message: String) : ReleaseUpdateUiState
    data class Installed(val result: ReleaseUpdateResult) : ReleaseUpdateUiState
}

object Logic : Initialization {
    private val modDownloadMutex = Mutex()
    private var modQueue: LinkedList<String>? = null
    private var requiredMods: List<String>? = null
    private var releaseUpdateJob: Job? = null
    private var releaseUpdateGeneration = 0
    private var releaseUpdateState by mutableStateOf<ReleaseUpdateUiState>(ReleaseUpdateUiState.Ready)

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
                        net.sendPacketToServer(
                            ModPacket.RequestPacket().apply { this.mods = missingMods.joinToString(",") })
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

    }

    override fun onGameLoaded() {
        GameScope.launch {
            when (appKoin.get<Settings>().effectLimitForAllEffects) {
                "Zero" -> setEffectLimits(0)
                "Unlimited" -> setEffectLimits(Int.MAX_VALUE)
            }
        }
    }

    fun getNextPlayerId(): Int = PlayerIds.next()

    fun registerListeners() {
        val owner = EventOwner(RwppExtensionRuntime.extensionContext)
        val game = appKoin.get<Game>()
        val net = appKoin.get<Net>()
        ServerInfoResponder.install(owner, net, RwppClientServerInfo)
        net.registerPacketListener(owner, ModPackets.MOD_DATA) { _, packet ->
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
                        GameScope.launch(Dispatchers.Main) {
                            UI.showWarning(
                                "Mod download failed: ${it.message}",
                                true
                            )
                        }
                    }
                }
            }
            true
        }
    }

    suspend fun checkUpdate(checkImmediately: Boolean = false) {
        val coreData = appKoin.get<CoreData>()
        val settings = appKoin.get<Settings>()
        val repository = rwmlRepository()
        val isAndroid = appKoin.get<io.github.rwpp.AppContext>().isAndroid()
        val now = System.currentTimeMillis()
        var profile: LatestVersionProfile? = null
        var checkUpdateDialogVisible = false
        coreData.lastPlayTime = now
        // per day
        if (checkImmediately || (now - (coreData.lastAutoCheckUpdateTime + 1000 * 60 * 60 * 24) > 0) || coreData.debug) {
            if ((settings.autoCheckUpdate || checkImmediately) && repository != null) {
                // 目录读取在 rwppProfile 内切换到 IO；失败时保留原有状态，不阻止游戏启动。
                val latestProfile = runCatching { repository.rwppProfile(isAndroid) }.getOrNull()
                profile = latestProfile

                if (latestProfile != null) {
                    coreData.lastAutoCheckUpdateTime = now
                    val latestVersion = runCatching { normalizeReleaseVersion(latestProfile.version) }
                        .getOrDefault(latestProfile.version)
                    val ignoredVersion = settings.ignoreVersion?.let { ignored ->
                        runCatching { normalizeReleaseVersion(ignored) }.getOrDefault(ignored)
                    }
                    val isNewer = compareReleaseVersions(latestVersion, projectVersion)?.let { it > 0 } == true
                    if (isNewer && (ignoredVersion != latestVersion || coreData.debug)) {
                        checkUpdateDialogVisible = true
                    }
                }
            }
        }

        if (checkUpdateDialogVisible) {
            val latest = profile ?: return
            releaseUpdateState = ReleaseUpdateUiState.Ready
            UI.showDialog { dismiss ->
                ReleaseUpdateDialog(latest, isAndroid, settings, dismiss)
            }
        }
    }

    private fun startReleaseUpdate(profile: LatestVersionProfile, isAndroid: Boolean) {
        if (releaseUpdateJob?.isActive == true) return
        val repository = rwmlRepository() ?: return
        val totalBytes = profile.archiveSize(isAndroid)?.takeIf { it > 0 }
        val generation = ++releaseUpdateGeneration
        releaseUpdateState = ReleaseUpdateUiState.Downloading(ReleaseDownloadProgress(0, totalBytes))
        releaseUpdateJob = GameScope.launch(Dispatchers.IO) {
            try {
                val result = repository.installRwppUpdate(isAndroid) { progress ->
                    withContext(Dispatchers.Main) {
                        if (generation == releaseUpdateGeneration) releaseUpdateState =
                            ReleaseUpdateUiState.Downloading(progress)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (generation == releaseUpdateGeneration) releaseUpdateState =
                        ReleaseUpdateUiState.Installed(result)
                }
            } catch (cancelled: CancellationException) {
                // The explicit cancel action already publishes the cancelled state.
                throw cancelled
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main) {
                    if (generation == releaseUpdateGeneration) {
                        releaseUpdateState =
                            ReleaseUpdateUiState.Failed(failure.message ?: readI18n("rwpp.update.unknownError"))
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (generation == releaseUpdateGeneration) releaseUpdateJob = null
                }
            }
        }
    }

    private fun cancelReleaseUpdate() {
        releaseUpdateGeneration++
        releaseUpdateJob?.cancel()
        releaseUpdateJob = null
    }

    private fun formatUpdateBytes(bytes: Long): String {
        val mib = 1024L * 1024L
        if (bytes < mib) return "${bytes / 1024L} KiB"
        val tenths = bytes * 10L / mib
        return "${tenths / 10L}.${tenths % 10L} MiB"
    }

    @Composable
    private fun ReleaseUpdateDialog(
        profile: LatestVersionProfile,
        isAndroid: Boolean,
        settings: Settings,
        dismiss: () -> Unit,
    ) {
        val archiveSize = remember(profile, isAndroid) { profile.archiveSize(isAndroid) }
        val state = releaseUpdateState
        DisposableEffect(profile) {
            onDispose { cancelReleaseUpdate() }
        }

        AeroPanel(Modifier.widthIn(max = 620.dp).fillMaxWidth(0.94f)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AeroHeader(title = "RWPP UPDATE", tag = profile.version)
                AeroDivider()
                Text(readI18n("rwpp.update.available", profile.version), style = MaterialTheme.typography.titleMedium)
                if (profile.prerelease) {
                    Text(readI18n("rwpp.update.prerelease"), color = MaterialTheme.colorScheme.tertiary)
                }
                Text(readI18n(if (isAndroid) "rwpp.update.platformAndroid" else "rwpp.update.platformJvm"))

                if (archiveSize == null) {
                    Text(
                        readI18n("rwpp.update.packageMissing"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                when (state) {
                    is ReleaseUpdateUiState.Downloading -> {
                        val total = state.progress.totalBytes ?: 0L
                        val fraction =
                            if (total > 0L) (state.progress.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        Text(readI18n("rwpp.update.downloading"), fontSize = 12.sp)
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (total > 0L) {
                            Text(
                                "${formatUpdateBytes(state.progress.bytesDownloaded)} / ${formatUpdateBytes(total)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    is ReleaseUpdateUiState.Cancelled -> Text(readI18n("rwpp.update.cancelled"))
                    is ReleaseUpdateUiState.Failed -> Text(
                        readI18n("rwpp.update.failed", state.message),
                        color = MaterialTheme.colorScheme.error,
                    )

                    is ReleaseUpdateUiState.Installed -> {
                        val result = state.result
                        if (result.complete) {
                            Text(readI18n(if (result.restartRequired) "rwpp.update.installedRestart" else "rwpp.update.alreadyCurrent"))
                        } else {
                            val details = result.failed.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                            Text(
                                readI18n("rwpp.update.partialFailure", details),
                                color = MaterialTheme.colorScheme.error
                            )
                            if (result.restartRequired) Text(readI18n("rwpp.update.installedRestart"))
                        }
                    }

                    ReleaseUpdateUiState.Ready -> Unit
                }

                if (profile.body.isNotBlank()) {
                    AeroDivider()
                    Markdown(
                        profile.body,
                        modifier = Modifier.fillMaxWidth(),
                        colors = markdownColor(),
                        typography = markdownTypography(),
                    )
                }

                AeroDivider()
                when (state) {
                    is ReleaseUpdateUiState.Downloading -> AeroButton(
                        text = readI18n("rwpp.update.cancel"),
                        isDanger = true,
                        onClick = {
                            releaseUpdateState = ReleaseUpdateUiState.Cancelled
                            cancelReleaseUpdate()
                        },
                    )

                    is ReleaseUpdateUiState.Installed -> AeroButton(
                        text = readI18n("rwpp.common.close"),
                        onClick = dismiss,
                    )

                    is ReleaseUpdateUiState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AeroButton(
                            text = readI18n("rwpp.update.retry"),
                            enabled = archiveSize != null,
                            modifier = Modifier.weight(1f),
                            onClick = { startReleaseUpdate(profile, isAndroid) },
                        )
                        AeroButton(
                            text = readI18n("rwpp.common.close"),
                            modifier = Modifier.weight(1f),
                            onClick = dismiss,
                        )
                    }

                    ReleaseUpdateUiState.Ready, is ReleaseUpdateUiState.Cancelled -> {
                        AeroButton(
                            text = readI18n("rwpp.update.downloadInstall"),
                            enabled = archiveSize != null,
                            onClick = { startReleaseUpdate(profile, isAndroid) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AeroButton(
                                text = readI18n("rwpp.update.ignoreVersion"),
                                modifier = Modifier.weight(1f),
                                onClick = { settings.ignoreVersion = profile.version; dismiss() },
                            )
                            AeroButton(
                                text = readI18n("rwpp.update.later"),
                                modifier = Modifier.weight(1f),
                                onClick = dismiss,
                            )
                        }
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

    private fun setEffectLimits(limit: Int) {
        GameEngine.getInstance().effectEngine.apply {
            limitVerylow = limit
            limitLow = limit
            limitHigh = limit
            limitVeryhigh = limit
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
