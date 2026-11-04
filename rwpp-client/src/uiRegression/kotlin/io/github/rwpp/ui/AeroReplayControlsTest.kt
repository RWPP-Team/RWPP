package io.github.rwpp.ui

import io.github.rwpp.game.hud.isAeroLiveObserver
import io.github.rwpp.game.replay.*
import io.github.rwpp.graphics.aeroRangeOwnership
import io.github.rwpp.graphics.aeroReplayOrderAccess
import io.github.rwpp.graphics.aeroReplayWaypointDestinationVisible
import io.github.rwpp.mixin.replay.AeroObserverCommandAccessMixin
import kotlin.test.*

class AeroReplayControlsTest {
    @Test
    fun observerCommandGuardRemainsRegisteredOnTheInterfaceEngineMixin() {
        val mixin = AeroObserverCommandAccessMixin::class.java
        val annotation = mixin.getAnnotation(kim.der.asm.api.annotation.AsmMixin::class.java)
        assertNotNull(annotation)
        assertTrue(
            annotation.toString().contains("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine"),
            "The relocated mixin must still target InterfaceEngine",
        )
        assertTrue(
            mixin.declaredMethods.any { method ->
                method.name == "readOnly" && method.getAnnotation(kim.der.asm.api.annotation.AsmInject::class.java) != null
            },
            "The command guard must remain a discovered native mixin after leaving the UI package",
        )
    }

