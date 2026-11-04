/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.client.net

import io.github.rwml.api.Extension
import io.github.rwml.api.ExtensionContext
import io.github.rwpp.api.net.transport.TransportRuntime

/** Installs the JDK client backend for extensions that consume the network runtime jar directly. */
class ClientNetworkExtension : Extension {
    override fun onLoad(context: ExtensionContext) {
        TransportRuntime.installIfAbsent { JdkTransportBackend() }
        context.log("RWPP JDK client network backend initialized")
    }
}
