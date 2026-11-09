package io.github.rwpp.game.building

import kotlin.test.*

class BuildingPlanningControllerTest {
    @Test
    fun independentSessionKeepsEveryCapturedTypeAndOffsetWhileMoving() {
        val input = mutableListOf(
            BuildingPlanningRelativeItem("factory", 1, -40f, 0f),
            BuildingPlanningRelativeItem("turret", 2, 40f, 60f),
            BuildingPlanningRelativeItem("extractor", 1, 80f, -60f),
        )
        val session = BlueprintPlacementSession(input, 100f, 200f)
        input.clear()
        repeat(20) { session.moveTo(it * 20f, it * 40f) }
        val placed = session.placements()
        assertEquals(listOf("factory", "turret", "extractor"), placed.map { it.value })
        assertEquals(listOf(1, 2, 1), placed.map { it.techLevel })
        assertEquals(80f, placed[1].x - placed[0].x)
        assertEquals(60f, placed[1].y - placed[0].y)
        session.moveTo(Float.NaN, 1f)
        assertEquals(placed, session.placements())
        assertEquals(3, session.items.size)
    }

    @Test
    fun blueprintHitBoundsIncludeBuildingsAndTheSpaceBetweenThem() {
        val session = BlueprintPlacementSession(
            listOf(
                BuildingPlanningRelativeItem("a", 1, -40f, -20f, 12f),
                BuildingPlanningRelativeItem("b", 1, 40f, 20f, 12f),
            ), 100f, 200f
        )
        assertTrue(session.contains(60f, 180f))
        assertTrue(session.contains(100f, 200f))
        assertFalse(session.contains(200f, 200f))
        assertTrue(session.contains(160f, 200f, padding = 12f))
        session.moveTo(200f, 300f)
        assertFalse(session.contains(60f, 180f))
        assertTrue(session.contains(200f, 300f))
    }

    @Test
    fun gridSnapKeepsReferenceFootprintAndLayoutOffsetsAligned() {
        assertEquals(110f, snapBlueprintOrigin(112f, 40f, 10f, 20f))
        assertEquals(-10f, snapBlueprintOrigin(-12f, 40f, 10f, 20f))
        assertEquals(112f, snapBlueprintOrigin(112f, 40f, 10f, 0f))
    }

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

    @Test
    fun touchSecondFingerNeverStartsTheDesktopPanGesture() {
        // Android reports the second pointer with state 2, which is the secondary mouse button
        // on desktop. Only the desktop mouse may therefore enter the mouse branches.
        assertEquals(
            BuildingPlanningInputRoute.TOUCH_BOX,
            buildingPlanningInputRoute(desktop = false, planningArmed = true)
        )
        assertEquals(
            BuildingPlanningInputRoute.DESKTOP_MOUSE,
            buildingPlanningInputRoute(desktop = true, planningArmed = true)
        )
        assertEquals(
            BuildingPlanningInputRoute.NATIVE,
            buildingPlanningInputRoute(desktop = false, planningArmed = false)
        )
        assertEquals(
            BuildingPlanningInputRoute.NATIVE,
            buildingPlanningInputRoute(desktop = true, planningArmed = false)
        )
    }

    @Test
    fun releasedAndroidTouchDoesNotRetainItsLastPointerCount() {
        assertEquals(0, buildingPlanningTouchPointerCount(1, down = false))
        assertEquals(0, buildingPlanningTouchPointerCount(2, down = false))
        assertEquals(2, buildingPlanningTouchPointerCount(2, down = true))
        assertFalse(buildingPlanningHasTouchBox(buildingPlanningTouchPointerCount(2, false), 0f))
    }

    @Test
    fun touchBoxNeedsTwoPointersAndNoSettlingPinch() {
        assertFalse(buildingPlanningHasTouchBox(pointerCount = 1, pinchTimer = 0f))
        assertFalse(buildingPlanningHasTouchBox(pointerCount = 0, pinchTimer = 0f))
        assertTrue(buildingPlanningHasTouchBox(pointerCount = 2, pinchTimer = 0f))
        assertFalse(buildingPlanningHasTouchBox(pointerCount = 3, pinchTimer = 0f))
        assertFalse(buildingPlanningHasTouchBox(pointerCount = 2, pinchTimer = 20f))
    }
}
