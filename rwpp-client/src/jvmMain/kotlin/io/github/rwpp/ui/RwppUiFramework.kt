/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwml.api.ui.UiFramework
import io.github.rwml.api.ui.UiHost
import io.github.rwpp.AppContext
import io.github.rwpp.api.ApiRuntime
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.appKoin
import io.github.rwpp.client.api.clientServices
import io.github.rwpp.config.Settings
import io.github.rwpp.desktop.DesktopModule
import io.github.rwpp.game.units.comp.CompModule
import io.github.rwpp.logic.LogicModule
import io.github.rwpp.logic.config.ConfigModule
import io.github.rwpp.logic.config.migrateTerrainEdgeBuildingPreference
import io.github.rwpp.logic.config.registerClientConfigSerializers
import io.github.rwpp.logic.i18n.ProductTranslations
import io.github.rwpp.widget.loadingMessage
import org.koin.plugin.module.dsl.modules
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps RWML's game window and attaches Compose from its render thread. */
class RwppUiFramework : UiFramework {
    private val started = AtomicBoolean(false)

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    override fun start(host: UiHost) {
        check(started.compareAndSet(false, true)) { "RWPP desktop UI has already been started" }
        // Custom CanvasLayersComposeScene hosts initialize the Skiko backend explicitly in 1.13.
        androidx.compose.ui.platform.registerSkikoComposeImplementation()
        ApiRuntime.configureKoin {
            modules(clientServices)
            modules(ConfigModule::class, LogicModule::class, DesktopModule::class, CompModule::class, UIModule::class)
        }
        registerClientConfigSerializers()
        val configIO = appKoin.get<ConfigIO>()
        configIO.readAllConfig()
        if (appKoin.get<Settings>().migrateTerrainEdgeBuildingPreference()) {
            configIO.saveConfig(appKoin.get<Settings>())
        }
        ProductTranslations.install()
        appKoin.get<AppContext>().init()
        DesktopComposeRuntime.enable()
        val report = host.gameBoot().start { _, message -> loadingMessage = message }
        check(report.isClean) {
            "RWML game startup failed: ${report.failed().entries.joinToString { "${it.key}: ${it.value}" }}"
        }
    }
}
