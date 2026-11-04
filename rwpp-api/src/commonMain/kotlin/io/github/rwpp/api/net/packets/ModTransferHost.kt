/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.packets

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.logging.logger
import io.github.rwpp.api.net.Net
import io.github.rwpp.api.net.registerPacketListener
import io.github.rwpp.api.net.sendPacketToClient
import io.github.rwpp.api.util.zipFolderToByte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** The same mod-serving protocol for dedicated servers and client-hosted rooms. */
object ModTransferHost {
    private val transfers = Semaphore(2)
    private val activeTransfers =
        java.util.concurrent.ConcurrentHashMap.newKeySet<com.corrodinggames.rts.gameFramework.network.Connection>()
    private val readiness = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Team, Boolean>())

    fun isReady(player: Team): Boolean = readiness[player] ?: true
    fun setReady(player: Team, ready: Boolean) {
        readiness[player] = ready
    }

    fun install(owner: EventOwner, net: Net) {
        net.registerPacketListener(owner, ModPackets.MOD_REQUEST) { client, packet ->
            if (client == null || !activeTransfers.add(client)) return@registerPacketListener true
            val requested = packet.mods.split(',').filter(String::isNotBlank).toSet()
            GameScope.launch {
                try {
                    val room = appKoin.get<Game>().gameRoom
                    if (!room.isHost || !room.option.canTransferMod) return@launch
                    runCatching {
                        val player = room.getPlayerByClient(client) ?: return@runCatching
                        setReady(player, false)
                        val sources = GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>()
                            .filter { it.isEnabled && (it.getTitle() ?: it.name.orEmpty()) in requested }
                            .map { (it.getTitle() ?: it.name.orEmpty()) to (it.getSourcePath() ?: it.path.orEmpty()) }
                        transfers.withPermit {
                            withContext(Dispatchers.IO) {
                                sources.forEachIndexed { index, (name, path) ->
                                    if (!client.isOpen) return@withContext
                                    val file = File(path)
                                    require(file.isDirectory || file.length() <= ModPacket.MAX_MOD_BYTES) { "Mod is too large" }
                                    val data =
                                        if (file.isDirectory) file.zipFolderToByte(ModPacket.MAX_MOD_BYTES) else file.readBytes()
                                    client.sendPacketToClient(ModPacket.ModPackPacket().apply {
                                        this.index = index
                                        this.name = name
                                        modBytes = data
                                    })
                                }
                            }
                        }
                    }.onFailure { logger().error("Mod transfer failed", it) }
                } finally {
                    activeTransfers.remove(client)
                }
            }
            true
        }
        net.registerPacketListener(owner, ModPackets.MOD_READY) { client, _ ->
            GameScope.launch {
                val room = appKoin.get<Game>().gameRoom
                if (room.isHost && room.option.canTransferMod && client != null) {
                    room.getPlayerByClient(client)?.let { setReady(it, true) }
                }
            }
            true
        }
    }
}
