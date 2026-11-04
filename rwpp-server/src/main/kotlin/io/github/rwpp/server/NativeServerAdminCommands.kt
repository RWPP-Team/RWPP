/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.ai.AI
import com.corrodinggames.rts.gameFramework.localization.LocaleEngine
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.command.CommandContext
import io.github.rwpp.api.command.CommandVisibility
import io.github.rwpp.api.command.Commands
import io.github.rwpp.api.command.SERVER
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.map.GameMap
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

internal fun registerNativeRoomAdminCommands(
    commands: Commands,
    server: NativeHeadlessServer,
    setConsolePrompt: (String) -> Unit,
) {
    commands.command("maps", "List maps available to the native server") {
        usage = "[page]"
        requireVisibility(CommandVisibility.SERVER)
        body {
            runAdminCommand {
                server.assertOwnerThread()
                val maps = nativeServerMaps(appKoin.get<Game>().getAllMaps())
                val page = when (args.size) {
                    0 -> 1
                    1 -> argument(0).toInt()
                    else -> error("Usage: maps [page]")
                }
                val pageCount = maxOf(1, (maps.size + MAPS_PER_PAGE - 1) / MAPS_PER_PAGE)
                require(page in 1..pageCount) { "Page must be between 1 and $pageCount." }
                val pageItems = maps.drop((page - 1) * MAPS_PER_PAGE).take(MAPS_PER_PAGE)
                pageItems.forEachIndexed { index, map -> reply("${(page - 1) * MAPS_PER_PAGE + index + 1}: ${map.displayName()} (${map.enginePath})") }
                if (pageItems.isEmpty()) reply("No maps are available.")
                "Maps page $page/$pageCount. Use map <index|path|name> to select one."
            }
        }
    }

    commands.command("maxplayers", "Set the native room player-slot limit") {
        aliases("maxplayer")
        usage = "<10..100>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("10", "20", "50", "100") }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val count = argument(0).toInt()
                validateNativeMaxPlayers(count, room.getPlayers().filterNot { it.isSpectator() }.map { it.teamId })
                val spectatorOutsideNativeArray = room.getPlayers().firstOrNull {
                    it.isSpectator() && it.teamId >= count + Team.extraTeamSlots
                }
                require(spectatorOutsideNativeArray == null) {
                    "Cannot shrink to $count slots while a spectator occupies a native team-array slot that would be removed."
                }
                room.maxPlayerCount = count
                server.engine.networkEngine.apply {
                    markPlayerListDirty()
                    sendUpdatePlayer(null)
                    sendServerInfoToAll()
                }
                "Maximum players set to $count."
            }
        }
    }

    commands.command("addai", "Add native AI teams") {
        usage = "[count]"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("1", "2", "4") }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                val count = parseNativeAddAiCount(args)
                val freeSlots = (0 until room.maxPlayerCount).count { Team.getTeam(it) == null }
                require(count <= freeSlots) { "Only $freeSlots native team slots are free." }
                room.addAI(count)
                "Added $count native AI${if (count == 1) "" else "s"}."
            }
        }
    }

    commands.command("kick", "Kick a player or AI by room slot") {
        usage = "<slot>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val slot = argument(0).toInt() - 1
                require(slot in 0 until room.maxPlayerCount) { "Slot must be between 1 and ${room.maxPlayerCount}." }
                val target = room.getPlayers().firstOrNull { it.teamId == slot }
                    ?: error("There is no player in slot ${slot + 1}.")
                require(target !== server.engine.networkEngine.localTeam) { "The server's local team cannot be kicked." }
                room.kickPlayer(target)
                "Kicked ${target.name ?: "slot ${slot + 1}"}."
            }
        }
    }

    commands.command("ai", "Set native AI difficulty (-2..3)") {
        usage = "<-2..3> [AI slot]"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { (-2..3).map(Int::toString) }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                val (difficulty, targetSlot) = parseNativeAiArguments(args)
                if (targetSlot == null) {
                    room.aiDifficulty = difficulty
                    "Default AI difficulty set to $difficulty."
                } else {
                    require(targetSlot in 1..room.maxPlayerCount) { "AI slot must be between 1 and ${room.maxPlayerCount}." }
                    val ai = Team.getTeam(targetSlot - 1) as? AI
                        ?: error("Slot $targetSlot does not contain a native AI.")
                    ai.aiDifficultyOverride = difficulty
                    server.engine.networkEngine.apply {
                        updateTeamAIDifficulty(ai)
                        sendUpdatePlayer(null)
                    }
                    "AI in slot $targetSlot set to difficulty $difficulty."
                }
            }
        }
    }

    commands.command("credits", "Set native starting credits") {
        usage = "<default|0|1000|2000|4000|5000|10000|50000|100000|200000>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { NATIVE_STARTING_CREDITS.keys.map { if (it == 4_000) "default" else it.toString() } }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val optionId = parseNativeStartingCredits(argument(0))
                room.startingCredits = optionId
                val amount = NATIVE_STARTING_CREDITS.entries.first { it.value == optionId }.key
                "Starting credits set to ${if (amount == 4_000) "default (\$4,000)" else "\$$amount"}."
            }
        }
    }

    commands.command("startingunits", "Set the native starting-units preset") {
        usage = "<preset-id>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { server.startingUnitPresetIds.map(Int::toString) }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val options = appKoin.get<Game>().getStartingUnitOptions().map { it.first }
                val units = validateNativeStartingUnits(argument(0).toInt(), options)
                room.startingUnits = units
                "Starting-units preset $units set."
            }
        }
    }

    commands.command("income", "Set the native income multiplier") {
        usage = "<1|1.5|2|2.5>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { NATIVE_INCOME_MULTIPLIERS.map { it.toString() } }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val multiplier = validateNativeIncomeMultiplier(argument(0))
                room.incomeMultiplier = multiplier
                "Income multiplier set to $multiplier."
            }
        }
    }

    commands.command("fog", "Set fog mode") {
        usage = "<off|basic|los>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("off", "basic", "los") }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val mode = when (argument(0).lowercase(Locale.ROOT)) {
                    "off" -> 0
                    "basic" -> 1
                    "los" -> 2
                    else -> error("Fog mode must be off, basic, or los.")
                }
                room.fogMode = mode
                "Fog mode set to ${argument(0).lowercase(Locale.ROOT)}."
            }
        }
    }

    commands.command("revealed", "Enable or disable the revealed map") {
        usage = "<on|off>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("on", "off") }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val value = parseNativeBoolean(argument(0))
                room.revealedMap = value
                "Revealed map ${if (value) "enabled" else "disabled"}."
            }
        }
    }

    commands.command("revealedmap", "Set the native revealed-map command flag") {
        usage = "<true|false>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("true", "false") }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val nativeFlag = parseNativeBoolean(argument(0))
                // Vanilla's -revealedmap command accepts the inverse of GameSetup.revealedMap.
                room.revealedMap = !nativeFlag
                "Revealed map ${if (room.revealedMap) "enabled" else "disabled"}."
            }
        }
    }

    commands.command("nukes", "Enable or disable nukes") {
        usage = "<on|off>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("on", "off", "true", "false") }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val enabled = parseNativeBoolean(argument(0))
                room.noNukes = !enabled
                "Nukes ${if (enabled) "enabled" else "disabled"}."
            }
        }
    }

    commands.command("sharedcontrol", "Enable or disable shared control") {
        aliases("sharedControl")
        usage = "<on|off>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("on", "off", "true", "false") }
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 1)
                val enabled = parseNativeBoolean(argument(0))
                room.sharedControl = enabled
                "Shared control ${if (enabled) "enabled" else "disabled"}."
            }
        }
    }

    commands.command("team", "Set a player's ally group") {
        usage = "<slot> <1..99|-1>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size == 2)
                val slot = argument(0).toInt() - 1
                val target = requireNativePlayer(room, slot)
                val requestedGroup = argument(1).toInt()
                validateOneBasedNativeAllyGroup(requestedGroup)
                target.allyTeam = if (requestedGroup == -1) target.teamId % 2 else requestedGroup - 1
                syncNativePlayerList(server)
                "${target.name ?: "Slot ${slot + 1}"} moved to ally group ${target.allyTeam + 1}."
            }
        }
    }

    commands.command("move", "Move a player to another room slot") {
        usage = "<from-slot> <to-slot> [0..98|-1]"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        body {
            runAdminCommand {
                val room = server.adminRoom()
                requireLobby(server)
                require(args.size in 2..3) { "Usage: move <from-slot> <to-slot> [ally-group]" }
                val from = argument(0).toInt() - 1
                val to = argument(1).toInt() - 1
                require(to in 0 until room.maxPlayerCount) { "Destination slot must be between 1 and ${room.maxPlayerCount}." }
                val target = requireNativePlayer(room, from)
                val group = args.getOrNull(2)?.toIntOrNull()
                require(args.size == 2 || group != null) { "Ally group must be an integer." }
                group?.let(::validateZeroBasedNativeAllyGroup)
                server.engine.networkEngine.moveTeamToSlot(target, to)
                if (group != null) target.allyTeam = if (group == -1) target.teamId % 2 else group
                syncNativePlayerList(server)
                "Moved ${target.name ?: "player"} to slot ${target.teamId + 1}."
            }
        }
    }

    commands.command("players", "List registered native room players") {
        requireVisibility(CommandVisibility.SERVER)
        body {
            runAdminCommand {
                val room = server.adminRoom()
                val network = server.engine.networkEngine
                val players =
                    room.getPlayers().sortedWith(compareBy<Team>({ if (it.isSpectator()) 1 else 0 }, { it.teamId }))
                if (players.isEmpty()) {
                    reply("No players are in the room.")
                } else {
                    players.forEach { player ->
                        val slot = if (player.isSpectator()) "spectator" else "slot ${player.teamId + 1}"
                        val kind = when {
                            player.isAI -> "AI"
                            player.isSpectator() -> "spectator"
                            player === network.localTeam -> "host"
                            network.getConnectionForTeam(player) != null -> "connected"
                            else -> "registered"
                        }
                        reply("$slot: ${player.name ?: "(unnamed)"} [$kind, ally ${player.allyTeam + 1}]")
                    }
                }
                "${players.size} registered player/team entr${if (players.size == 1) "y" else "ies"}."
            }
        }
    }

    commands.command("language", "Set the native game language") {
        usage = "<system|language-code>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("system", "en", "zh", "ru", "es", "fr", "de", "ja") }
        body {
            runAdminCommand {
                server.assertOwnerThread()
                require(args.size == 1) { "Usage: language <system|language-code>" }
                val available = listNativeLanguageCodes(Path.of("assets", "translations"))
                val language = normalizeNativeLanguage(argument(0), available)
                val engine = server.engine
                engine.settings.forceEnglish = false
                if (language == null) engine.settings.overrideLanguageCode = ""
                LocaleEngine.languageOverride = language
                LocaleEngine.reloadBundle()
                "Native game language set to ${language ?: "system"}."
            }
        }
    }

    commands.command("replay", "Enable or disable native replay recording") {
        usage = "<on|off|status>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        onComplete { listOf("on", "off", "status") }
        body {
            runAdminCommand {
                server.assertOwnerThread()
                require(args.size == 1) { "Usage: replay <on|off|status>" }
                server.setReplayEnabled(argument(0).lowercase(Locale.ROOT))
            }
        }
    }

    commands.command("prompt", "Change the console input prompt") {
        usage = "<text>"
        requireVisibility(CommandVisibility.SERVER)
        requirePermission(SERVER_ADMIN_PERMISSION)
        body {
            runAdminCommand {
                server.assertOwnerThread()
                require(args.isNotEmpty()) { "Usage: prompt <text>" }
                val prompt = args.joinToString(" ")
                require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_LENGTH) {
                    "Prompt must be between 1 and $MAX_PROMPT_LENGTH characters."
                }
                require(prompt.none { it.isISOControl() }) { "Prompt cannot contain control characters." }
                setConsolePrompt(prompt)
                "Console prompt changed."
            }
        }
    }
}

