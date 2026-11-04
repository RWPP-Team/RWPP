package io.github.rwpp.ui

import com.corrodinggames.rts.gameFramework.CommonUtils
import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerNotificationType
import io.github.rwpp.game.minimap.*
import kotlin.test.*

class AeroMinimapIndicatorsTest {
    @Test
    fun nativeOrderSignalsStayBoundedAndExpireAcrossClockResets() {
        AeroMinimapOrders.clear()
        try {
            AeroMinimapOrders.add(Float.NaN, 0f, 1000L)
            repeat(10) { AeroMinimapOrders.add(it.toFloat(), 2f, 1000L) }
            val bounded = AeroMinimapOrders.snapshot(1001L)
            assertEquals(8, bounded.size)
            assertEquals(2f, bounded.first().x)
            assertTrue(AeroMinimapOrders.snapshot(1701L).isEmpty())

            AeroMinimapOrders.add(4f, 5f, 2000L)
            assertTrue(AeroMinimapOrders.snapshot(1000L).isEmpty())
        } finally {
            AeroMinimapOrders.clear()
        }
    }

    @Test
    fun unitMarkersUseRelationshipColorsByDefaultAndKeepAnOriginalColorOption() {
        val originalColor = 0xff48c8e0.toInt()

        assertEquals(MINIMAP_PLAYER_UNIT_COLOR, minimapUnitColor(originalColor, true, false, false))
        assertEquals(MINIMAP_ALLIED_UNIT_COLOR, minimapUnitColor(originalColor, false, false, false))
        assertEquals(MINIMAP_ENEMY_UNIT_COLOR, minimapUnitColor(originalColor, false, true, false))
        assertEquals(originalColor, minimapUnitColor(originalColor, false, true, true))
    }

    @Test
    fun minimapUnitAndResourceMarkersUseSmallFixedScreenRadii() {
        assertEquals(0.9f, minimapUnitRadiusDp(isBuilding = false))
        assertEquals(1.25f, minimapUnitRadiusDp(isBuilding = true))
        assertEquals(0.8f, MINIMAP_RESOURCE_RADIUS_DP)
        assertEquals(0xffffffff.toInt(), MINIMAP_RESOURCE_COLOR)
    }

    @Test
    fun resourceMarkersIgnoreFogWhileUnitMarkersStillRequirePlayerVisibility() {
        val resources = minimapResourceDots(listOf(2 to 3, 7 to 8), tileWidth = 20, tileHeight = 20)
        assertEquals(2, resources.size)
        assertEquals(50f, resources.first().x)
        assertEquals(70f, resources.first().y)
        assertTrue(minimapUnitMarkerVisible(false, true, false, true, true, false))
        assertFalse(minimapUnitMarkerVisible(false, true, false, false, true, false))
    }

    @Test
    fun terrainPixelSnapshotsStayStableAcrossFogUpdatesAndMapReplacement() {
        val terrain = intArrayOf(0xff80a040.toInt(), 0xff182838.toInt(), 0xffc08040.toInt(), 0xff385078.toInt())
        val noFog = updateMinimapFogPixels(terrain, intArrayOf(), 2, 2, 2, null)
        assertContentEquals(terrain, noFog)

        val unchanged = updateMinimapFogPixels(terrain, noFog, 2, 2, 2, null)
        assertSame(noFog, unchanged, "Unchanged map/fog output should reuse the published immutable pixels")

        val fogged = updateMinimapFogPixels(
            terrain,
            noFog,
            2,
            2,
            2,
            arrayOf(byteArrayOf(0, 5), byteArrayOf(10, 4)),
        )
        assertNotSame(noFog, fogged, "A fog update must publish a fresh pixel array")
        assertContentEquals(
            intArrayOf(terrain[0], 0xff000000.toInt(), 0xff4c3319.toInt(), terrain[3]),
            fogged,
        )
        assertContentEquals(terrain, noFog, "Published pixels from the prior frame must remain unchanged")

        val replacementMap = updateMinimapFogPixels(terrain, intArrayOf(), 2, 2, 2, null)
        assertNotSame(noFog, replacementMap, "A map replacement starts a new published snapshot")
        assertContentEquals(noFog, replacementMap)
        assertContentEquals(terrain, noFog)
    }

