/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api

import io.github.rwml.api.ExtensionContext
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.GameEventPublisher
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules

/** Runtime owned by the built-in API extension and shared with host-side extension modules. */
object ApiRuntime {
    private val lock = Any()
    private var application: KoinApplication? = null
    private var context: ExtensionContext? = null
    private val components = InitializationRegistry()
    private var gameLoaded = false

    val extensionContext: ExtensionContext
        get() = checkNotNull(context) {
            "The RWML API extension has not been loaded"
        }

    /** Starts the one Koin container used by the API and the application extension. */
    internal fun initialize(extensionContext: ExtensionContext) {
        synchronized(lock) {
            check(context == null) { "The RWML API extension was loaded more than once" }
            context = extensionContext
            extensionContext.own(io.github.rwpp.api.net.transport.TransportRuntime)
            val started = startKoin {
                modules(RwmlApiModule::class)
            }
            application = started
            appKoin = started.koin
            gameScope = GameScope
            GameMainDispatcher.startPump()
        }
    }

    /** Adds the platform and application modules to the same container the API already created. */
    fun configureKoin(configuration: KoinApplication.() -> Unit) {
        synchronized(lock) {
            val started = checkNotNull(application) {
                "The RWML API extension must load before application Koin modules are installed"
            }
            configuration(started)
        }
    }

    /** Custom headless loops call this once per frame, including while waiting in a lobby.
     * Such loops bypass updateFrameInternal, which normally drains game-thread coroutines.
     */
    fun pumpHeadlessFrame() {
        checkNotNull(application) { "The RWPP API extension must load before the server loop starts" }
        GameMainDispatcher.drainNativeFrame()
    }

    /** Wait for a custom server loop while keeping suspended game-thread work on its owner. */
    fun <T> runHeadlessLoop(block: suspend () -> T): T =
        GameMainDispatcher.runBlockingWithGameDispatcher(block)

    /** Runs all API and application initialization hooks once the Koin modules are ready. */
    fun initializeComponents() {
        synchronized(lock) {
            val koin = checkNotNull(application) {
                "The RWML API extension must load before components are initialized"
            }.koin
            components.initialize(koin.getAll<Initialization>(), gameLoaded)
        }
    }

    /** Client calls this after loading its configuration and declaring its UI services. */
    fun initializeLateComponents() {
        synchronized(lock) { if (gameLoaded) initializeComponents() }
    }

    /** Finishes the shared game-loaded lifecycle, then publishes the API event. */
    fun onGameLoaded() {
        GameMainDispatcher.noteEngineInitialized()
        initializeComponents()
        val publish = synchronized(lock) {
            if (gameLoaded) return
            val koin = checkNotNull(application) {
                "The RWML API extension must load before the game is loaded"
            }.koin
            components.initialize(koin.getAll<Initialization>(), engineReady = true)
            gameLoaded = true
            true
        }
        if (publish) {
            GameEventPublisher.gameLoaded()
        }
    }
}
