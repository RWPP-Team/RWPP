@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.IntSize
import io.github.rwpp.config.Settings
import io.github.rwpp.screen.Screen
import io.github.rwpp.screen.ScreenNavigator
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Surface
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WallpaperVisibilityTest : DesktopComposeGraphicsTest() {
    private class MenuScreen : Screen {
        @Composable
        override fun Content() = Unit
    }

    @Test
    fun publishingTheNavigatorAndEnteringGameplayRecomposesTheWallpaperCondition() {
        val app = koinApplication { modules(module { single { Settings() } }) }
        // The API keeps its container setter internal; the test only needs a Koin instance for
        // the lazy injections declared on GameScreen and UI.
        Class.forName("io.github.rwpp.api.GlobalsKt").declaredMethods
            .first { it.name.startsWith("setAppKoin") }
            .apply { isAccessible = true }
            .invoke(null, app.koin)
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(320, 240))
        val surface = Surface.makeRasterN32Premul(320, 240)
        var gameplayVisible = false
        var frameTime = 0L
        try {
            scene.setContent { gameplayVisible = rememberNavigatorHoldsGameScreen() }
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertFalse(gameplayVisible, "the wallpaper stays visible before the navigator is published")

            val navigator = ScreenNavigator(
                MenuScreen(),
                app.koin.createScope("wallpaper-test", named("wallpaper-test"))
            )
            UI.publishNavigator(navigator)
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertFalse(gameplayVisible, "the menu keeps the wallpaper")

            navigator.push(gameScreenMarker())
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertTrue(gameplayVisible, "a GameScreen must hide the wallpaper without a click")

            navigator.pop()
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertFalse(gameplayVisible, "returning to the menu restores the wallpaper")
        } finally {
            UI.publishNavigator(null)
            scene.close()
            recomposer.close()
            while (true) (tasks.poll() ?: break).run()
            surface.close()
            app.close()
        }
    }

    /** GameScreen reads native i18n in its constructor; the visibility check only uses its type. */
    private fun gameScreenMarker(): Screen {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val instance = unsafeType.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        return unsafeType.getMethod("allocateInstance", Class::class.java)
            .invoke(instance, GameScreen::class.java) as Screen
    }

    private fun drawFrame(
        scene: ComposeScene,
        recomposer: FrameRecomposer,
        tasks: ConcurrentLinkedQueue<Runnable>,
        surface: Surface,
        previousFrameTime: Long,
    ): Long {
        while (true) (tasks.poll() ?: break).run()
        val frameTime = previousFrameTime + 16_666_667L
        recomposer.performFrame(frameTime)
        while (true) (tasks.poll() ?: break).run()
        scene.measureAndLayout()
        scene.draw(surface.canvas.asComposeCanvas())
        return frameTime
    }
}
