package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.units.Waypoint
import kotlin.test.*

class MoveQueueProgressTest {
    private fun move(x: Float, y: Float = 100f) = Waypoint().apply { setMoveTo(x, y) }

    @Test
    fun nativeAdvancementConsumesIntermediatesWithoutRequiringAnotherCommand() {
        val route = listOf(move(50f), move(90f), move(130f), move(200f))
        val progress = MoveQueueProgress(route, 3)
        assertEquals(0, progress.observe(route))
        assertEquals(1, progress.observe(route.drop(1)))
        assertEquals(2, progress.observe(route.drop(2)))
        assertEquals(3, progress.observe(route.drop(3)))
        assertTrue(progress.cursor >= progress.protectedCount, "The next original user target can now be planned")
        assertEquals(4, progress.observe(emptyList()))
    }

    @Test
    fun shiftAppendPreservesTheRunningPrefixAndIsNotAReplacement() {
        val route = listOf(move(50f), move(90f), move(130f))
        val progress = MoveQueueProgress(route, 2)
        assertEquals(0, progress.observe(route + move(200f)))
        assertEquals(1, progress.observe(route.drop(1) + move(200f)))
        assertEquals(2, progress.observe(route.drop(2) + move(200f)))
    }

    @Test
    fun replacementStopAndRemovedSuffixCannotResumeAnOldSegment() {
        val route = listOf(move(50f), move(90f), move(130f), move(200f))
        val progress = MoveQueueProgress(route, 3)
        assertNull(progress.observe(listOf(move(300f))))
        assertNull(progress.observe(listOf(move(50f), move(90f), move(130f))), "Removed user suffix changes the queue")
        assertEquals(1, progress.observe(route.drop(1)))
        assertNull(progress.observe(route), "A consumed prefix cannot be resurrected")
    }
}
