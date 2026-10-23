/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.Lifecycle
import io.github.rwpp.api.ApiRuntime
import io.github.rwml.api.ui.UiContentFactory
import io.github.rwml.api.ProgressListener
import io.github.rwml.api.game.GameBoot
import io.github.rwml.api.game.StageReport
import io.github.rwml.api.ui.GameUi
import io.github.rwml.api.ui.UiFramework
import io.github.rwml.api.ui.UiHost
import io.github.rwml.api.ui.UiSurface
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.config.ConfigModule
import io.github.rwpp.config.ProductTranslations
import io.github.rwpp.android.AndroidModule
import io.github.rwpp.game.units.comp.CompModule
import io.github.rwpp.api.game.Game
import io.github.rwpp.widget.isLoading
import io.github.rwpp.widget.loadingMessage
import io.github.rwpp.android.OffscreenSurfaceView
import kotlinx.coroutines.runBlocking
import org.koin.plugin.module.dsl.modules
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import io.github.rwpp.api.logging.logger

/** Android services that need the host handle after the framework entry point returns. */
internal object AndroidUiRuntime {
    private val lock = Any()

    @Volatile
    private var host: UiHost? = null

    private var launcherExecutor: java.util.concurrent.Executor? = null
    private var gameSurfaceFactory: (() -> UiSurface)? = null
    private var suspendLauncherUi: (() -> Unit)? = null
    private var restoreLauncherUi: (() -> Unit)? = null
    private var gameSurface: UiSurface? = null
    private var openingGame = false

    fun install(
        host: UiHost,
        launcherSurface: UiSurface,
        suspendLauncher: () -> Unit,
        restoreLauncher: () -> Unit,
        createGameSurface: () -> UiSurface
    ) {
        this.host = host
        launcherExecutor = launcherSurface.uiExecutor()
        suspendLauncherUi = suspendLauncher
        restoreLauncherUi = restoreLauncher
        gameSurfaceFactory = createGameSurface
    }

    fun clear(host: UiHost) {
        val toClose = synchronized(lock) {
            if (this.host !== host) return
            this.host = null
            launcherExecutor = null
            gameSurfaceFactory = null
            suspendLauncherUi = null
            restoreLauncherUi = null
            openingGame = false
            gameSurface.also { gameSurface = null }
        }
        toClose?.close()
    }

    fun openGameScreen() {
        val launcher = launcherExecutor ?: error("RWPP Android UI has not been started")
        launcher.execute {
            val currentHost = host ?: return@execute
            val surface: UiSurface
            synchronized(lock) {
                val current = gameSurface
                if (openingGame) {
                    return@execute
                }
                surface = current?.takeUnless { it.isClosed } ?: checkNotNull(gameSurfaceFactory).invoke()
                    .also { gameSurface = it }
                openingGame = true
            }
            try {
                suspendLauncherUi?.invoke()
                currentHost.openScreen("game", surface)
            } catch (failure: Throwable) {
                gameSurfaceClosed(currentHost, surface, restore = true)
                throw failure
            }
        }
    }

    fun closeGameScreen() {
        val current = synchronized(lock) { gameSurface }
        current?.close()
    }

    fun gameSurfaceClosed(host: UiHost, surface: UiSurface, restore: Boolean) {
        val restoreUi = synchronized(lock) {
            if (this.host !== host || gameSurface !== surface) return
            gameSurface = null
            openingGame = false
            restoreLauncherUi
        }
        if (restore) restoreUi?.invoke()
    }

    fun canResume(): Boolean = runCatching {
        host?.services()?.find(GameUi::class.java)?.snapshot()?.canResume == true
    }.getOrDefault(false)

    fun mapName(displayName: String): String = runCatching {
        host?.services()?.find(GameUi::class.java)?.mapName(displayName)
    }.getOrNull()?.takeIf(String::isNotBlank) ?: displayName
}

/** Android UI entry point. The host owns the Activity and the game engine lifecycle. */
class RwppUiFramework : UiFramework {
    private val started = AtomicBoolean(false)

