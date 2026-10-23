package io.github.rwpp.api.net.transport

/** Server facade; listening and worker management are supplied by rwpp-server. */
class KcpServerTransport(config: TransportConfig = TransportConfig()) : CountedServerTransport {
    private val delegate = TransportRuntime.backend.kcpServer(config)
    override val localAddress: TransportAddress? get() = delegate.localAddress
    override val peerCount: Int get() = (delegate as? CountedServerTransport)?.peerCount ?: 0
    override fun start(listener: TransportListener) = delegate.start(listener)
    override fun close() = delegate.close()
}
