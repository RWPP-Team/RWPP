/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api

import io.github.rwml.api.Extension
import io.github.rwml.api.ExtensionContext
import io.github.rwml.api.Rwml
import io.github.rwpp.api.command.CommandText
import io.github.rwpp.api.command.GameCommandRuntime

/** Built-in archive entry point that owns shared API initialization. */
class ApiExtension : Extension {
    override fun onLoad(context: ExtensionContext) {
        ApiRuntime.initialize(context)
        runCatching { Rwml.get()?.ui()?.messages() }.getOrNull()?.let(CommandText::attach)
        commands.registerHelpCommand()
        GameCommandRuntime.install(context)
        context.log("RWML API services initialized")
    }
}
