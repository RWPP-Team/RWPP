package io.github.rwpp.api.net.transport

/** TCP fallback facade; I/O is supplied by the client or server backend. */
class TcpClientTransport(
    config: TransportConfig,
    host: String,
    connectTimeoutMillis: Int = 1500,
    messages: TransportMessages = TransportMessages.DEFAULT,
) : ClientTransport by TransportRuntime.backend.tcpClient(config, host, connectTimeoutMillis, messages)