internal fun resolveNativeMapArgument(query: String, maps: List<GameMap>): String {
    require(query.isNotBlank()) { "Usage: map <index|path|name>" }
    val normalizedQuery = query.replace('\\', '/')
    require(
        !normalizedQuery.startsWith('/') && ':' !in normalizedQuery &&
                normalizedQuery.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Map path must stay inside the native game asset directories."
    }
    val available = nativeServerMaps(maps)
    query.toIntOrNull()?.let { index ->
        return available.getOrNull(index - 1)?.enginePath
            ?: error("Map index must be between 1 and ${available.size}.")
    }
    val matching = available.filter {
        it.enginePath.replace('\\', '/').equals(normalizedQuery, ignoreCase = true) ||
                it.displayName().equals(query, ignoreCase = true) ||
                it.enginePath.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
                    .equals(query, ignoreCase = true) ||
                it.mapName.equals(query, ignoreCase = true)
    }
    require(matching.size <= 1) { "Map name '$query' is ambiguous; use a full path or map index." }
    return matching.singleOrNull()?.enginePath
        ?: error("No native map matches '$query'. Use maps to list available maps.")
}

internal fun nativeServerMaps(maps: List<GameMap>): List<GameMap> = maps
    .filter { map ->
        val path = map.enginePath.replace('\\', '/')
        path.startsWith("maps/skirmish/", ignoreCase = true) ||
                path.startsWith("mods/maps/", ignoreCase = true) ||
                path.startsWith("saves/", ignoreCase = true)
    }
    .distinctBy { it.enginePath.lowercase(Locale.ROOT) }
    .sortedBy { it.enginePath.lowercase(Locale.ROOT) }

