package io.github.rwpp.game.building

import kotlin.test.*

class BuildingPlanningControllerTest {
    @Test
    fun dragRectNormalizesCoordinatesAndIncludesBuildingRadiusAtEdges() {
        val rect = assertNotNull(buildingPlanningNormalizedRect(20f, 30f, 10f, 5f))
        assertEquals(BuildingPlanningScreenRect(10f, 5f, 20f, 30f), rect)
        assertTrue(buildingPlanningRectTouchesPoint(rect, 8f, 12f, radius = 2f))
        assertFalse(buildingPlanningRectTouchesPoint(rect, 7.9f, 12f, radius = 2f))
        assertNull(buildingPlanningNormalizedRect(Float.NaN, 0f, 1f, 1f))
    }

    @Test
    fun blueprintPlacementKeepsTechAndRelativeLayoutAndHonorsBoundedNativeBatchSize() {
        val entries = listOf(
            BuildingPlanningRelativeItem("power", techLevel = 2, offsetX = 0f, offsetY = 0f, radius = 16f),
            BuildingPlanningRelativeItem("turret", techLevel = 1, offsetX = 48f, offsetY = 24f, radius = 20f),
            BuildingPlanningRelativeItem("ignored", techLevel = 1, offsetX = Float.NaN, offsetY = 0f),
        )

        val placed = buildingPlanningWorldPlacements(entries, originX = 100f, originY = 200f, limit = 2)

        assertEquals(2, placed.size)
        assertEquals("power", placed[0].value)
        assertEquals(2, placed[0].techLevel)
        assertEquals(100f, placed[0].x)
        assertEquals(200f, placed[0].y)
        assertEquals("turret", placed[1].value)
        assertEquals(1, placed[1].techLevel)
        assertEquals(148f, placed[1].x)
        assertEquals(224f, placed[1].y)
        assertTrue(placed.all { it.radius > 0f })
    }

    @Test
    fun invalidOriginsOrEmptyLimitsCannotProduceNativePlacements() {
        val item = BuildingPlanningRelativeItem("turret", 1, 0f, 0f)
        assertTrue(buildingPlanningWorldPlacements(listOf(item), Float.NaN, 0f).isEmpty())
        assertTrue(buildingPlanningWorldPlacements(listOf(item), 0f, 0f, limit = 0).isEmpty())
    }
}
