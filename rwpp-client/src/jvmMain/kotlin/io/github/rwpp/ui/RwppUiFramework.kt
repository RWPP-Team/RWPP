/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.api.ApiRuntime
import io.github.rwml.api.ProgressListener
import io.github.rwpp.api.config.ConfigIO
import io.github.rwml.api.ui.UiFramework
import io.github.rwml.api.ui.UiHost
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigModule
import io.github.rwpp.config.ProductTranslations
import io.github.rwpp.desktop.DesktopModule
import io.github.rwpp.game.units.comp.CompModule
import io.github.rwpp.widget.loadingMessage
import org.koin.plugin.module.dsl.modules
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the host's native game launcher, window and LibRocket interface. */
class RwppUiFramework : UiFramework {
    private val started = AtomicBoolean(false)

    override fun start(host: UiHost) {
        check(started.compareAndSet(false, true)) { "RWPP desktop UI has already been started" }
        ApiRuntime.configureKoin {
            modules(ConfigModule::class, DesktopModule::class, CompModule::class, UIModule::class)
        }
        appKoin.get<ConfigIO>().readAllConfig()
        ProductTranslations.install()
        appKoin.get<AppContext>().init()
        val report = host.gameBoot().start(ProgressListener { _, message -> loadingMessage = message })
        check(report.isClean) {
            "RWML game startup failed: ${report.failed().entries.joinToString { "${it.key}: ${it.value}" }}"
        }
    }
}