internal fun parseNativeBoolean(value: String): Boolean = when (value.lowercase(Locale.ROOT)) {
    "on", "true" -> true
    "off", "false" -> false
    else -> throw IllegalArgumentException("Expected on/off or true/false.")
}

internal fun validateNativeMaxPlayers(count: Int, occupiedTeamIds: List<Int>): Int {
    require(count in MIN_NATIVE_PLAYERS..MAX_NATIVE_PLAYERS) {
        "Maximum players must be between $MIN_NATIVE_PLAYERS and $MAX_NATIVE_PLAYERS."
    }
    val outOfRange = occupiedTeamIds.firstOrNull { it !in 0 until count }
    require(outOfRange == null) {
        "Cannot shrink to $count slots while a player occupies slot ${(outOfRange ?: 0) + 1}."
    }
    return count
}

internal fun validateNativeAiDifficulty(value: Int): Int {
    require(value in NATIVE_AI_DIFFICULTY) { "AI difficulty must be between -2 and 3." }
    return value
}

internal fun parseNativeStartingCredits(value: String): Int {
    val normalized = value.lowercase(Locale.ROOT)
    val amount = if (normalized == "default") 4_000 else normalized.toIntOrNull()
        ?: throw IllegalArgumentException("Starting credits must be default or a listed dollar amount.")
    return NATIVE_STARTING_CREDITS[amount]
        ?: throw IllegalArgumentException("Unsupported native starting-credit value: $value.")
}

