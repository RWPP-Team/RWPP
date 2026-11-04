/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp

import io.github.rwml.api.Extension
import io.github.rwml.api.ExtensionContext

/** Runtime services supplied by the RWML extension host. */
object RwppExtensionRuntime {
    private var context: ExtensionContext? = null

    val extensionContext: ExtensionContext
        get() = checkNotNull(context) {
            "RWPP must be loaded by RWML before platform runtime services are used"
        }

    internal fun initialize(context: ExtensionContext) {
        check(this.context == null) { "RWPP Extension.onLoad was called more than once" }
        this.context = context
    }
}

/** RWML extension entry point. */
class RwppExtension : Extension {
    override fun onLoad(context: ExtensionContext) {
        io.github.rwpp.api.net.transport.TransportRuntime.installIfAbsent {
            io.github.rwpp.client.net.JdkTransportBackend()
        }
        RwppExtensionRuntime.initialize(context)
        context.log("RWPP extension services initialized")
    }
}
