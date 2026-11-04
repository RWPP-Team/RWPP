/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.transport

/** TCP fallback facade; I/O is supplied by the client or server backend. */
class TcpClientTransport(
    config: TransportConfig,
    host: String,
    connectTimeoutMillis: Int = 1500,
    messages: TransportMessages = TransportMessages.DEFAULT,
) : ClientTransport by TransportRuntime.backend.tcpClient(config, host, connectTimeoutMillis, messages)