internal fun validateNativeStartingUnits(value: Int, allowedPresetIds: List<Int>): Int {
    require(value in allowedPresetIds) { "Unknown starting-units preset ID: $value." }
    return value
}

internal fun parseNativeAddAiCount(arguments: List<String>): Int {
    require(arguments.size <= 1) { "Usage: addai [count]" }
    val count = arguments.singleOrNull()?.toInt()
        ?: if (arguments.isEmpty()) 1 else throw IllegalArgumentException("AI count must be an integer.")
    require(count in 1..MAX_NATIVE_PLAYERS) { "AI count must be between 1 and $MAX_NATIVE_PLAYERS." }
    return count
}

internal fun parseNativeAiArguments(arguments: List<String>): Pair<Int, Int?> {
    require(arguments.size in 1..2) { "Usage: ai <-2..3> [AI slot]" }
    val difficulty = validateNativeAiDifficulty(arguments[0].toInt())
    val targetSlot = arguments.getOrNull(1)?.toInt()
    return difficulty to targetSlot
}

internal fun validateNativeIncomeMultiplier(value: String): Float {
    val parsed = value.toFloatOrNull()
    require(parsed != null && parsed in NATIVE_INCOME_MULTIPLIERS) {
        "Income multiplier must be one of ${NATIVE_INCOME_MULTIPLIERS.joinToString()}."
    }
    return parsed
}

internal fun validateOneBasedNativeAllyGroup(value: Int): Int {
    require(value == -1 || value in 1..MAX_NATIVE_ALLY_GROUPS) {
        "Ally group must be -1 or between 1 and $MAX_NATIVE_ALLY_GROUPS."
    }
    return value
}

internal fun validateZeroBasedNativeAllyGroup(value: Int): Int {
    require(value == -1 || value in 0 until MAX_NATIVE_ALLY_GROUPS) {
        "Ally group must be -1 or between 0 and ${MAX_NATIVE_ALLY_GROUPS - 1}."
    }
    return value
}

internal fun normalizeNativeLanguage(value: String, availableCodes: Set<String>): String? {
    if (value.equals("system", ignoreCase = true)) return null
    val normalized = value.trim().lowercase(Locale.ROOT).replace('-', '_')
    require(normalized.matches(NATIVE_LANGUAGE_PATTERN)) { "Language code must be a language tag such as en or zh_cn." }
    require(normalized in availableCodes) { "No native translation bundle is available for '$normalized'." }
    return normalized
}

