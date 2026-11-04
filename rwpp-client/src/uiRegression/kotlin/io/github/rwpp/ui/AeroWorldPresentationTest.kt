package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwpp.graphics.*
import kotlin.test.*

class AeroWorldPresentationTest {
    @Test
    fun animatedUnitFramesReuseUnchangedItemsWithoutMutatingPublishedFrames() {
        val cache = ImmutableFrameListCache<Any>()
        val first = Any()
        val second = Any()
        val completeSource = listOf(first, second)
        cache.publishStable(completeSource)
        val initial = cache.update(completeSource) { it }
        assertSame(initial, cache.update(completeSource) { it }, "No data change should reuse the published list")

        val replacement = Any()
        val updated = cache.update(completeSource) { if (it === first) replacement else it }
        assertNotSame(initial, updated)
        assertSame(first, initial[0], "A prior frame remains intact after publication")
        assertSame(replacement, updated[0])
        assertSame(second, updated[1], "Unchanged items are shared across immutable frames")

        val filtered = cache.update(completeSource) { if (it === first) replacement else null }
        assertEquals(listOf(replacement), filtered, "Hidden source items leave the published frame")
        assertEquals(listOf(replacement, second), updated, "Later updates must not mutate frames in flight")
        val restored = cache.update(completeSource) { if (it === first) replacement else it }
        assertEquals(
            listOf(replacement, second), restored,
            "Units visible again are restored from the complete tracking source and last visual state"
        )
    }

    @Test
    fun cameraTracksActualDrawFramesWhileHudSamplingIsStalled() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engine = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
        engine.zoomScale = 1f; engine.screenWidth = 800f; engine.screenHeight = 600f
        val original = WorldVisuals(
            camera = Offset.Zero,
            orders = listOf(AeroOrder(Offset(100f, 100f), Offset(200f, 100f), false))
        )
        val presentation = AeroWorldPresentation()
        presentation.publish(original, emptyList(), emptyList())
        val registration = NativeWorldPresentation.register(presentation)
        try {
            engine.cameraRenderX = 100f; engine.cameraRenderY = 50f
            NativeWorldPresentation.capture(engine)
            assertEquals(Offset(100f, 50f), presentation.frame.value!!.camera)
            // No second OnGameUpdate/publish: native camera moves during a slow HUD frame/drag.
            engine.cameraRenderX = 180f; engine.cameraRenderY = 70f; engine.zoomScale = 1.5f
            NativeWorldPresentation.capture(engine)
            assertEquals(Offset(180f, 70f), presentation.frame.value!!.camera)
            assertEquals(1.5f, presentation.frame.value!!.zoom)
            assertEquals(800f, presentation.frame.value!!.width)
            assertEquals(Offset.Zero, original.camera, "Published HUD metadata is not mutated")
            presentation.publish(WorldVisuals(), emptyList(), emptyList())
            NativeWorldPresentation.capture(engine)
            assertNull(presentation.frame.value, "Disabled/empty effects must not keep invalidating native frames")
        } finally {
            registration.close()
        }
    }
}
