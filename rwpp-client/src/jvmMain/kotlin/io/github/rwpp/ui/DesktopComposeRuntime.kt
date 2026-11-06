/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import com.corrodinggames.rts.java.SlickMainGame
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.logging.logger
import io.github.rwpp.appKoin
import io.github.rwpp.desktop.OffscreenComposeRenderer
import io.github.rwpp.widget.isLoading
import kotlinx.coroutines.runBlocking
import org.lwjgl.opengl.Display
import org.newdawn.slick.GameContainer
import javax.swing.SwingUtilities

/** Compose owns its scene on AWT; each operation borrows Slick's current Display context. */
internal object DesktopComposeRuntime {
    @Volatile
    private var enabled = false

    @Volatile
    private var renderer: OffscreenComposeRenderer? = null

    @Volatile
    private var contextBridge: SlickAwtGlContextBridge? = null

    @Volatile
    private var inputListener: SlickAwtComposeInputListener? = null

    @Volatile
    private var attaching = false

    @Volatile
    private var nativeDocumentsCleanupAttempted = false
    private val framePacer = UiFramePacer()
    private val settings by lazy { appKoin.get<io.github.rwpp.config.Settings>() }

    /** Screen changes must repaint the offscreen texture, otherwise the menu wallpaper lingers. */
    @Volatile
    private var lastGameplayOwnership = false

    fun requestFrame() {
        framePacer.request()
    }

    private val engineField by lazy {
        SlickMainGame::class.java.getDeclaredField("gameEngine").apply { isAccessible = true }
    }
    private val loadedField by lazy {
        SlickMainGame::class.java.getDeclaredField("loadStarted").apply { isAccessible = true }
    }

    fun enable() {
        enabled = true
    }

    fun isAttached(): Boolean = renderer != null || attaching

    fun render(game: Any, nativeContainer: Any) {
        if (!enabled || !loadedField.getBoolean(game) || engineField.get(game) == null) return
        val container = nativeContainer as GameContainer
        // The engine skips updateFrameInternal while its main menu is open.
        io.github.rwpp.api.ApiRuntime.pumpHeadlessFrame()
        val scene = renderer ?: run {
            attaching = true
            try {
                // Game startup invokes the Slick-owned game dispatcher. Keep this phase on the
                // Slick thread and finish it before the Compose dispatcher is created on AWT.
                cleanupNativeMenuDocuments(game)
                runBlocking { appKoin.get<Game>().load { } }

                val bridge = SlickAwtGlContextBridge()
                val createdRenderer = bridge.call {
                    val displayCurrent = Display.isCurrent()
                    logger().info(
                        "Creating RWPP desktop Compose scene on thread='${Thread.currentThread().name}#${Thread.currentThread().id}', " +
                                "awtEdt=${SwingUtilities.isEventDispatchThread()}, Display.isCurrent=$displayCurrent"
                    )
                    check(displayCurrent) { "Slick's Display context is not current while creating Compose scene" }
                    val created = OffscreenComposeRenderer()
                    try {
                        created._input = container.input
                        created.updateBounds(container.width, container.height)
                        isLoading = false
                        created.setContent { MainComposeContent() }
                        created
                    } catch (error: Throwable) {
                        runCatching { created.close() }
                        throw error
                    }
                }

                val listener = SlickAwtComposeInputListener(createdRenderer, bridge)
                container.input.addPrimaryListener(listener)
                renderer = createdRenderer
                contextBridge = bridge
                inputListener = listener
                appKoin.get<io.github.rwpp.AppContext>().onExit {
                    // Compose callbacks run inside the synchronous Slick input bridge, so this
                    // call is reentrant on AWT with the same Display context already borrowed.
                    container.input.removeListener(listener)
                    bridge.call { createdRenderer.close() }
                    renderer = null
                    inputListener = null
                    contextBridge = null
                }
                logger().info("RWPP desktop Compose scene attached to game window")
                createdRenderer
            } finally {
                attaching = false
            }
        }
        val bridge = contextBridge ?: return
        // Runs on the Slick thread that owns the LWJGL window, outside the AWT GL bridge.
        // The HUD frame pacer can skip a Compose frame, so this state must not be gated by it.
        val gameplay = scene.syncSystemShortcutGate()
        if (gameplay != lastGameplayOwnership) {
            lastGameplayOwnership = gameplay
            framePacer.request()
        }
        if (framePacer.shouldRender(System.nanoTime(), settings.hudRefreshRate)) {
            bridge.call { scene.render() }
        }
        // This method only composites an existing GL texture. Keep it on Slick's
        // current context instead of transferring that context to AWT every game frame.
        scene.drawToScreen()
        // Fixed-function Compose compositing invalidates Slick's cached shader/texture bindings.
        com.corrodinggames.rts.java.JavaCanvas.currentShader = null
        com.corrodinggames.rts.java.JavaCanvas.lastBoundGraphics = null
        org.newdawn.slick.opengl.TextureImpl.unbind()
    }

    /** Compose replaces the native menu stack, so release the hidden LibRocket document stack once. */
    private fun cleanupNativeMenuDocuments(game: Any) {
        if (nativeDocumentsCleanupAttempted) return
        nativeDocumentsCleanupAttempted = true
        runCatching {
            val launcher =
                SlickMainGame::class.java.getDeclaredField("mainLauncher").apply { isAccessible = true }.get(game)
                    ?: return
            val rocket = launcher.javaClass.getDeclaredField("libRocket").apply { isAccessible = true }.get(launcher)
                ?: return
            rocket.javaClass.getMethod("closeActiveDocument").invoke(rocket)
            rocket.javaClass.getMethod("clearHistory").invoke(rocket)
        }.onFailure { error ->
            logger().info("Could not release the hidden LibRocket menu stack: ${error.message}")
        }
    }
}
