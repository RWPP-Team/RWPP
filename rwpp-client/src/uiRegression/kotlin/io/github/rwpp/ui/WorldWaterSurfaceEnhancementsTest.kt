package io.github.rwpp.ui

import io.github.rwpp.graphics.*

import kotlin.test.Test
import kotlin.test.assertEquals

class WorldWaterSurfaceEnhancementsTest {
    @Test
    fun nativeTerrainSemanticsExcludeBridgesAndCliffLava() {
        assertEquals(
            WorldSurfaceMaterial.WATER, worldSurfaceMaterial(
                WorldSurfaceTileTraits(
                    water = true, waterBridge = false, lava = false, cliff = false
                )
            )
        )
        assertEquals(
            null, worldSurfaceMaterial(
                WorldSurfaceTileTraits(
                    water = true, waterBridge = true, lava = false, cliff = false
                )
            )
        )
        assertEquals(
            WorldSurfaceMaterial.LAVA, worldSurfaceMaterial(
                WorldSurfaceTileTraits(
                    water = false, waterBridge = false, lava = true, cliff = false
                )
            )
        )
        assertEquals(
            null, worldSurfaceMaterial(
                WorldSurfaceTileTraits(
                    water = false, waterBridge = false, lava = true, cliff = true
                )
            )
        )
        assertEquals(
            null, worldSurfaceMaterial(
                WorldSurfaceTileTraits(
                    water = false, waterBridge = false, lava = false, cliff = false
                )
            )
        )
    }

    @Test
    fun worldTileProjectionUsesCameraAndZoom() {
        assertEquals(
            WorldSurfaceTileRect(16f, 16f, 48f, 48f),
            worldSurfaceScreenRect(2, 3, 16, 16, cameraX = 24f, cameraY = 40f, zoom = 2f)
        )
        val left = worldSurfaceScreenRect(2, 3, 20, 20, 18f, 25f, 0.65f)
        val right = worldSurfaceScreenRect(3, 3, 20, 20, 18f, 25f, 0.65f)
        assertEquals(left.right, right.left)
    }
}
