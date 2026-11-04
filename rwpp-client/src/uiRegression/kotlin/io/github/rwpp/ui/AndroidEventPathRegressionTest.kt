package io.github.rwpp.ui

import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.config.ServerType
import io.github.rwpp.api.net.Net
import io.github.rwpp.config.MultiplayerPreferences
import io.github.rwpp.game.minimap.minimapTapOrders
import io.github.rwpp.graphics.WorldVisualRefreshSchedule
import io.github.rwpp.logic.NetProductInitialization
import io.github.rwpp.mixin.graphics.AeroNativeZoomMixin
import io.github.rwpp.mixin.network.NativeUnitSearchPromptMixin
import io.github.rwpp.utils.isNativeUnitSearchPrompt
import io.github.rwpp.utils.nativePromptText
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import java.lang.reflect.Proxy
import java.util.*
import kotlin.test.*

class AndroidEventPathRegressionTest {
    @Test
    fun literalSearchPromptDoesNotRequireAnEngineTranslationKey() {
        val literal = "Search units by internal name or text title."
        assertTrue(isNativeUnitSearchPrompt(literal))
        assertFalse(isNativeUnitSearchPrompt("password"))
        assertFalse(isNativeUnitSearchPrompt(null))
        assertEquals(literal, nativePromptText(literal) { throw MissingResourceException("missing", "locale", it) })
        assertEquals("translated", nativePromptText("known.key") { "translated" })
        assertNull(nativePromptText(null) { error("must not look up null") })
    }

    @Test
    fun editorSearchPromptBridgeIsPackagedInTheJvmNativeCallPath() {
        val name = "com/corrodinggames/rts/gameFramework/network/NetworkEngine"
        val bytes = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(NativeUnitSearchPromptMixin::class.java)
            val transformed = AsmProcessor().transform(name, bytes, javaClass.classLoader)
            val node = org.objectweb.asm.tree.ClassNode()
            org.objectweb.asm.ClassReader(transformed).accept(node, 0)
            val prompt = node.methods.single { it.name == "showPasswordPrompt" }
            assertTrue(prompt.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().any {
                it.owner == "io/github/rwpp/mixin/network/NativeUnitSearchPromptMixin" && it.name == "search"
            })
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun lateNetworkInitializationRemovesOnlyTheLegacyQRoomListEntry() {
        val net = Proxy.newProxyInstance(Net::class.java.classLoader, arrayOf(Net::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "offline network fixture"
                else -> error("unexpected native network call ${method.name}")
            }
        } as Net
        val preferences = MultiplayerPreferences()
        preferences.allServerConfig.add(ServerConfig("localhost:5123", "custom", ServerType.Server))
        val legacyQRoomList = ServerConfig(
            ip = "",
            name = "Q Room List",
            type = ServerType.RoomList,
            useAsDefaultList = true,
            editable = false,
            customRoomListProvider = "qRoomListProvider",
            customRoomHostProtocol = "QN",
        )
        val userRoomListWithSameName = ServerConfig(
            ip = "rooms.example:5123",
            name = "Q Room List",
            type = ServerType.RoomList,
            customRoomListProvider = "myRoomListProvider",
            customRoomHostProtocol = "CUSTOM",
        )
        preferences.allServerConfig.add(legacyQRoomList)
        preferences.allServerConfig.add(userRoomListWithSameName)
        val initialization = NetProductInitialization(net, preferences)
        initialization.init()
        initialization.init()
        assertEquals(1, preferences.allServerConfig.count { it.name == "Official Room List" })
        assertFalse(preferences.allServerConfig.contains(legacyQRoomList))
        assertTrue(preferences.allServerConfig.contains(userRoomListWithSameName))
        assertEquals(1, preferences.allServerConfig.count { it.name == "Q Room List" })
        assertTrue(preferences.allServerConfig.any { it.name == "custom" })
    }

    @Test
    fun touchAndSecondaryCommandsDoNotReplaceDesktopPrimaryCameraControl() {
        assertTrue(minimapTapOrders(true, false, true))
        assertTrue(minimapTapOrders(false, true, true))
        assertFalse(minimapTapOrders(false, false, true))
        assertFalse(minimapTapOrders(true, false, false))
    }

    @Test
    fun nativeZoomArtworkHookMatchesActualGameBytecode() {
        val name = "com/corrodinggames/rts/gameFramework/gui/BattleInterface"
        val bytes = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(AeroNativeZoomMixin::class.java)
            assertFalse(bytes.contentEquals(AsmProcessor().transform(name, bytes, javaClass.classLoader)))
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun worldSamplingIsBoundedButRecoversAfterClockReset() {
        val schedule = WorldVisualRefreshSchedule()
        assertTrue(schedule.due(1_000_000_000L))
        assertFalse(schedule.due(1_016_666_667L))
        assertTrue(schedule.due(1_033_333_334L))
        assertTrue(schedule.due(1L))
    }

    @Test
    fun selectionChangesBypassTheHeavyWorldSampleInterval() {
        val schedule = WorldVisualRefreshSchedule()
        assertTrue(schedule.due(1_000_000_000L))
        assertFalse(schedule.due(1_008_333_333L))
        assertTrue(schedule.due(1_008_333_333L, force = true), "new selection must not wait for the 30Hz world scan")
        assertFalse(schedule.due(1_016_666_666L))
        assertTrue(schedule.due(1_041_666_667L))
    }

}