    @Test
    fun notificationFadeUsesNativeHorizontalThenVerticalDeadzoneAndRenderDelta() {
        val horizontal = advanceNativeMinimapNotification(1f, 0.8f, 15f)
        assertEquals(0.4f, horizontal.scaleX, 0.00001f)
        assertEquals(0.8f, horizontal.scaleY)

        val vertical = advanceNativeMinimapNotification(0.2f, 0.8f, 10f)
        assertEquals(0f, vertical.scaleX)
        assertEquals(CommonUtils.applyDeadzone(0.8f, 0.005f * 10f), vertical.scaleY)
    }

    @Test
    fun borderFlashKeepsNativeColorPriorityAndIncludesUnitHitAlerts() {
        assertNull(minimapBorderFlashColor(emptyList(), false, 90f))
        assertEquals(
            android.graphics.Color.argb(255, (CommonUtils.sin(90f) * 230f).toInt(), 0, 0),
            minimapBorderFlashColor(listOf(MinimapHandlerNotificationType.unit), false, 90f),
            "Unit damage uses the requested red border pulse"
        )
        assertEquals(
            android.graphics.Color.argb(
                255,
                12,
                (CommonUtils.sin(90f) * 220f).toInt(),
                (CommonUtils.sin(90f) * 220f).toInt()
            ),
            minimapBorderFlashColor(listOf(MinimapHandlerNotificationType.message), false, 90f)
        )
        assertEquals(
            android.graphics.Color.argb(255, (CommonUtils.sin(90f) * 230f).toInt(), 0, 0),
            minimapBorderFlashColor(
                listOf(MinimapHandlerNotificationType.message, MinimapHandlerNotificationType.base), false, 90f
            )
        )
        assertEquals(
            android.graphics.Color.argb(255, 0, (CommonUtils.sin(90f) * 230f).toInt(), 0),
            minimapBorderFlashColor(listOf(MinimapHandlerNotificationType.unit), true, 90f)
        )
    }

    @Test
    fun alertPaletteAndScaleFloorsMatchNativeTypes() {
        assertEquals(
            android.graphics.Color.argb(200, 12, 227, 219),
            minimapAlertColor(MinimapHandlerNotificationType.message, 23f)
        )
        val pulseAlpha = (50f + CommonUtils.sin(23f) * 190f).toInt()
        assertEquals(
            android.graphics.Color.argb(pulseAlpha, pulseAlpha, 0, 0),
            minimapAlertColor(MinimapHandlerNotificationType.unit, 23f)
        )
        assertEquals(0.03f, minimapAlertDrawScale(MinimapHandlerNotificationType.unit, 0f))
        assertEquals(0.05f, minimapAlertDrawScale(MinimapHandlerNotificationType.base, 0f))
        assertEquals(1f, minimapAlertDrawScale(MinimapHandlerNotificationType.message, 1.2f))
    }

    @Test
    fun nukeMarkerPulsesAlphaFromTheNativeAngleWithoutChangingTeamColor() {
        val teamColor = 0xff48c8e0.toInt()
        val low = minimapNukeMarkerColor(teamColor, 0f)
        val high = minimapNukeMarkerColor(teamColor, 90f)
        assertEquals(0x66, low ushr 24)
        assertEquals(0xff, high ushr 24)
        assertEquals(teamColor and 0x00ffffff, low and 0x00ffffff)
        assertEquals(teamColor and 0x00ffffff, high and 0x00ffffff)
    }

    @Test
    fun animationAnglesAdvanceOnceUsingNativeDeltaAndWrapRule() {
        assertEquals(5f, advanceNativeMinimapAngle(179f, 1f, 6f))
        assertEquals(10f, advanceNativeMinimapAngle(0f, 2f, 5f))
    }
}
