/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.Packet
import java.net.Socket
import java.util.*

/** Bind before native receive workers start, so an old worker cannot affect its replacement. */
internal object ClientPacketSessionGuard {
    private data class Session(val socket: Socket, val generation: Long)

    private val sessions = WeakHashMap<NetworkEngine, Session>()
    private var nextGeneration = 0L

    @Synchronized
    fun begin(network: NetworkEngine, socket: Socket) {
        sessions[network] = Session(socket, ++nextGeneration)
    }

    @Synchronized
    fun generation(network: NetworkEngine): Long = sessions[network]?.generation ?: 0L

    fun isStale(network: NetworkEngine, packet: Packet): Boolean {
        if (network.isServer) return false
        var connection: Connection = packet.connection ?: return false // Local/internal packets.
        if (!network.networked || !connection.isOpen) return true
        val expected = synchronized(this) { sessions[network]?.socket } ?: return false
        // Relay-forwarded connections belong to their physical transport's session too.
        repeat(16) {
            if (connection.socket === expected) return false
            connection = connection.parent ?: return true
        }
        return true
    }
}
