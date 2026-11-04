/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.transport

/** Server facade; listening and worker management are supplied by rwpp-server. */
class KcpServerTransport(config: TransportConfig = TransportConfig()) : CountedServerTransport {
    private val delegate = TransportRuntime.backend.kcpServer(config)
    override val localAddress: TransportAddress? get() = delegate.localAddress
    override val peerCount: Int get() = (delegate as? CountedServerTransport)?.peerCount ?: 0
    override fun start(listener: TransportListener) = delegate.start(listener)
    override fun close() = delegate.close()
}
