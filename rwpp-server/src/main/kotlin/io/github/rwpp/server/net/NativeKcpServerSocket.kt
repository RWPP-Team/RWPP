/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server.net

import io.github.rwpp.api.net.impl.NativeKcpSocket
import io.github.rwpp.api.net.transport.TransportListener
import io.github.rwpp.api.net.transport.TransportPeer
import io.github.rwpp.api.net.transport.TransportRuntime
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The native accept/admission loop receives authenticated KCP welcomes from the UDP listener. */
class NativeKcpServerSocket(runtime: ServerTransportBackend, port: Int) : ServerSocket() {
    constructor(port: Int) : this(TransportRuntime.backend as ServerTransportBackend, port)

    private val pending = ArrayBlockingQueue<NativeKcpSocket>(128)
    private val sessions = ConcurrentHashMap<TransportPeer, NativeKcpSocket>()
    private val closed = AtomicBoolean()
    private val transport = NettyKcpServerTransport(runtime, NativeKcpSocket.config(port))

    init {
        transport.start(object : TransportListener {
            override fun connected(peer: TransportPeer) = Unit
            override fun received(peer: TransportPeer, payload: ByteArray) {
                val existing = sessions[peer]
                if (existing != null) {
                    existing.accept(payload); return
                }
                val nonce = NativeKcpSocket.helloNonce(payload) ?: run { peer.close(); return }
                val socket = NativeKcpSocket(peer, localPort)
                if (closed.get() || !peer.trySend(NativeKcpSocket.welcome(nonce)) || !pending.offer(socket)) {
                    socket.close(); return
                }
                sessions[peer] = socket
                if (closed.get()) {
                    sessions.remove(peer, socket); pending.remove(socket); socket.close()
                }
            }

            override fun disconnected(peer: TransportPeer) {
                sessions.remove(peer)?.fail("KCP connection closed")
            }
        })
    }

    override fun accept(): Socket {
        while (!closed.get()) {
            val socket = pending.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (!closed.get() && !socket.isClosed) return socket
            socket.close()
        }
        throw SocketException("Server socket closed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.clear(); sessions.values.toList().forEach { it.close() }; sessions.clear(); transport.close()
    }

    override fun isClosed() = closed.get()
    override fun isBound() = !closed.get()
    override fun getLocalPort() = transport.localAddress?.port ?: -1
    override fun getInetAddress(): InetAddress = InetAddress.getByName("0.0.0.0")
}
