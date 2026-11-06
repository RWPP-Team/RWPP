/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.rwpp.App
import io.github.rwpp.widget.MenuLoadingView
import io.github.rwpp.widget.isLoading
import io.github.rwpp.widget.loadingMessage
import io.github.rwpp.widget.loadingOverlayKeepsContent
import kotlinx.coroutines.CompletableDeferred

private object LoadingOverlayPresentation {
    private var nextGeneration = 0L
    private var activeGeneration = 0L
    private val waiters = mutableMapOf<Long, CompletableDeferred<Unit>>()

    @Synchronized
    fun begin(): Long {
        waiters.remove(activeGeneration)?.cancel()
        activeGeneration = ++nextGeneration
        waiters[activeGeneration] = CompletableDeferred()
        return activeGeneration
    }

    suspend fun awaitDrawn(generation: Long) {
        val waiter = synchronized(this) { waiters[generation] } ?: return
        waiter.await()
    }

    @Synchronized
    fun markDrawn(generation: Long) {
        if (generation == activeGeneration) waiters[generation]?.complete(Unit)
    }

    @Synchronized
    fun finish(generation: Long) {
        val waiter = waiters.remove(generation)
        if (activeGeneration == generation) activeGeneration = 0
        waiter?.cancel()
    }
}

private var loadingOverlayGeneration by mutableStateOf(0L)

/** Begins a generation-tagged draw acknowledgement for the active loading overlay. */
internal fun beginLoadingOverlayPresentation(): Long = LoadingOverlayPresentation.begin().also {
    loadingOverlayGeneration = it
}

/** Suspends until the matching loading overlay has completed a Compose draw pass. */
internal suspend fun awaitLoadingOverlayDrawn(generation: Long) =
    LoadingOverlayPresentation.awaitDrawn(generation)

internal fun finishLoadingOverlayPresentation(generation: Long) {
    LoadingOverlayPresentation.finish(generation)
    if (loadingOverlayGeneration == generation) loadingOverlayGeneration = 0L
}

private fun markLoadingOverlayDrawn(generation: Long) = LoadingOverlayPresentation.markDrawn(generation)

/** State shared by desktop and Android UI roots. */
var isSendingTeamChat by mutableStateOf(false)
var isShowSendMessageDialog by mutableStateOf(false)

fun showSendMessageDialog() {
    isShowSendMessageDialog = true
}

/** Platform implementations draw the configured background image when it can be read. */
@Composable
internal expect fun PlatformBackgroundLayer(path: String)

/**
 * The wallpaper is composited above the native game surface. This layer reads the navigator
 * state itself, so a missed parent recomposition cannot leave the image over a running game.
 */
@Composable
private fun WallpaperLayer(path: String) {
    if (rememberNavigatorHoldsGameScreen()) return
    PlatformBackgroundLayer(path)
}

/** Main RWPP screen and the in-game overlays, shared by both host surfaces. */
@Composable
fun MainComposeContent(showBackground: Boolean = true, safeInsets: Boolean = false) {
    val foregroundInsets =
        if (safeInsets) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        } else {
            Modifier
        }
    Box(modifier = Modifier.fillMaxSize()) {
        // The background layer is part of the window, which is composited above the native
        // game surface; drawing it during gameplay would hide the running game.
        if (showBackground && !rememberNavigatorHoldsGameScreen()) {
            WallpaperLayer(UI.backgroundImagePath)
        }
        Box(modifier = Modifier.fillMaxSize().then(foregroundInsets), contentAlignment = Alignment.Center) {
            LoadingAwareContent(
                isLoading = isLoading,
                keepContentWhileLoading = loadingOverlayKeepsContent,
                loadingContent = { MenuLoadingView(loadingMessage) },
                content = { App() },
            )
        }
    }


}

/** Initial loading keeps the app uncomposed; blocking in-app reloads retain it beneath an input shield. */
@Composable
internal fun LoadingAwareContent(
    isLoading: Boolean,
    keepContentWhileLoading: Boolean,
    loadingContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (isLoading && !keepContentWhileLoading) {
        loadingContent()
        return
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { isLoading && keepContentWhileLoading },
    ) {
        content()
        if (isLoading && keepContentWhileLoading) {
            val generation = loadingOverlayGeneration
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .pointerInput(generation) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        if (generation != 0L) markLoadingOverlayDrawn(generation)
                    },
                contentAlignment = Alignment.Center,
            ) {
                loadingContent()
            }
        }
    }
}

/** Desktop boot surface only; the running game owns its separate offscreen Compose scene. */
@Composable
fun LoadingComposeContent() {
    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(UI.backgroundImagePath)
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MenuLoadingView(loadingMessage)
            }
        }
    }
}
