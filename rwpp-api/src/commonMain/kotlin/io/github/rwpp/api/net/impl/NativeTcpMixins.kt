package io.github.rwpp.api.net.impl

import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import com.corrodinggames.rts.gameFramework.network.Packet
import com.corrodinggames.rts.gameFramework.network.WrappedPacket
import kim.der.asm.api.annotation.*
import io.github.rwpp.api.net.transport.TransportRuntime
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine", priority = 900)
object NativeAddressConnectMixin {
    @JvmStatic
    @WrapMethod(method = "connectToServer(Ljava/lang/String;Z)Ljava/net/Socket;", require = 1)
    fun connect(address: String, forceTcp: Boolean, original: Operation<Socket>): Socket {
        if (NativeNetworkSettings.mode == "vanilla" || !TransportRuntime.isInstalled) return original.call(address, forceTcp)
        val prior = NativeNetworkSettings.tcpOnly.get()
        NativeNetworkSettings.tcpOnly.set(forceTcp || address.trim().startsWith("[TCP]") || NativeNetworkSettings.mode == "tcp")
        try {
            // Keep native address/relay resolution, but choose our socket rather than native RUDP.
            return original.call(address, true)
        } finally { NativeNetworkSettings.tcpOnly.set(prior) }
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine", priority = 1100)
object NativeTcpConnectMixin {
    @JvmStatic
    @Redirect(
        method = "connectToServer(Ljava/lang/String;Z)Ljava/net/Socket;",
        at = At(value = InjectionPoint.INVOKE, target = "java.net.Socket.<init>()V"),
        require = 1,
    )
    fun createSocket(): Socket = when {
        NativeNetworkSettings.mode == "vanilla" || !TransportRuntime.isInstalled -> Socket()
        else -> TransportRuntime.backend.nativeClientSocket(NativeNetworkSettings.tcpOnly.get())
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NewConnectionRunnable")
object NativeTcpListenMixin {
    private val fields by lazy {
        val type = Class.forName("com.corrodinggames.rts.gameFramework.network.NewConnectionRunnable")
        listOf("networkEngine", "listenPort", "udpMode", "serverSocket").associateWith {
            type.getDeclaredField(it).apply { isAccessible = true }
        }
    }

    @AsmInject(method = "openServerSocket(Z)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun openUdp(callback: CallbackInfo, target: Any, udp: Boolean) {
        if (!udp || NativeNetworkSettings.mode != "auto" || !TransportRuntime.isInstalled || !TransportRuntime.backend.supportsNativeServer) return
        val port = (fields.getValue("networkEngine").get(target) as NetworkEngine).networkPort
        fields.getValue("listenPort").setInt(target, port)
        fields.getValue("udpMode").setBoolean(target, true)
        fields.getValue("serverSocket").set(target, TransportRuntime.backend.nativeKcpListener(port))
        callback.cancel()
    }
    @JvmStatic
    @Redirect(
        method = "openServerSocket(Z)V",
        at = At(value = InjectionPoint.INVOKE, target = "java.net.ServerSocket.<init>(I)V"),
        require = 1,
    )
    fun createServerSocket(port: Int): ServerSocket =
        if (NativeNetworkSettings.mode == "vanilla" || !TransportRuntime.isInstalled || !TransportRuntime.backend.supportsNativeServer) ServerSocket(port) else TransportRuntime.backend.nativeTcpListener(port)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/Connection")
object NativeTcpConnectionMixin {
    @AsmInject(method = "startWorkers()V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun start(callback: CallbackInfo, target: Any) {
        if (NativeTcpConnections.attach(target as Connection)) callback.cancel()
    }

    @AsmInject(
        method = "sendPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun send(callback: CallbackInfo, target: Any, packet: Any) {
        if (NativeTcpConnections.send(target as Connection, packet as Packet)) callback.cancel()
    }

    @AsmInject(method = "stopSendWorkerAndRemove()V", target = InjectionPoint.TAIL, require = 1)
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
                else { receivePacket(type, bytes); true }
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

        @Synchronized fun send(packet: Packet) {
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