    @Test
    fun switchingPerspectiveClearsNativeFogCachesAndRestoresOnlyTheSameReplayMap() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engineField = com.corrodinggames.rts.gameFramework.GameEngine::class.java
            .getDeclaredField("al").apply { isAccessible = true }
        val previousEngine = engineField.get(null)
        val previousBuffers = com.corrodinggames.rts.game.map.Map.mapLayerBuffer
        try {
            val engine = unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java)
                    as com.corrodinggames.rts.game.RWGameEngine
            engineField.set(null, engine)
            com.corrodinggames.rts.gameFramework.GameEngine::class.java.getField("gameUpdateLock")
                .apply { isAccessible = true }
                .set(engine, Any())
            engine.interfaceEngine = com.corrodinggames.rts.gameFramework.gui.InterfaceEngine()
            engine.minimap = com.corrodinggames.rts.gameFramework.gui.MinimapHandler()
            val map = unsafe.allocateInstance(com.corrodinggames.rts.game.map.Map::class.java)
                    as com.corrodinggames.rts.game.map.Map
            map.mapWidthInTiles = 2; map.mapHeightInTiles = 2
            map.smoothFogCache1 = Array(2) { ByteArray(2) { 127 } }
            map.smoothFogCache2 = Array(2) { ByteArray(2) { 127 } }
            engine.map = map
            engine.playerTeam = com.corrodinggames.rts.game.Team.blankPlayer
            val buffers = com.corrodinggames.rts.game.map.MapLayerBuffer()
            val cell = unsafe.allocateInstance(com.corrodinggames.rts.game.map.MapLayerBufferBufferImage::class.java)
                    as com.corrodinggames.rts.game.map.MapLayerBufferBufferImage
            buffers.javaClass.getDeclaredField("bufferGrid").apply { isAccessible = true }
                .set(buffers, arrayOf(arrayOf(cell)))
            buffers.javaClass.getDeclaredField("layerBufferCount").apply { isAccessible = true }.setInt(buffers, 1)
            com.corrodinggames.rts.game.map.Map.mapLayerBuffer = buffers
            engine.networkEngine =
                unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.network.NetworkEngine::class.java)
                        as com.corrodinggames.rts.gameFramework.network.NetworkEngine
            engine.replayEngine = unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.ReplayEngine::class.java)
                    as com.corrodinggames.rts.gameFramework.ReplayEngine
            val observer =
                unsafe.allocateInstance(com.corrodinggames.rts.game.Player::class.java) as com.corrodinggames.rts.game.Player
            observer.allyTeam = -3
            engine.networkEngine.networked = true
            engine.networkEngine.localTeam = observer
            assertTrue(isAeroLiveObserver(engine))
            engine.networkEngine.networked = false
            engine.networkEngine.singlePlayerServer = true
            assertTrue(isAeroLiveObserver(engine), "Native skirmish disables networking once its map loads")
            engine.networkEngine.singlePlayerServer = false
            assertFalse(isAeroLiveObserver(engine), "A stale local team outside a session grants no observer access")
            engine.networkEngine.singlePlayerServer = true
            val alliedA = aeroRangeOwnership(1, 4, androidx.compose.ui.graphics.Color.Red, true)
            val alliedB = aeroRangeOwnership(7, 4, androidx.compose.ui.graphics.Color.Blue, true)
            assertEquals(
                alliedA,
                alliedB,
                "players at different spawn slots share their allied team's range tint/group"
            )
            val otherTeam = aeroRangeOwnership(2, 5, androidx.compose.ui.graphics.Color.Red, true)
            assertTrue(otherTeam != alliedA)
            assertEquals(
                androidx.compose.ui.graphics.Color.Blue,
                aeroRangeOwnership(7, 4, androidx.compose.ui.graphics.Color.Blue, false).color
            )
            val session = AeroReplayPerspectiveSession()
            session.begin(engine)
            setAeroReplayPerspective(engine, com.corrodinggames.rts.game.Team.grayTeam)
            assertSame(
                com.corrodinggames.rts.game.Team.blankPlayer, engine.playerTeam,
                "Changing live perspective must leave simulation identity intact"
            )
            assertSame(com.corrodinggames.rts.game.Team.grayTeam, aeroPresentationTeam(engine))
            assertFailsWith<IllegalStateException> {
                withAeroPresentationTeam(engine) {
                    assertSame(com.corrodinggames.rts.game.Team.grayTeam, engine.playerTeam)
                    error("render failure")
                }
            }
            assertSame(com.corrodinggames.rts.game.Team.blankPlayer, engine.playerTeam)
            assertTrue(
                isAeroLiveObserver(engine),
                "global/player camera must not replace the network observer identity"
            )
            assertSame(observer, engine.networkEngine.localTeam)
            assertTrue(engine.minimap.fogRedrawPending)
            assertTrue(cell.dirty, "Cached terrain must redraw for the new player's fog")
            assertTrue(map.smoothFogCache1.all { row -> row.all { it == 0.toByte() } })
            assertTrue(map.smoothFogCache2.all { row -> row.all { it == 0.toByte() } })
            assertEquals(0, engine.interfaceEngine.selectedUnits.size)
            val restore = session.finish(engine)!!
            restoreAeroReplayPerspective(restore)
            assertSame(com.corrodinggames.rts.game.Team.blankPlayer, engine.playerTeam)
            assertFalse(session.active)
            session.begin(engine)
            engine.map = unsafe.allocateInstance(com.corrodinggames.rts.game.map.Map::class.java)
                    as com.corrodinggames.rts.game.map.Map
            assertNull(session.finish(engine), "A new game must not receive the previous replay's perspective")
        } finally {
            com.corrodinggames.rts.game.map.Map.mapLayerBuffer = previousBuffers
            engineField.set(null, previousEngine)
        }
    }

    @Test
    fun replayOrderVisibilityDoesNotTrustNativeReplayControlPermissions() {
        assertFalse(
            aeroReplayOrderAccess(
                replaying = true,
                showAllOrders = false,
                sameTeam = false,
                sharedControl = false,
                nativeCanControl = true
            ),
            "Native replay control permissions are broader than the selected viewer's perspective"
        )
        assertTrue(aeroReplayOrderAccess(true, false, sameTeam = true, sharedControl = false, nativeCanControl = false))
        assertTrue(aeroReplayOrderAccess(true, false, sameTeam = false, sharedControl = true, nativeCanControl = false))
        assertTrue(aeroReplayOrderAccess(true, true, sameTeam = false, sharedControl = false, nativeCanControl = false))
        assertTrue(
            aeroReplayOrderAccess(
                false,
                false,
                sameTeam = false,
                sharedControl = false,
                nativeCanControl = true
            )
        )
        assertFalse(
            aeroReplayOrderAccess(
                false,
                true,
                sameTeam = false,
                sharedControl = false,
                nativeCanControl = false
            )
        )
    }

    @Test
    fun enemyReplayDestinationsRespectCurrentFogWithoutHidingFriendlyOrders() {
        val fog = Array(4) { ByteArray(4) }
        fog[1][2] = 5

        assertFalse(
            aeroReplayWaypointDestinationVisible(
                fogEnabled = true, fogGrid = fog,
                tileWidth = 10, tileHeight = 10, mapWidth = 4, mapHeight = 4,
                x = 15f, y = 25f, targetUnitVisible = true, requireCurrentVision = true
            ),
            "An enemy order destination outside current vision must be truncated"
        )
        assertTrue(
            aeroReplayWaypointDestinationVisible(
                fogEnabled = true, fogGrid = fog,
                tileWidth = 10, tileHeight = 10, mapWidth = 4, mapHeight = 4,
                x = 15f, y = 25f, targetUnitVisible = true, requireCurrentVision = false
            ),
            "The viewer knows destinations of orders issued by their own or shared-control units"
        )
        assertFalse(
            aeroReplayWaypointDestinationVisible(
                fogEnabled = true, fogGrid = fog,
                tileWidth = 10, tileHeight = 10, mapWidth = 4, mapHeight = 4,
                x = 15f, y = 25f, targetUnitVisible = false, requireCurrentVision = false
            ),
            "A hidden target must not be revealed even when the issuing unit is friendly"
        )

        fog[1][2] = 4
        assertTrue(
            aeroReplayWaypointDestinationVisible(
                fogEnabled = true, fogGrid = fog,
                tileWidth = 10, tileHeight = 10, mapWidth = 4, mapHeight = 4,
                x = 15f, y = 25f, targetUnitVisible = true, requireCurrentVision = true
            ),
            "A visible destination remains available"
        )
    }

    @Test
    fun globalObserverAndInvalidFogCoordinatesAreHandledSafely() {
        assertTrue(
            aeroReplayWaypointDestinationVisible(
                fogEnabled = true, fogGrid = null,
                tileWidth = 10, tileHeight = 10, mapWidth = 4, mapHeight = 4,
                x = 15f, y = 25f, targetUnitVisible = true, requireCurrentVision = true
            ),
            "The global observer has no team fog grid"
        )
        val fog = Array(4) { ByteArray(4) }
        assertFalse(
            aeroReplayWaypointDestinationVisible(
                fogEnabled = true, fogGrid = fog,
                tileWidth = 10, tileHeight = 10, mapWidth = 4, mapHeight = 4,
                x = Float.NaN, y = 25f, targetUnitVisible = true, requireCurrentVision = true
            )
        )
        assertFalse(
            aeroReplayWaypointDestinationVisible(
                fogEnabled = true, fogGrid = fog,
                tileWidth = 10, tileHeight = 10, mapWidth = 4, mapHeight = 4,
                x = 45f, y = 25f, targetUnitVisible = true, requireCurrentVision = true
            ),
            "Out-of-map enemy destinations are never shown"
        )
    }
}
