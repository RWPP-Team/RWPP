package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import io.github.rwpp.config.HudComponentData
import io.github.rwpp.game.minimap.minimapFogColor
import io.github.rwpp.graphics.readHudTexturePixels
import io.github.rwpp.hud.HudComponentState
import io.github.rwpp.hud.hudViewportScale
import io.github.rwpp.logic.hotkeys.hotkeyActionLabel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudResponsiveTest {
    @Test
    fun relativeCoordinatesRoundTripAndOldTomlStillLoads() {
        val old = net.peanuuutz.tomlkt.Toml.decodeFromString(
            HudComponentData.serializer(),
            "id = \"chat\"\nxDp = 40.0\nyDp = 20.0\nscale = 1.0\n"
        )
        assertNull(old.relativeX)
        assertNull(old.relativeY)
        old.relativeX = 0.75f
        old.relativeY = 1f
        val encoded = net.peanuuutz.tomlkt.Toml.encodeToString(HudComponentData.serializer(), old)
        val restored = net.peanuuutz.tomlkt.Toml.decodeFromString(HudComponentData.serializer(), encoded)
        assertEquals(old, restored)
    }

    @Test
    fun hudDensityTracksAeroDesignSize() {
        assertEquals(0.75f, hudViewportScale(640f, 360f))
        assertEquals(1f, hudViewportScale(1280f, 720f))
        assertEquals(1f, hudViewportScale(2560f, 1440f))
        assertEquals(1f, hudViewportScale(1600f, 720f))
    }

    @Test
    fun savedCornerAnchorSurvivesWindowResizeWithoutChangingUserScale() {
        val data = HudComponentData("minimap", 0f, 0f, 1.1f, relativeX = 1f, relativeY = 1f)
        val state = HudComponentState(data).apply { width = 200f; height = 160f }
        state.updateViewport(1280f, 720f)
        assertEquals(1060f, state.x)
        assertEquals(544f, state.y)
        state.updateViewport(1920f, 1080f)
        assertEquals(1700f, state.x)
        assertEquals(904f, state.y)
        state.syncToData()
        assertEquals(1f, data.relativeX)
        assertEquals(1f, data.relativeY)
        assertEquals(1.1f, data.scale)
    }

    @Test
    fun legacyPositionMigratesOnceAndDragPersistsAsFraction() {
        val data = HudComponentData("chat", 400f, 250f)
        val state = HudComponentState(data).apply { width = 200f; height = 100f }
        state.updateViewport(1280f, 720f, legacyWidth = 1000f, legacyHeight = 600f)
        assertEquals(0.5f, data.relativeX)
        assertEquals(0.5f, data.relativeY)
        assertEquals(540f, state.x)
        assertEquals(310f, state.y)
        state.x = 1080f
        state.syncToData()
        state.updateViewport(1600f, 900f)
        assertEquals(1400f, state.x)
        assertEquals(400f, state.y)
    }

    @Test
    fun runtimeConsoleDragClampsToViewportAndPersistsRelativePosition() {
        val data = HudComponentData("hud_console_vertical", 0f, 0f, relativeX = 1f, relativeY = 0f)
        val state = HudComponentState(data).apply { width = 240f; height = 720f }
        state.updateViewport(1280f, 720f)

        state.moveBy(-200f, 80f, 1280f, 720f)
        state.syncToData()

        assertEquals(840f, state.x)
        assertEquals(0f, state.y)
        assertEquals(840f / 1040f, data.relativeX!!)
        assertEquals(0f, data.relativeY!!)

        state.updateViewport(640f, 720f)
        assertEquals(data.relativeX!! * 400f, state.x)
        state.moveBy(-1000f, 0f, 640f, 720f)
        assertEquals(0f, state.x)
    }

    @Test
    fun runtimeConsoleMoveZoneStaysOutboardAndRemainsReachableAtLeftEdge() {
        val state = HudComponentState(
            HudComponentData("hud_console_vertical", 0f, 0f, relativeX = 1f, relativeY = 0f)
        ).apply { width = 240f; height = 720f }
        state.updateViewport(1280f, 720f)
        assertEquals(Rect(1028f, 12f, 1040f, 60f), state.moveZoneBounds(1280f))

        state.x = 0f
        assertEquals(Rect(240f, 12f, 252f, 60f), state.moveZoneBounds(1280f))
    }

    @Test
    fun minimapProjectionUsesLetterboxForBothInputAndDrawing() {
        val projection = MinimapProjection(2000f, 1000f, 200f, 200f)
        assertEquals(Offset(100f, 100f), projection.worldToMap(1000f, 500f))
        assertEquals(Offset(1000f, 500f), projection.mapToWorld(Offset(100f, 100f)))
        assertNull(projection.mapToWorld(Offset(100f, 25f)))
        assertEquals(Offset.Zero, projection.mapToWorld(Offset(0f, 50f)))
        assertEquals(Offset(2000f, 1000f), projection.mapToWorld(Offset(200f, 150f)))
    }

    @Test
    fun minimapNeverPaintsUnexploredTerrainAndDimsExploredFog() {
        val original = 0xff80a040.toInt()
        assertEquals(original, minimapFogColor(original, 0))
        assertEquals(original, minimapFogColor(original, 4))
        assertEquals(0xff334019.toInt(), minimapFogColor(original, 5))
        assertEquals(0xff000000.toInt(), minimapFogColor(original, 10))
    }

    @Test
    fun thumbnailRestoresDroppedPixelsBeforeReadingCroppedSprite() {
        var restored = false
        val texture = object : BitmapOrTexture() {
            override fun ensurePixelData() {
                restored = true
            }

            override fun getPixel(x: Int, y: Int): Int {
                check(restored) { "GPU upload discarded the CPU buffer" }
                return x + y * 10
            }
        }.apply { width = 8; height = 8 }
        assertContentEquals(
            intArrayOf(21, 23, 41, 43),
            readHudTexturePixels(texture, 1, 2, 4, 4, 2, 2)
        )
    }

    @Test
    fun nativeAndCustomHotkeyActionsHaveChineseLabels() {
        val chineseLabels = mapOf(
            "rwpp.hotkeys.action.cameraUp" to "向上移动视角",
            "rwpp.hotkeys.action.selectWholeArmy" to "选取全部作战单位",
            "rwpp.hotkeys.action.createGroup" to "创建编队",
            "rwpp.hotkeys.action.upgrade" to "升级",
        )
        assertEquals("向上移动视角", hotkeyActionLabel("Camera Up") { key, _ -> chineseLabels.getValue(key) })
        assertEquals(
            "选取全部作战单位",
            hotkeyActionLabel("Select Whole Army") { key, _ -> chineseLabels.getValue(key) })
        assertEquals(
            "创建编队 3",
            hotkeyActionLabel("create group 3") { key, number -> "${chineseLabels.getValue(key)} $number" })
        assertEquals("升级", hotkeyActionLabel("Action - Upgrade") { key, _ -> chineseLabels.getValue(key) })
        assertEquals("Camera Up", hotkeyActionLabel("Camera Up") { key, _ ->
            assertEquals("rwpp.hotkeys.action.cameraUp", key)
            "Camera Up"
        })
        assertEquals(
            "Extension Action",
            hotkeyActionLabel("Extension Action") { _, _ -> error("Unexpected translation key") })
    }
}
