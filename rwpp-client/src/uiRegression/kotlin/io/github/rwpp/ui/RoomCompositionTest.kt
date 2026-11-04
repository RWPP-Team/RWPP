@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Surface
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomCompositionTest : DesktopComposeGraphicsTest() {
    @Test
    fun textFieldCanAttachMeasureAndDetachAcrossRepeatedScreenChanges() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(800, 600))
        val surface = Surface.makeRasterN32Premul(800, 600)
        var screen by mutableIntStateOf(0)
        var attached = 0
        var disposed = 0
        try {
            scene.setContent {
                key(screen) {
                    DisposableEffect(Unit) {
                        attached++
                        onDispose { disposed++ }
                    }
                    MaterialTheme {
                        Column {
                            Text("Room $screen")
                            // Material3's SAM style ABI must match Foundation's CustomStyleScope.
                            TextField("player", {})
                        }
                    }
                }
            }
            repeat(6) { index ->
                screen = index
                while (true) (tasks.poll() ?: break).run()
                recomposer.performFrame((index + 1) * 16_666_667L)
                scene.measureAndLayout()
                scene.draw(surface.canvas.asComposeCanvas())
            }
            assertEquals(6, attached)
            assertEquals(5, disposed)
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
        assertEquals(attached, disposed)
    }
}
