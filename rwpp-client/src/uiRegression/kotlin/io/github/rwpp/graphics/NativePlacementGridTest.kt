package io.github.rwpp.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativePlacementGridTest {
    private fun tile(x: Float) = NativePlacementTile(x, 0f, x + 20f, 20f, 20f, 20f)

    @Test
    fun oneFrameKeepsEverySquareOfAPlacementDrag() {
        NativePlacementGrid.beginFrame()
        NativePlacementGrid.record(tile(0f))
        NativePlacementGrid.record(tile(20f))
        NativePlacementGrid.beginFrame()
        assertEquals(2, NativePlacementGrid.snapshot().size)
    }

    @Test
    fun aNewFrameDiscardsSquaresFromThePreviousPointerPosition() {
        NativePlacementGrid.beginFrame()
        NativePlacementGrid.record(tile(0f))
        NativePlacementGrid.record(tile(20f))
        NativePlacementGrid.beginFrame()
        NativePlacementGrid.record(tile(40f))
        NativePlacementGrid.beginFrame()
        val moved = NativePlacementGrid.snapshot()
        assertEquals(1, moved.size, "the old placement squares must not survive a move")
        assertEquals(40f, moved.single().left)
    }

    @Test
    fun placementSquaresChainInBuildOrderInsteadOfRadiatingFromTheBuilder() {
        val source = androidx.compose.ui.geometry.Offset(0f, 0f)
        val cells = listOf(tile(20f), tile(40f), tile(60f))
        val chain = placementChain(source, cells)
        assertEquals(3, chain.size)
        assertEquals(source, chain[0].first)
        assertEquals(30f, chain[0].second.x)
        assertEquals(chain[0].second, chain[1].first)
        assertEquals(chain[1].second, chain[2].first)
    }

    @Test
    fun anEmptyFrameClearsThePreviousSquares() {
        NativePlacementGrid.beginFrame()
        NativePlacementGrid.record(tile(0f))
        NativePlacementGrid.beginFrame()
        NativePlacementGrid.beginFrame()
        assertTrue(NativePlacementGrid.snapshot().isEmpty())
    }
}