    override fun start(host: UiHost) {
        check(started.compareAndSet(false, true)) { "RWPP Android UI has already been started" }
        val activity = host.platform() as? ComponentActivity
            ?: throw IllegalArgumentException("RWPP Android UI requires a ComponentActivity host")
        initializeKoin(activity)
        appKoin.get<ConfigIO>().readAllConfig()
        ProductTranslations.install()
        val surface = host.createSurface()

        surface.uiExecutor().execute {
            if (surface.isClosed || activity.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                surface.close()
                return@execute
            }

            isLoading = true
            loadingMessage = "Preparing game…"
            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { MainComposeContent(safeInsets = true) }
            }
            val restoreKeys = installVolumeKeyMapping(activity)
            AndroidUiRuntime.install(
                host,
                surface,
                suspendLauncher = {
                    if (!surface.isClosed) {
                        composeView.disposeComposition()
                        composeView.visibility = View.GONE
                    }
                },
                restoreLauncher = {
                    surface.uiExecutor().execute {
                        if (!surface.isClosed) {
                            composeView.setContent { MainComposeContent(safeInsets = true) }
                            composeView.visibility = View.VISIBLE
                        }
                    }
                },
                createGameSurface = { createGameSurface(host, surface) }
            )
            val closeHandle = surface.onClose {
                restoreKeys()
                composeView.disposeComposition()
                AndroidUiRuntime.clear(host)
            }
            try {
                surface.attach(composeView)
                startGameBoot(host, surface)
            } catch (failure: Throwable) {
                closeHandle.close()
                restoreKeys()
                composeView.disposeComposition()
                AndroidUiRuntime.clear(host)
                surface.close()
                throw failure
            }
        }
    }

    private fun createGameSurface(host: UiHost, launcherSurface: UiSurface): UiSurface {
        val surface = host.createGameSurface()
        val emptyCleanup: () -> Unit = {}
        val contentCleanup = AtomicReference(emptyCleanup)
        surface.onClose {
            contentCleanup.getAndSet(emptyCleanup).invoke()
            AndroidUiRuntime.gameSurfaceClosed(host, surface, restore = !launcherSurface.isClosed)
        }
        surface.attachFactory(UiContentFactory { nativeActivity ->
            val gameActivity = nativeActivity as? Activity
                ?: throw IllegalArgumentException("Android game surfaces require an Activity owner")
            val container = FrameLayout(gameActivity)
            val rangeLayer = OffscreenSurfaceView(gameActivity)
            container.addView(rangeLayer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val composeView = ComposeView(gameActivity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { MainComposeContent(showBackground = false, safeInsets = true) }
            }
            container.addView(composeView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val restoreKeys = installVolumeKeyMapping(gameActivity)
            val cleaned = AtomicBoolean()
            val cleanup: () -> Unit = {
                if (cleaned.compareAndSet(false, true)) {
                    restoreKeys()
                    composeView.disposeComposition()
                }
            }
            contentCleanup.set(cleanup)
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    if (!surface.isClosed) {
                        cleanup()
                        contentCleanup.compareAndSet(cleanup, emptyCleanup)
                    }
                }
            })
            container
        })
        return surface
    }

    private fun startGameBoot(host: UiHost, surface: UiSurface) {
        Thread({
            try {
                val report = host.gameBoot().start(ProgressListener { _, message ->
                    surface.uiExecutor().execute {
                        if (!surface.isClosed) loadingMessage = message
                    }
                })
                verifyBoot(report)

                // AppContext.init resolves game-backed singletons, so it must follow RWML's DEX preparation and engine stage.
                appKoin.get<AppContext>().init()
                runBlocking {
                    appKoin.get<Game>().load { message ->
                        surface.uiExecutor().execute {
                            if (!surface.isClosed) loadingMessage = message
                        }
                    }
                }
                surface.uiExecutor().execute {
                    if (!surface.isClosed) {
                        loadingMessage = ""
                        isLoading = false
                    }
                }
                logger().info("RWPP Android GameBoot completed: ${report.ran()}")
            } catch (failure: Throwable) {
                logger().error("RWPP Android game startup failed", failure)
                surface.uiExecutor().execute {
                    if (!surface.isClosed) {
                        loadingMessage = "Game startup failed: ${failure.message ?: failure::class.simpleName}"
                    }
                }
            }
        }, "rwpp-android-game-boot").apply {
            isDaemon = true
            start()
        }
    }

    private fun verifyBoot(report: StageReport) {
        check(report.contains(GameBoot.ENGINE)) {
            "The RWML game engine stage did not run; report=$report"
        }
        check(report.isClean) {
            "RWML GameBoot had failed stage(s): ${report.failed().entries.joinToString { "${it.key}: ${it.value}" }}"
        }
    }

    private fun initializeKoin(activity: ComponentActivity) {
        appKoin.declare(activity, secondaryTypes = listOf(Context::class, Activity::class))
        ApiRuntime.configureKoin {
            modules(io.github.rwpp.client.api.clientServices)
            modules(ConfigModule::class, AndroidModule::class, CompModule::class, UIModule::class)
        }
    }

    private fun installVolumeKeyMapping(activity: Activity): () -> Unit {
        val window = activity.window
        val original = window.callback ?: return {}
        val closed = AtomicBoolean(false)
        var volumeUpDownTime = 0L
        var volumeDownDownTime = 0L
        lateinit var wrapped: Window.Callback

        val handler = InvocationHandler { _, method, args ->
            if (method.name == "dispatchKeyEvent" && args?.firstOrNull() is KeyEvent) {
                val event = args[0] as KeyEvent
                if (runCatching { appKoin.get<Settings>().enableVolumeKeyMapping }.getOrDefault(false)) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            when (event.action) {
                                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                                    volumeUpDownTime = event.downTime
                                    dispatchSyntheticKey(original, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, volumeUpDownTime)
                                }
                                KeyEvent.ACTION_UP -> {
                                    dispatchSyntheticKey(original, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, volumeUpDownTime)
                                    volumeUpDownTime = 0L
                                }
                            }
                            return@InvocationHandler true
                        }
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            when (event.action) {
                                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                                    volumeDownDownTime = event.downTime
                                    dispatchSyntheticKey(original, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, volumeDownDownTime)
                                }
                                KeyEvent.ACTION_UP -> {
                                    dispatchSyntheticKey(original, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S, volumeDownDownTime)
                                    volumeDownDownTime = 0L
                                }
                            }
                            return@InvocationHandler true
                        }
                    }
                }
            }

            try {
                method.invoke(original, *(args ?: emptyArray()))
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw failure.targetException
            }
        }
        wrapped = Proxy.newProxyInstance(
            Window.Callback::class.java.classLoader,
            arrayOf(Window.Callback::class.java),
            handler
        ) as Window.Callback
        window.callback = wrapped

        return {
            if (closed.compareAndSet(false, true) && window.callback === wrapped) {
                window.callback = original
            }
        }
    }

    private fun dispatchSyntheticKey(callback: Window.Callback, action: Int, keyCode: Int, downTime: Long) {
        val now = SystemClock.uptimeMillis()
        callback.dispatchKeyEvent(KeyEvent(downTime.takeIf { it != 0L } ?: now, now, action, keyCode, 0))
    }
}

@Composable
internal actual fun PlatformBackgroundLayer(path: String) {
    val bitmap = remember(path) {
        path.takeIf(String::isNotBlank)?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
