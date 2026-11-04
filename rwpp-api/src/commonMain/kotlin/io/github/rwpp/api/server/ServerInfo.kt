/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.server

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.config.ServerType
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.logging.logger
import io.github.rwpp.api.net.InternalPacketType
import io.github.rwpp.api.net.Net
import io.github.rwpp.api.net.PacketListenerHandle
import io.github.rwpp.api.net.ServerStatus
import io.github.rwpp.api.net.packets.ServerInfoPackets
import io.github.rwpp.api.net.packets.ServerPacket
import io.github.rwpp.api.net.registerPacketListener
import io.github.rwpp.api.net.sendPacketToClient
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

private val log = logger()

/**
 * Reads the server-info answer of one [ServerType.Server] entry.
 *
 * The protocol is specific to RWPP: a server that does not implement it either refuses the
 * connection or never answers, so implementations are expected to fail with an I/O error.
 */
fun interface ServerInfoQuery {
    fun query(config: ServerConfig): ServerPacket.ServerInfoReceivePacket
}

/**
 * Blocking socket implementation of the server-info query.
 *
 * Call it off the game thread. The read is limited to [MAX_ANSWER_BYTES], so a hostile or broken
 * server cannot make the caller allocate an arbitrary buffer.
 */
class SocketServerInfoQuery(
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : ServerInfoQuery {
    override fun query(config: ServerConfig): ServerPacket.ServerInfoReceivePacket {
        val parts = config.ip.split(":")
        val address = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT

        Socket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.connect(InetSocketAddress(address, port), timeoutMillis)

            val query = ServerPacket.ServerInfoGetPacket().toBytes()
            DataOutputStream(socket.getOutputStream()).apply {
                writeInt(query.size)
                writeInt(InternalPacketType.PRE_GET_SERVER_INFO_FROM_LIST.type)
                write(query)
                flush()
            }

            val answer = DataInputStream(socket.getInputStream())
            while (true) {
                val size = answer.readInt()
                val type = answer.readInt()
                require(size >= 0) { "Negative server-info answer length: $size" }
                val body = ByteArray(size.coerceAtMost(MAX_ANSWER_BYTES))
                answer.readFully(body)
                if (type == InternalPacketType.RECEIVE_SERVER_INFO_FROM_LIST.type) {
                    return ServerPacket.ServerInfoReceivePacket().apply {
                        readPacket(InputNetStream(DataInputStream(ByteArrayInputStream(body))))
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 5123
        const val DEFAULT_TIMEOUT_MILLIS = 5000
        const val MAX_ANSWER_BYTES = 10 * 1024
    }
}

/**
 * Describes the room this process hosts.
 *
 * The answer is read from live game state, so implementations must produce it on the game thread.
 * Returning null means the process is not hosting and must stay silent.
 */
fun interface ServerInfoProvider {
    fun describe(): ServerPacket.ServerInfoReceivePacket?
}

/**
 * Builds the server-info answer of [game]'s room, using the enabled mod list of the running game.
 *
 * [productLabel] is the version string shown by the marketing list, for example the product name and
 * game version of the answering build. Returns null when this process is not the room host.
 */
fun serverInfoOfRoom(game: Game, productLabel: String): ServerPacket.ServerInfoReceivePacket? {
    val room = game.gameRoom
    if (!room.isHost) return null
    return ServerPacket.ServerInfoReceivePacket(
        room.localPlayer.name + "'s game",
        room.getPlayers().size,
        room.maxPlayerCount,
        room.selectedMap.mapName,
        "",
        productLabel,
        GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>()
            .filter { it.isEnabled }
            .joinToString(", ") { it.getTitle() ?: it.name.orEmpty() },
        if (game.state.isPlaying()) ServerStatus.InGame else ServerStatus.BattleRoom,
    )
}

/** Answers the server-info query of the multiplayer server list for this process. */
object ServerInfoResponder {
    /**
     * Installs the query listener owned by [owner]. The answer is built and sent on the game thread;
     * a provider that is not hosting returns null and the query is left unanswered.
     */
    fun install(owner: EventOwner, net: Net, provider: ServerInfoProvider): PacketListenerHandle =
        net.registerPacketListener(owner, ServerInfoPackets.QUERY) { client, _ ->
            if (client != null) {
                GameScope.launch {
                    val answer = try {
                        provider.describe()
                    } catch (failure: Throwable) {
                        log.error("Could not describe the hosted server", failure)
                        null
                    }
                    answer?.let { client.sendPacketToClient(it) }
                }
            }
            true
        }
}
