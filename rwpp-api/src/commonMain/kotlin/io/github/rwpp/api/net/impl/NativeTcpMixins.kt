/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.impl

import com.corrodinggames.rts.gameFramework.network.*
import io.github.rwpp.api.net.transport.TransportRuntime
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Unwraps reflection failures thrown by ASM's Operation.call bridge. */
internal fun unwrapNativeInvocationFailure(failure: Throwable): Throwable {
    var cause = failure
    while (cause is InvocationTargetException) {
        val target = cause.targetException ?: cause.cause ?: break
        if (target === cause) break
        cause = target
    }
    return cause
}

/** Keeps the original checked network exception visible to ConnectSocketToServerRunnable. */
internal fun <T> callNativeConnect(original: () -> T): T {
    try {
        return original()
    } catch (failure: Throwable) {
        // Preserve IOException, CancellationException and programming failures as their real types.
        throw unwrapNativeInvocationFailure(failure)
    }
}

internal object NativeTcpCallbacks {
    fun connect(address: String, forceTcp: Boolean, original: (String, Boolean) -> Socket): Socket {
        if (NativeNetworkSettings.mode == "vanilla" || !TransportRuntime.isInstalled) {
            return callNativeConnect { original(address, forceTcp) }
        }
        val prior = NativeNetworkSettings.tcpOnly.get()
        NativeNetworkSettings.tcpOnly.set(
            forceTcp || address.trim().startsWith("[TCP]") || NativeNetworkSettings.mode == "tcp"
        )
        try {
            // Keep native address/relay resolution, but choose our socket rather than native RUDP.
            return callNativeConnect { original(address, true) }
        } finally {
            NativeNetworkSettings.tcpOnly.set(prior)
        }
    }

    fun createSocket(): Socket = when {
        NativeNetworkSettings.mode == "vanilla" || !TransportRuntime.isInstalled -> Socket()
        else -> TransportRuntime.backend.nativeClientSocket(NativeNetworkSettings.tcpOnly.get())
    }

    private val fields by lazy {
        val type = Class.forName("com.corrodinggames.rts.gameFramework.network.NewConnectionRunnable")
        listOf("networkEngine", "listenPort", "udpMode", "serverSocket").associateWith {
            type.getDeclaredField(it).apply { isAccessible = true }
        }
    }

    fun openUdp(target: Any, udp: Boolean): Boolean {
        if (!udp || NativeNetworkSettings.mode != "auto" || !TransportRuntime.isInstalled || !TransportRuntime.backend.supportsNativeServer) return false
        val port = (fields.getValue("networkEngine").get(target) as NetworkEngine).networkPort
        fields.getValue("listenPort").setInt(target, port)
        fields.getValue("udpMode").setBoolean(target, true)
        fields.getValue("serverSocket").set(target, TransportRuntime.backend.nativeKcpListener(port))
        return true
    }

    fun createServerSocket(port: Int): ServerSocket =
        if (NativeNetworkSettings.mode == "vanilla" || !TransportRuntime.isInstalled || !TransportRuntime.backend.supportsNativeServer) ServerSocket(
            port
        ) else TransportRuntime.backend.nativeTcpListener(port)

    fun start(target: Any): Boolean = NativeTcpConnections.attach(target as Connection)
    fun send(target: Any, packet: Any): Boolean = NativeTcpConnections.send(target as Connection, packet as Packet)
    fun stop(target: Any) {
        ((target as Connection).socket as? NativePacketSocket)?.takeIf { it.handlesNativePackets }?.closeAfterWrites()
    }
}

/** System packets use ordered workers; ordinary game frames only enter the native queue. */
internal object NativeTcpConnections {
    private val engineField by lazy { Connection::class.java.getDeclaredField("engine").apply { isAccessible = true } }
    private val incomingField by lazy {
        NetworkEngine::class.java.getDeclaredField("incomingPacketQueue").apply { isAccessible = true }
    }
    private val sessions = ConcurrentHashMap<Connection, Session>()

    fun attach(connection: Connection): Boolean {
        val socket = connection.socket as? NativePacketSocket ?: return false
        if (!socket.handlesNativePackets) return false
        val engine = engineField.get(connection) as NetworkEngine

        @Suppress("UNCHECKED_CAST")
        val incoming = incomingField.get(engine) as ConcurrentLinkedQueue<Packet>
        val session = Session(connection, socket)
        check(sessions.putIfAbsent(connection, session) == null) { "Native TCP connection already attached" }
        val receivePacket: (Int, ByteArray) -> Unit = { type, bytes ->
            if (connection.isOpen) {
                val packet = Packet(type).apply { data = bytes; this.connection = connection }
                connection.readCallCount++
                if (type > 100) engine.processSystemPacket(packet)
                else {
                    check(incoming.size < 8192) { "Native game packet queue full" }
                    incoming.add(packet)
                }
            }
        }
        socket.startReceiving(
            limit = { if (!connection.connected) 10_000 else if (engine.isServer) 1_000_000 else NativePacketSocket.MAX_PACKET_BYTES },
            receive = receivePacket,
            failure = { reason ->
                sessions.remove(connection)
                connection.handleRemoteDisconnect(true, true, reason)
            },
            fastReceive = { type, bytes ->
                if (type > 100) false
                else {
                    receivePacket(type, bytes); true
                }
            },
        )
        return true
    }

    fun send(connection: Connection, packet: Packet): Boolean {
        if ((connection.socket as? NativePacketSocket)?.handlesNativePackets != true) return false
        sessions[connection]?.send(packet)
        // A closed Netty connection must never fall through to a native worker that doesn't exist.
        return true
    }

    private class Session(val connection: Connection, val socket: NativePacketSocket) {
        private var lastPacket: Packet? = null

        @Synchronized
        fun send(packet: Packet) {
            if (!connection.isOpen) return
            val wire = if (packet is WrappedPacket) {
                val output = OutputNetStream()
                output.writeInt(packet.targetConnectionId)
                val repeat = lastPacket === packet.packet && connection.multicast
                if (!repeat) {
                    output.writeInt(packet.packet.type)
                    output.writeBytesWithLength(packet.packet.data)
                }
                lastPacket = packet.packet
                output.createPacket(if (repeat) 176 else 175)
            } else packet
            socket.send(wire.type, wire.data, wire.channel)
        }
    }
}
