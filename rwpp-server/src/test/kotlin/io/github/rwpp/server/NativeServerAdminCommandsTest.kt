package io.github.rwpp.server

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.command.CommandSender
import io.github.rwpp.api.command.Commands
import io.github.rwpp.api.command.SERVER
import io.github.rwpp.api.game.map.GameMap
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeServerAdminCommandsTest {
    @Test
    fun mapsListsAndResolvesOnlyPathsSupportedByTheNativeServer() {
        val maps = listOf(
            map(0, "[p2]Lake", "maps/skirmish/[p2]Lake.tmx"),
            map(1, "[p4]Desert", "maps/skirmish/[p4]Desert.tmx"),
            map(2, "User map", "mods/maps/User map.tmx", GameSetupMapType.customMap),
            map(3, "Campaign", "saves/Campaign.rwsave", GameSetupMapType.savedGame),
            map(4, "External", "/SD/rusted_warfare_maps/External.tmx", GameSetupMapType.customMap),
        )

        val nativeMaps = nativeServerMaps(maps)
        assertEquals(4, nativeMaps.size)
        assertEquals("maps/skirmish/[p2]Lake.tmx", resolveNativeMapArgument("1", maps))
        assertEquals("maps/skirmish/[p4]Desert.tmx", resolveNativeMapArgument("Desert", maps))
        assertEquals("maps/skirmish/[p4]Desert.tmx", resolveNativeMapArgument("maps\\skirmish\\[p4]Desert.tmx", maps))
        assertEquals("mods/maps/User map.tmx", resolveNativeMapArgument("User map", maps))
        assertEquals("saves/Campaign.rwsave", resolveNativeMapArgument("Campaign", maps))
    }

    @Test
    fun mapSelectionRejectsAmbiguousNamesAndUnknownIndexes() {
        val maps = listOf(
            map(0, "Arena", "maps/skirmish/Arena.tmx"),
            map(1, "Arena", "mods/maps/Arena.tmx", GameSetupMapType.customMap),
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> { resolveNativeMapArgument("Arena", maps) }
        kotlin.test.assertFailsWith<IllegalStateException> { resolveNativeMapArgument("3", maps) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { resolveNativeMapArgument("../Arena", maps) }
    }

    @Test
    fun serverAdminCommandsAreServerVisibleAndGuardedByTheAdminPermission() {
        val commands = Commands()
        registerNativeRoomAdminCommands(commands, NativeHeadlessServer(), {})
        val names = commands.help(Sender(admin = true)).map { it.substringBefore(' ') }.toSet()

        assertTrue(
            setOf(
                "maps", "maxplayers", "addai", "kick", "ai", "credits", "startingunits", "income", "fog",
                "revealed", "revealedmap", "nukes", "sharedcontrol", "team", "move", "players", "language",
                "replay", "prompt",
            ).all(names::contains)
        )
        assertTrue("maxplayers" in commands.complete(listOf("max"), Sender(admin = true)))
        assertFalse("maxplayers" in commands.complete(listOf("max"), Sender(admin = false)))
        assertTrue("players" in commands.complete(listOf("pla"), Sender(admin = false)))
        val protectedActions = listOf(
            "maxplayers 20", "addai 1", "kick 1", "ai 1", "credits 10000", "startingunits 1",
            "income 2", "fog off", "revealed on", "revealedmap true", "nukes on", "sharedcontrol on",
            "team 1 1", "move 1 2", "language en", "replay status", "prompt test",
        )
        protectedActions.forEach { assertFalse(commands.execute(it, Sender(admin = false)), it) }
    }

    @Test
    fun nativeBooleanAndLanguageArgumentsAreStrictAndNormalized() {
        assertTrue(parseNativeBoolean("ON"))
        assertTrue(parseNativeBoolean("true"))
        assertFalse(parseNativeBoolean("off"))
        assertFalse(parseNativeBoolean("FALSE"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeBoolean("yes") }

        assertEquals(null, normalizeNativeLanguage("SYSTEM", setOf("en", "zh_cn")))
        assertEquals("zh_cn", normalizeNativeLanguage("zh-CN", setOf("en", "zh_cn")))
        kotlin.test.assertFailsWith<IllegalArgumentException> { normalizeNativeLanguage("bad/code", setOf("en")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { normalizeNativeLanguage("fr", setOf("en")) }
    }

    @Test
    fun nativeRoomSettingsUseNativeOptionIdsAndDynamicPresetChoices() {
        assertEquals(0, parseNativeStartingCredits("default"))
        assertEquals(0, parseNativeStartingCredits("4000"))
        assertEquals(1, parseNativeStartingCredits("0"))
        assertEquals(5, parseNativeStartingCredits("10000"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeStartingCredits("4001") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeStartingCredits("not-money") }

        val nativeAndModPresets = listOf(1, 2, 3, 4, 5, 9, 101)
        assertEquals(1, validateNativeStartingUnits(1, nativeAndModPresets))
        assertEquals(5, validateNativeStartingUnits(5, nativeAndModPresets))
        assertEquals(101, validateNativeStartingUnits(101, nativeAndModPresets))
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            validateNativeStartingUnits(6, nativeAndModPresets)
        }

        assertEquals(1f, validateNativeIncomeMultiplier("1"))
        assertEquals(2.5f, validateNativeIncomeMultiplier("2.5"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { validateNativeIncomeMultiplier("1.1") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { validateNativeIncomeMultiplier("NaN") }
    }

    @Test
    fun nativeRoomCommandArgumentsRejectMalformedValuesAndHonorNativeBoundaries() {
        assertEquals(MIN_NATIVE_PLAYERS, validateNativeMaxPlayers(MIN_NATIVE_PLAYERS, emptyList()))
        assertEquals(MAX_NATIVE_PLAYERS, validateNativeMaxPlayers(MAX_NATIVE_PLAYERS, listOf(99)))
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            validateNativeMaxPlayers(MIN_NATIVE_PLAYERS - 1, emptyList())
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            validateNativeMaxPlayers(MAX_NATIVE_PLAYERS + 1, emptyList())
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            validateNativeMaxPlayers(10, listOf(10))
        }

        assertEquals(1, parseNativeAddAiCount(emptyList()))
        assertEquals(100, parseNativeAddAiCount(listOf("100")))
        kotlin.test.assertFailsWith<NumberFormatException> { parseNativeAddAiCount(listOf("foo")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeAddAiCount(listOf("0")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeAddAiCount(listOf("101")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeAddAiCount(listOf("1", "2")) }

        assertEquals(-2 to null, parseNativeAiArguments(listOf("-2")))
        assertEquals(3 to 10, parseNativeAiArguments(listOf("3", "10")))
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeAiArguments(listOf("-3")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeAiArguments(listOf("4")) }
        kotlin.test.assertFailsWith<NumberFormatException> { parseNativeAiArguments(listOf("1", "slot")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { parseNativeAiArguments(listOf("1", "2", "3")) }

        assertEquals(-1, validateOneBasedNativeAllyGroup(-1))
        assertEquals(99, validateOneBasedNativeAllyGroup(99))
        kotlin.test.assertFailsWith<IllegalArgumentException> { validateOneBasedNativeAllyGroup(100) }
        assertEquals(-1, validateZeroBasedNativeAllyGroup(-1))
        assertEquals(98, validateZeroBasedNativeAllyGroup(98))
        kotlin.test.assertFailsWith<IllegalArgumentException> { validateZeroBasedNativeAllyGroup(99) }
    }

    @Test
    fun nativeReplayToggleStartsOnMatchAndStopsWhenDisabled() {
        var matchActive = false
        var recording = false
        var starts = 0
        var stops = 0
        val controller = NativeReplayController(
            matchActive = { matchActive },
            isRecording = { recording },
            startRecording = { starts++; recording = true },
            stopRecording = { stops++; recording = false },
        )

        assertEquals("Native replay recording is enabled for the next match.", controller.execute("on"))
        matchActive = true
        controller.onMatchStarted()
        assertEquals(1, starts)
        assertEquals("Native replay recording is enabled and active.", controller.execute("status"))
        assertEquals("Native replay recording is disabled.", controller.execute("off"))
        assertEquals(1, stops)
    }

    private fun map(
        id: Int,
        name: String,
        path: String,
        type: GameSetupMapType = GameSetupMapType.skirmishMap,
    ) = GameMap(id, name, type, { ByteArrayInputStream(ByteArray(0)) }, enginePath = path)

    private class Sender(private val admin: Boolean) : CommandSender {
        override fun reply(message: String) = Unit
        override fun hasPermission(permission: String): Boolean = admin || permission != SERVER_ADMIN_PERMISSION
        override fun hasVisibility(visibility: String): Boolean =
            visibility == io.github.rwpp.api.command.CommandVisibility.SERVER
    }
}
