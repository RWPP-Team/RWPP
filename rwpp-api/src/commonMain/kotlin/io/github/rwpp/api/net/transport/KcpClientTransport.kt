package io.github.rwpp.api.net.transport

/** KCP protocol facade; the installed platform owns sockets and execution. */
class KcpClientTransport(
    config: TransportConfig,
    host: String,
    messages: TransportMessages = TransportMessages.DEFAULT,
) : ClientTransport by TransportRuntime.backend.kcpClient(config, host, messages)
