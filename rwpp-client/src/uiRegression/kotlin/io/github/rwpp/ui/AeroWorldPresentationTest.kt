package io.github.rwpp.ui

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwpp.graphics.AeroWorldPresentation
import io.github.rwpp.graphics.ImmutableFrameListCache
import io.github.rwpp.graphics.NativeWorldPresentation
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
    fun obsoleteOrUninitializedEnginesCannotPresentStaleWorldVisuals() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val obsolete = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
        val presentation = AeroWorldPresentation()
        val registration = NativeWorldPresentation.register(presentation)
        try {
            // No map, viewer or initialized engine lock: capture must reject this instance
            // before touching its native objects, even while a disposed HUD callback survives.
            NativeWorldPresentation.capture(obsolete)
            assertNull(presentation.frame.value)
        } finally {
            registration.close()
        }
    }
}
