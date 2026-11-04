package io.github.rwpp.ui

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.event.events.ChatMessageEvent
import io.github.rwpp.api.event.events.ChatOrigin
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.config.RoomPreset
import io.github.rwpp.config.RoomPresetPlayer
import io.github.rwpp.config.Settings
import io.github.rwpp.game.replay.ReplayFiles
import io.github.rwpp.game.replay.parseReplay
import io.github.rwpp.game.room.PresetTeamMode
import io.github.rwpp.game.room.findPresetMap
import io.github.rwpp.mixin.graphics.*
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import net.peanuuutz.tomlkt.Toml
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.ByteArrayInputStream
import kotlin.test.*
import io.github.rwpp.api.game.chat.ChatMute as ApiChatMute

class LegacyFeaturesTest {
    @Test
    fun theNativeFpsSettingControlsBothHudPerformanceValues() {
        assertEquals("", hudPerformanceLabel(false, 60, 42))
        assertEquals("60 FPS  ·  42 ms  ·  ", hudPerformanceLabel(true, 60, 42))
    }

    @Test
    fun presetsRoundTripThroughTheExistingSettingsFormatAndMatchFullMapPaths() {
        val preset = RoomPreset(
            "Huge map", "mods/maps/A/arena.tmx", "customMap", 12, true, 2, 1,
            2, 3, 1.5f, true, true, false, true, 1.25f, null,
            listOf(RoomPresetPlayer(1, 2, null, 4), RoomPresetPlayer(3, 0, 5, null)),
            recyclingDisabled = true,
            recyclingDisabledUnits = listOf("commandCenter", "builder"),
        )
        val toml = Toml { ignoreUnknownKeys = true }
        val restored = toml.decodeFromString(
            Settings.serializer(),
            toml.encodeToString(Settings.serializer(), Settings(roomPresets = listOf(preset)))
        )
        assertEquals(listOf(preset), restored.roomPresets)
        assertTrue(toml.decodeFromString(Settings.serializer(), "hudAnimations = true\n").roomPresets.isEmpty())
        fun map(path: String) =
            GameMap(1, "arena", GameSetupMapType.customMap, { ByteArrayInputStream(byteArrayOf()) }, enginePath = path)

        val wanted = map("mods/maps/A/arena.tmx")
        assertSame(wanted, findPresetMap(preset, listOf(map("mods/maps/B/arena.tmx"), wanted)))
        assertNull(findPresetMap(preset, listOf(map("mods/maps/B/arena.tmx"))))
    }

