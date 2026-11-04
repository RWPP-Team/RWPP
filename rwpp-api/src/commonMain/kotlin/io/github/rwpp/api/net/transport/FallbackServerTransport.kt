/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.transport

/**
 * 同一端口上的 UDP/KCP 与 TCP。
 *
 * 客户端优先使用 KCP，未收到应用层欢迎时改用 TCP。两种传输共用房间、身份与连接配额，因此这里只做分发：两个
 * 监听器都转发到同一个 [TransportListener]。
 *
 * 端口由 TCP 先确定。玩家输入的端口只有一个，端口为 0（由系统挑选）时两种传输必须落在同一个实际端口上，否则
 * 查询到达的端口上没有监听者。
 *
 * @param banner 首帧应答；用于在同一端口上回答原版的服务器信息查询
 */
class FallbackServerTransport(
    private val config: TransportConfig,
    banner: TransportBanner? = null,
) : ServerTransport {

    private val tcp = TcpServerTransport(config, banner)

    @Volatile
    private var kcp: KcpServerTransport? = null

    private var running = false

    override val localAddress: TransportAddress?
        get() = tcp.localAddress ?: kcp?.localAddress

    /** 两种传输当前连接数之和。 */
    val peerCount: Int get() = tcp.peerCount + (kcp?.peerCount ?: 0)

    @Synchronized
    override fun start(listener: TransportListener) {
        check(!running) { "The fallback transport is already running" }
        val forwarding = Forwarding(listener)
        val bound = try {
            tcp.start(forwarding)
            val port = requireNotNull(tcp.localAddress).port
            val kcpTransport = KcpServerTransport(if (port == config.port) config else config.copy(port = port))
            kcp = kcpTransport
            kcpTransport.start(forwarding)
            running = true
            port
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        check(bound == kcp?.localAddress?.port || kcp == null) {
            "The TCP and KCP transports bound to different ports"
        }
    }

    @Synchronized
    override fun close() {
        running = false
        kcp?.close()
        kcp = null
        tcp.close()
    }

    /** 把两种传输的回调并入同一个监听器，连接标识保持传输内唯一。 */
    private class Forwarding(private val delegate: TransportListener) : TransportListener {
        override fun connected(peer: TransportPeer) = delegate.connected(peer)

        override fun received(peer: TransportPeer, payload: ByteArray) = delegate.received(peer, payload)

        override fun disconnected(peer: TransportPeer) = delegate.disconnected(peer)
    }
}
