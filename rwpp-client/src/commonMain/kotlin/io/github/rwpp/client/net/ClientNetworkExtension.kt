/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the GNU AGPLv3 license.
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