    @Test
    fun muteUsesNativeSenderSlotAndRoomIdentityWithoutBlockingRelay() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engineField = com.corrodinggames.rts.gameFramework.GameEngine::class.java.getDeclaredField("al")
            .apply { isAccessible = true }
        val previousEngine = engineField.get(null)
        engineField.set(null, unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java))
        fun player(id: Int) =
            (unsafe.allocateInstance(Player::class.java) as Player).apply { teamId = id; name = "same" }

        val first = player(1);
        val second = player(2);
        val replacement = player(1)
        val roster: List<Team> = listOf(first, second)
        fun event(slot: Int, origin: ChatOrigin = ChatOrigin.RECEIVE) =
            ChatMessageEvent("same", "hello", null, slot, origin)
        try {
            val previousRevision = ChatMute.revision.value
            ChatMute.setMuted(first, true)
            assertTrue(ChatMute.revision.value > previousRevision, "The Compose adapter must expose API state changes")
            assertSame(ApiChatMute.revision, ChatMute.revision, "Room and in-game surfaces observe the same API flow")
            assertTrue(ChatMute.filters(event(1), roster))
            assertFalse(ChatMute.filters(event(2), roster))
            assertFalse(ChatMute.filters(event(-1), roster), "Ambiguous names do not identify a sender")
            assertFalse(ChatMute.filters(event(1, ChatOrigin.REMOTE_RELAY), roster))
            assertFalse(
                ChatMute.filters(event(1), listOf(replacement, second)),
                "A reused slot is not the muted player"
            )
            ChatMute.setMuted(first, false)
            assertFalse(ChatMute.filters(event(1), roster))
            ChatMute.setMuted(first, true)
            val mutedRevision = ChatMute.revision.value
            ChatMute.clear()
            assertTrue(ChatMute.revision.value > mutedRevision, "Room lifecycle clear must notify both chat surfaces")
            assertFalse(ChatMute.isMuted(first))
        } finally {
            ChatMute.clear(); engineField.set(null, previousEngine)
        }
    }

    @Test
    fun roomPresetAppliesOverridesToExistingAndLaterPlayersBySpawnSlot() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engineField = com.corrodinggames.rts.gameFramework.GameEngine::class.java.getDeclaredField("al")
            .apply { isAccessible = true }
        val previousEngine = engineField.get(null)
        engineField.set(null, unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java))
        try {
            fun player(slot: Int) =
                (unsafe.allocateInstance(Player::class.java) as Player).apply { teamId = slot; allyTeam = 7 }

            val current = player(1);
            val later = player(2)
            val room = java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(GameRoom::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "isHost" -> true
                    "isHostServer" -> false
                    "getPlayers" -> listOf(current)
                    else -> error("Unexpected room call: ${method.name}")
                }
            } as GameRoom
            val mode = PresetTeamMode(listOf(RoomPresetPlayer(1, 3, 4, 5), RoomPresetPlayer(2, 0, null, 8)))
            mode.onInit(room)
            assertEquals(3, current.allyTeam); assertEquals(4, current.colorOverride); assertEquals(
                5,
                current.startingUnitsOverride
            )
            mode.onPlayerJoin(room, later)
            assertEquals(0, later.allyTeam); assertNull(later.colorOverride); assertEquals(
                8,
                later.startingUnitsOverride
            )
            assertEquals(3, mode.autoTeamAssign(room, 1, later))
            assertEquals(0, mode.autoTeamAssign(room, 99, later))
        } finally {
            engineField.set(null, previousEngine)
        }
    }

    @Test
    fun replayNamesCannotEscapeTheNativeReplayDirectory() {
        for (name in listOf("../secret", "..\\secret", ".", "..", "")) {
            assertFailsWith<IllegalArgumentException> { ReplayFiles.open(Replay(0, name)) }
        }
    }

    @Test
    fun replayCatalogParsesNativeMetadataAndMatchesMapsOutsideTheUiPackage() {
        val map = GameMap(
            1, "arena", GameSetupMapType.customMap,
            { ByteArrayInputStream(byteArrayOf()) }, enginePath = "mods/maps/A/arena.tmx"
        )
        val replay = Replay(7, "Arena [v1.15] (28 Oct 2026 12.30.45).replay")

        val parsed = parseReplay(replay, listOf(map))

        assertEquals(7, parsed.id)
        assertEquals(replay.displayName(), parsed.fileName)
        assertSame(map, parsed.map)
        assertEquals("Arena", parsed.mapName)
        assertEquals("v1.15", parsed.versionStr)
        assertEquals("28 Oct 2026 12.30.45", parsed.dateTimeStr)
    }

    @Test
    fun drawLimitRedirectsMatchTheActualGameMethods() {
        val targets = listOf(
            "com/corrodinggames/rts/game/units/Unit" to UnitDrawLimitMixin::class.java,
            "com/corrodinggames/rts/game/units/custom/CustomUnit" to CustomUnitDrawLimitMixin::class.java,
            "com/corrodinggames/rts/game/Projectile" to ProjectileDrawLimitMixin::class.java,
            "com/corrodinggames/rts/gameFramework/effects/Blueprint" to BlueprintDrawLimitMixin::class.java,
            "com/corrodinggames/rts/gameFramework/effects/EffectEngine" to EffectDrawLimitMixin::class.java,
        )
        for ((name, mixin) in targets) {
            AsmRegistry.clear()
            try {
                val original = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
                AsmRegistry.register(mixin)
                val node = ClassNode()
                ClassReader(AsmProcessor().transform(name, original, javaClass.classLoader)).accept(node, 0)
                assertTrue(node.methods.any { method ->
                    method.instructions.toArray().filterIsInstance<MethodInsnNode>()
                        .any { it.owner.contains("DrawLimitMixin") && it.name == "visible" }
                }, name)
            } finally {
                AsmRegistry.clear()
            }
        }
    }
}