internal fun listNativeLanguageCodes(translations: Path): Set<String> {
    if (!Files.isDirectory(translations)) return emptySet()
    return Files.list(translations).use { files ->
        files.map { it.fileName.toString() }
            .filter { it.startsWith("Strings_") && it.endsWith(".properties") }
            .map { it.removePrefix("Strings_").removeSuffix(".properties").lowercase(Locale.ROOT) }
            .toList()
            .toSet()
    }
}

internal class NativeReplayController(
    private val matchActive: () -> Boolean,
    private val isRecording: () -> Boolean,
    private val startRecording: () -> Unit,
    private val stopRecording: () -> Unit,
) {
    var enabled: Boolean = false
        private set

    var lastError: String? = null
        private set

    fun onMatchStarted() {
        if (enabled && !isRecording()) startSafely()
    }

    fun execute(action: String): String = when (action.lowercase(Locale.ROOT)) {
        "status" -> status()
        "on" -> {
            enabled = true
            lastError = null
            if (matchActive() && !isRecording()) startSafely()
            status()
        }

        "off" -> {
            enabled = false
            lastError = null
            if (isRecording()) runCatching { stopRecording() }.onFailure(::rememberError)
            status()
        }

        else -> throw IllegalArgumentException("Usage: replay <on|off|status>")
    }

    private fun status(): String {
        val state = when {
            !enabled -> "Native replay recording is disabled."
            isRecording() -> "Native replay recording is enabled and active."
            matchActive() -> "Native replay recording is enabled but the native recorder is not active."
            else -> "Native replay recording is enabled for the next match."
        }
        return lastError?.let { "$state Last error: $it" } ?: state
    }

    private fun startSafely() {
        runCatching { startRecording() }.onFailure(::rememberError)
    }

    private fun rememberError(error: Throwable) {
        lastError = error.message ?: error.javaClass.simpleName
    }
}

private fun CommandContext.runAdminCommand(action: CommandContext.() -> String) {
    runCatching { action() }
        .onSuccess(::reply)
        .onFailure { reply(it.message ?: "Command failed.") }
}

private fun NativeHeadlessServer.adminRoom(): GameRoom {
    assertOwnerThread()
    check(engine.networkEngine.isServer) { "The native server is not hosting." }
    return appKoin.get<GameRoom>()
}

private fun requireLobby(server: NativeHeadlessServer) {
    server.assertOwnerThread()
    val network = server.engine.networkEngine
    check(!network.gameStarted && !network.isGameStarting) { "This command is only available in the lobby." }
}

private fun requireNativePlayer(room: GameRoom, slot: Int): Team {
    require(slot in 0 until room.maxPlayerCount) { "Player slot must be between 1 and ${room.maxPlayerCount}." }
    return room.getPlayers().firstOrNull { it.teamId == slot }
        ?: error("There is no player in slot ${slot + 1}.")
}

private fun syncNativePlayerList(server: NativeHeadlessServer) {
    server.engine.networkEngine.apply {
        assignTeamDisplaySlots()
        markPlayerListDirty()
        sendUpdatePlayer(null)
        sendServerInfoToAll()
    }
}

// Keys are user-facing dollar values; values are the native GameSetup option IDs.
private val NATIVE_STARTING_CREDITS = linkedMapOf(
    4_000 to 0,
    0 to 1,
    1_000 to 2,
    2_000 to 3,
    5_000 to 4,
    10_000 to 5,
    50_000 to 6,
    100_000 to 7,
    200_000 to 8,
)
private val NATIVE_INCOME_MULTIPLIERS = setOf(1f, 1.5f, 2f, 2.5f)
private val NATIVE_AI_DIFFICULTY = -2..3
private val NATIVE_LANGUAGE_PATTERN = Regex("[a-z]{2,3}(?:_[a-z0-9]{2,8})?")
private const val MAPS_PER_PAGE = 20
private const val MAX_PROMPT_LENGTH = 64
internal const val SERVER_ADMIN_PERMISSION = "server.admin"
internal const val MIN_NATIVE_PLAYERS = 10
internal const val MAX_NATIVE_PLAYERS = 100
private const val MAX_NATIVE_ALLY_GROUPS = 99
