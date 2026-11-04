package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.rwpp.game.hud.aeroCustomSecondaryIsShield
import io.github.rwpp.game.hud.aeroExperimentalHoverTankSecondaryIsShield
import io.github.rwpp.game.hud.aeroShieldVisible
import io.github.rwpp.graphics.nativeUnitHealthBarBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudUnitBarsTest {
    @Test
    fun nativeCollisionRadiusAndZoomDetermineLargeUnitBarGeometry() {
        val center = Offset(400f, 250f)
        // Unit.drawOverlay uses the collision radius (150), not the selection radius or a dp cap.
        assertEquals(
            Rect(250f, 404f, 550f, 408f),
            nativeUnitHealthBarBounds(center, 150f, 1f, 1f, 1f, false, false)
        )
        // Below 1x native resets the graphics zoom for bar width/thickness, while its offset
        // from the altitude-adjusted center remains in world space.
        assertEquals(
            Rect(250f, 327f, 550f, 331f),
            nativeUnitHealthBarBounds(center, 150f, 0.5f, 1f, 1f, false, false)
        )
        assertEquals(
            Rect(325f, 327f, 475f, 329f),
            nativeUnitHealthBarBounds(center, 150f, 0.5f, 1f, 1f, true, false)
        )
        assertEquals(
            Rect(100f, 558f, 700f, 560f),
            nativeUnitHealthBarBounds(center, 150f, 2f, 1f, 1f, false, true)
        )
    }

    @Test
    fun largeUnitBoundsMatchTheActualNativeOverlayAtDifferentZooms() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val singleton = com.corrodinggames.rts.gameFramework.GameEngine::class.java
            .getDeclaredField("al").apply { isAccessible = true }
        val previous = singleton.get(null)
        val engine = unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java)
                as com.corrodinggames.rts.game.RWGameEngine
        singleton.set(null, engine)
        try {
            engine.settings = unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.SettingsEngine::class.java)
                    as com.corrodinggames.rts.gameFramework.SettingsEngine
            engine.playerTeam = com.corrodinggames.rts.game.Team.blankPlayer
            val unit = unsafe.allocateInstance(com.corrodinggames.rts.game.units.land.Tank::class.java)
                    as com.corrodinggames.rts.game.units.Unit
            unit.team = engine.playerTeam
            unit.x = 400f; unit.y = 310f; unit.height = 60f
            unit.radius = 150f; unit.selectionRadius = 25f
            unit.hp = 50f; unit.maxHp = 100f; unit.buildProgress = 1f; unit.selected = true
            engine.cameraRenderX = 10f; engine.cameraRenderY = 20f
            for ((zoom, drawnScaledDown) in listOf(0.5f to false, 0.5f to true, 1f to false, 2f to false)) {
                engine.zoomScale = zoom; unit.drawnScaledDown = drawnScaledDown
                var scale = zoom
                val scales = java.util.ArrayDeque<Float>()
                val rectangles = mutableListOf<Rect>()
                val graphics = java.lang.reflect.Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(com.corrodinggames.rts.gameFramework.render.GraphicsEngine::class.java)
                ) { _, method, args ->
                    when (method.name) {
                        "save" -> {
                            scales.push(scale); null
                        }

                        "restore" -> {
                            scale = scales.pop(); null
                        }

                        "scale" -> {
                            scale *= args!![0] as Float; null
                        }

                        "drawRect" -> {
                            val rect = args!![0]
                            if (rect is android.graphics.RectF) rectangles += Rect(
                                rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale
                            )
                            null
                        }

                        else -> when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Float.TYPE -> 0f
                            else -> null
                        }
                    }
                } as com.corrodinggames.rts.gameFramework.render.GraphicsEngine
                engine.graphics = graphics
                unit.drawOverlay(0f, false)
                val center = Offset(
                    (unit.x - engine.cameraRenderX) * zoom,
                    (unit.y - unit.height - engine.cameraRenderY) * zoom
                )
                val expected = nativeUnitHealthBarBounds(center, unit.radius, zoom, 1f, 1f, drawnScaledDown, false)
                assertTrue(rectangles.size >= 2)
                assertEquals(expected, rectangles[1], "Native outlined bar at zoom=$zoom scaledDown=$drawnScaledDown")
            }
        } finally {
            singleton.set(null, previous)
        }
    }

    @Test
    fun shieldRowVisibilityDoesNotChangeWithSelectionOrShieldFill() {
        for ((selected, hovered) in listOf(false to false, false to true, true to false, true to true)) {
            assertTrue(aeroShieldVisible(true, selected, hovered, currentShield = 0f, maxShield = 100f))
            assertTrue(aeroShieldVisible(true, selected, hovered, currentShield = 100f, maxShield = 100f))
        }

        assertFalse(aeroShieldVisible(false, selected = true, hovered = true, currentShield = 50f, maxShield = 100f))
        assertFalse(aeroShieldVisible(true, selected = true, hovered = true, currentShield = 0f, maxShield = 0f))
    }

    @Test
    fun customUnitSecondaryKeepsEnergyPriorityAndDropsOnlyShieldBranch() {
        // Native CustomUnit.getSecondaryBarFill() chooses an enabled incomplete energy bar first.
        assertFalse(
            aeroCustomSecondaryIsShield(
                maxEnergy = 100f,
                currentEnergy = 20f,
                showEnergyBar = true,
                maxShield = 100f,
                shield = 20f,
                showShieldBar = true,
            ),
        )

        assertTrue(
            aeroCustomSecondaryIsShield(
                maxEnergy = 100f,
                currentEnergy = 20f,
                showEnergyBar = false,
                maxShield = 100f,
                shield = 20f,
                showShieldBar = true,
            ),
        )
        assertTrue(
            aeroCustomSecondaryIsShield(
                maxEnergy = 100f,
                currentEnergy = 100f,
                showEnergyBar = true,
                maxShield = 100f,
                shield = 0f,
                showShieldBar = true,
            ),
        )
        assertFalse(
            aeroCustomSecondaryIsShield(
                maxEnergy = 0f,
                currentEnergy = 0f,
                showEnergyBar = true,
                maxShield = 100f,
                shield = 0f,
                showShieldBar = false,
            ),
        )
    }

    @Test
    fun experimentalHoverTankSecondaryIsShieldOnlyWhileItsNativeShieldBranchRuns() {
        assertTrue(aeroExperimentalHoverTankSecondaryIsShield(maxShield = 5000f, shield = 0f))
        assertTrue(aeroExperimentalHoverTankSecondaryIsShield(maxShield = 5000f, shield = 4999f))
        assertFalse(aeroExperimentalHoverTankSecondaryIsShield(maxShield = 5000f, shield = 5000f))
        assertFalse(aeroExperimentalHoverTankSecondaryIsShield(maxShield = 0f, shield = 0f))
    }
}
