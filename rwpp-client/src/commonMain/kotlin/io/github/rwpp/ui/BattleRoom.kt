/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.ai.AI
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.RefreshUIEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.ai.AiManager
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.mode.*
import io.github.rwpp.api.game.sendChatMessageOrCommand
import io.github.rwpp.api.game.team.*
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.logging.logger
import io.github.rwpp.config.RoomPreset
import io.github.rwpp.config.RoomPresetPlayer
import io.github.rwpp.config.Settings
import io.github.rwpp.event.OnDispose
import io.github.rwpp.game.*
import io.github.rwpp.game.room.*
import io.github.rwpp.protocolVersion
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.ui.color.getTeamColor
import io.github.rwpp.widget.TableCell
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.largeProportion
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import kotlin.math.roundToInt

private fun addRoomAi(
    room: GameRoom,
    uiScope: CoroutineScope,
    count: Int = 1,
    refresh: () -> Unit = {},
) {
    scheduleRoomAiAddition(
        addAI = room::addAI,
        count = count,
        gameScope = GameScope,
        uiScope = uiScope,
        refresh = refresh,
    )
}

@Single
class BattleRoomScreen : Screen {
    var selectedPlayer: Team by mutableStateOf(noPlayer)
    var playerOverrideVisible by mutableStateOf(false)
    var update by mutableIntStateOf(0)
    var optionVisible by mutableStateOf(false)
    var banUnitVisible by mutableStateOf(false)
    var selectedBanUnits by mutableStateOf(listOf<UnitTypeInterface>())
    var recyclingUnitVisible by mutableStateOf(false)
    var selectedRecyclingUnits by mutableStateOf(listOf<UnitTypeInterface>())
    var showMapSelectView by mutableStateOf(false)
    private val maps = mutableStateListOf<GameMap>()
    private var mapsLoading by mutableStateOf(false)
    private var mapsLoadError by mutableStateOf<String?>(null)
    private var mapsLoaded by mutableStateOf(false)
    private var mapCatalogRefresh by mutableIntStateOf(0)

    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()
        val room = game.gameRoom
        val gameState = game.state
        val desktop = koinInject<AppContext>().isDesktop()
        val compact = LocalWindowManager.current == WindowManager.Small

        var lastSelectedIndex by remember { mutableIntStateOf(0) }
        var selectedMap by remember(update) { mutableStateOf(room.selectedMap) }
        val displayMapName = remember(update, selectedMap) { room.displayMapName }
        val isHost = remember(update) { room.isHost || room.isHostServer }
        val updateAction = { update += 1 }

        val scope = rememberCoroutineScope()

        GlobalEventChannel.filter(RefreshUIEvent::class).OnDispose {
            subscribeAlways { scope.launch { updateAction() } }
        }

        LaunchedEffect(UI.warning) {
            if (UI.warning != null) {
                if (UI.warning?.isKicked == true && navigator.current === this@BattleRoomScreen) {
                    navigator.pop()
                }
            }
        }

        val players = remember(update) { room.getPlayers().sortedBy { it.team } }
        // A server may temporarily send blank or duplicate client IDs (notably for bots).
        // Keep lazy-row identity local to the actual Team object, independent of that handshake.
        val rosterIds = remember { java.util.IdentityHashMap<Team, Int>() }
        val nextRosterId = remember { intArrayOf(0) }
        val playerRowKeys = players.map { rosterIds.getOrPut(it) { nextRosterId[0]++ } }
        LaunchedEffect(Unit) {
            playerOverrideVisible = false
            optionVisible = false
            banUnitVisible = false
            selectedBanUnits = emptyList()
            recyclingUnitVisible = false
            val recyclingMode = room.gameModes.activeModes.filterIsInstance<RecyclingRulesMode>().firstOrNull()
            val disabledRecyclingNames = recyclingMode?.disabledUnitNames.orEmpty()
            selectedRecyclingUnits = game.getAllUnitTypes().filter { it.internalName in disabledRecyclingNames }
            showMapSelectView = false
            selectedPlayer = players.firstOrNull() ?: noPlayer
            updateAction()
        }

        PlayerOverrideDialog(
            playerOverrideVisible,
            { playerOverrideVisible = false },
            updateAction,
            room,
            selectedPlayer
        )

        LaunchedEffect(showMapSelectView, mapCatalogRefresh) {
            if (!showMapSelectView) return@LaunchedEffect
            maps.clear()
            mapsLoading = true
            mapsLoadError = null
            var completed = false
            try {
                game.getMapPages(refresh = mapCatalogRefresh > 0).collect { page ->
                    maps.addAll(page.items)
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mapsLoadError = failure.message ?: readI18n("rwpp.catalog.unknownError")
            } finally {
                mapsLoading = false
                mapsLoaded = completed
            }
        }
        MapViewDialog(
            visible = showMapSelectView,
            onDismissRequest = { showMapSelectView = false },
            lastSelectedIndex = lastSelectedIndex,
            lastSelectedMapType = if (room.isHostServer) GameSetupMapType.skirmishMap else selectedMap.mapType,
            displayMaps = maps,
            isLoading = mapsLoading,
            loadError = mapsLoadError,
            mapTypeItems = if (room.isHost) GameSetupMapType.entries else listOf(GameSetupMapType.skirmishMap),
            lastSelectedMapKey = selectedMap.mapSelectionKey(),
            onRefresh = {
                mapCatalogRefresh++
            },
            onSelectedMap = { index, map ->
                selectedMap = map
                room.selectedMap = map
                lastSelectedIndex = index
            },
        )

        MultiplayerOption(
            optionVisible,
            { optionVisible = false },
            updateAction,
            { banUnitVisible = true; optionVisible = false },
            selectedRecyclingUnits,
            { recyclingUnitVisible = true },
            { selectedRecyclingUnits = it },
        )

        BanUnitViewDialog(
            visible = banUnitVisible,
            onDismissRequest = { banUnitVisible = false },
            lastSelectedUnits = selectedBanUnits,
            onSelectedUnits = { selectedUnits ->
                selectedBanUnits = selectedUnits
                val unitNames = selectedUnits.map(UnitTypeInterface::getInternalName).toSet()
                GameScope.launch {
                    if (unitNames.isEmpty()) room.gameModes.deactivate(BanUnitsMode.ID)
                    else room.gameModes.replace(BanUnitsMode(unitNames))
                }
            }
        )

        BanUnitViewDialog(
            visible = recyclingUnitVisible,
            onDismissRequest = { recyclingUnitVisible = false },
            lastSelectedUnits = selectedRecyclingUnits,
            onSelectedUnits = { selectedRecyclingUnits = it },
            title = "RECYCLING UNIT RESTRICTIONS",
            tagPrefix = "DISABLED",
        )

        val chatFocusRequester = remember { FocusRequester() }
        var chatHasFocus by remember { mutableStateOf(false) }

        @Composable
        fun ContentView() {
            val globalFocusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            val interactionSource = remember { MutableInteractionSource() }

            AeroPanel(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (compact) 4.dp else 8.dp)
                    .focusRequester(globalFocusRequester)
                    .clickable(interactionSource, null) {
                        globalFocusRequester.requestFocus()
                        keyboardController?.hide()
                    }
                    .onKeyEvent {
                        !chatHasFocus && it.type == KeyEventType.KeyDown && !it.isCtrlPressed && !it.isShiftPressed && when (it.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                chatFocusRequester.requestFocus()
                                true
                            }

                            Key.M -> {
                                showMapSelectView = true
                                true
                            }

                            Key.O -> {
                                optionVisible = true
                                true
                            }

                            Key.A -> {
                                addRoomAi(room, scope, refresh = updateAction)
                                true
                            }

                            Key.C -> {
                                if (players.isNotEmpty()) {
                                    selectedPlayer = room.localPlayer
                                    playerOverrideVisible = true
                                }
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                RoomWorkspaceLayout(
                    modifier = Modifier.fillMaxSize(),
                    compact = compact,
                    header = {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!compact) {
                                AeroHeader(
                                    title = when {
                                        gameState.multiplayer -> "MULTIPLAYER ROOM"
                                        gameState.sandbox -> "SANDBOX ROOM"
                                        else -> "SINGLE PLAYER ROOM"
                                    },
                                    tag = "NET // PROTOCOL V$protocolVersion",
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(16.dp))
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            AeroButton(
                                text = readI18n("rwpp.common.exit"),
                                isDanger = true,
                                modifier = Modifier.width(if (compact) 84.dp else 120.dp)
                                    .height(if (compact) 40.dp else 48.dp),
                                clickSound = Sounds.closePopup,
                                onClick = {
                                    room.disconnect()
                                    // A restored Android composition may hold the room as the
                                    // root, where pop() has nowhere to go.
                                    if (!navigator.pop()) {
                                        navigator.replaceAll(navigator.koinScope.get<MainMenuScreen>())
                                    }
                                },
                            )
                        }
                        if (!compact) AeroDivider()
                    },
                    map = {
                        var details by remember(room) {
                            mutableStateOf(readI18n("rwpp.multiplayer.room.loadingDetails"))
                        }
                        LaunchedEffect(update) {
                            details = room.roomDetails().split("\n")
                                .filter { !it.startsWith("Map:") && it.isNotBlank() }
                                .joinToString("\n")
                        }
                        RoomMapPane(
                            modifier = if (compact) Modifier.fillMaxWidth()
                                .wrapContentHeight() else Modifier.fillMaxSize(),
                            compact = compact,
                            mapPreview = { AeroMapPreview(selectedMap, true) },
                            mapType = remember(update, selectedMap) { room.mapType }.displayName,
                            mapName = displayMapName,
                            details = details,
                            isHost = isHost,
                            sectorInfoLabel = "SECTOR INFO",
                            hostAccessLabel = readI18n("rwpp.multiplayer.room.hostAccess"),
                            guestLabel = readI18n("rwpp.multiplayer.room.guest"),
                            startLabel = readI18n("rwpp.common.start"),
                            selectMapLabel = readI18n("rwpp.multiplayer.room.selectMap"),
                            optionsLabel = readI18n("rwpp.multiplayer.room.option"),
                            steamInviteLabel = readI18n("rwpp.multiplayer.room.inviteSteam"),
                            onSelectMap = { showMapSelectView = true },
                            onOptions = { optionVisible = true },
                            onInviteSteam = if (desktop && gameState.multiplayer && room.isHost) {
                                { inviteSteamPlayers() }
                            } else null,
                            onStart = {
                                val unpreparedPlayers = game.gameRoom.getPlayers().filter { !it.ready }
                                if (unpreparedPlayers.isNotEmpty()) {
                                    game.gameRoom.sendSystemMessage(
                                        readI18n(
                                            "rwpp.multiplayer.room.unreadyPlayers",
                                            unpreparedPlayers.joinToString(", ") { it.name },
                                        )
                                    )
                                } else if (room.isHostServer) room.sendQuickGameCommand("-start")
                                else room.startGame()
                            },
                        )
                    },
                    players = {
                        val state = rememberLazyListState()
                        AeroPanel(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(if (compact) 6.dp else 10.dp)
                            ) {
                                if (!compact) {
                                    AeroHeader(
                                        title = "OPERATOR ROSTER",
                                        tag = "${players.size} PLAYERS",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier.fillMaxWidth().clip(AeroSciFiTheme.ChamferAeroShape(6f))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .padding(vertical = 6.dp, horizontal = 8.dp),
                                    ) {
                                        TableCell(
                                            readI18n("rwpp.common.player"), .55f, drawStroke = false,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        TableCell(
                                            readI18n("rwpp.common.spawnPoint"),
                                            .15f,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        TableCell(
                                            readI18n("rwpp.common.team"),
                                            .15f,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        TableCell(
                                            readI18n("rwpp.common.ping"), .15f, drawStroke = false,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                                LazyColumnScrollbar(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    LazyColumn(modifier = Modifier.fillMaxSize(), state = state) {
                                        items(count = players.size, key = { playerRowKeys[it] }) { index ->
                                            PlayerTable(players, index, Modifier.aeroAnimateItem(), compact)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    chat = {
                        MessageView(
                            modifier = if (compact) Modifier.fillMaxWidth()
                                .wrapContentHeight() else Modifier.fillMaxSize(),
                            chatFocusRequester = chatFocusRequester,
                            onComposerFocusChanged = { chatHasFocus = it },
                        )
                    },
                )
            }
        }
        ContentView()
    }

    private fun inviteSteamPlayers() {
        GameScope.launch {
            val opened = runCatching { openRoomSteamInvite() }
                .onFailure { logger().warn("Could not open the Steam invite dialog", it) }
                .getOrDefault(false)
            if (!opened) UI.showWarning(readI18n("rwpp.multiplayer.room.steamUnavailable"))
        }
    }

    @Composable
    fun OptionButtons(
        onShowMapSelectView: () -> Unit,
        onShowOption: () -> Unit
    ) {
        val game = koinInject<Game>()
        val room = game.gameRoom
        RoomMapActionBar(
            compact = LocalWindowManager.current == WindowManager.Small,
            startLabel = readI18n("rwpp.common.start"),
            selectMapLabel = readI18n("rwpp.multiplayer.room.selectMap"),
            optionsLabel = readI18n("rwpp.multiplayer.room.option"),
            onSelectMap = onShowMapSelectView,
            onOptions = onShowOption,
            onStart = {
                val unpreparedPlayers = game.gameRoom.getPlayers().filter { !it.ready }
                if (unpreparedPlayers.isNotEmpty()) {
                    game.gameRoom.sendSystemMessage(
                        readI18n(
                            "rwpp.multiplayer.room.unreadyPlayers",
                            unpreparedPlayers.joinToString(", ") { it.name },
                        )
                    )
                } else if (room.isHostServer) room.sendQuickGameCommand("-start") else room.startGame()
            },
        )
    }

    @Composable
    fun MessageView(
        modifier: Modifier = Modifier,
        chatFocusRequester: FocusRequester = remember { FocusRequester() },
        onComposerFocusChanged: (Boolean) -> Unit = {},
    ) {
        val game = koinInject<Game>()
        val room = game.gameRoom
        val isHost = room.isHost || room.isHostServer
        val uiScope = rememberCoroutineScope()
        var isLocked by remember(update) { mutableStateOf(room.lockedRoom) }
        var chatMessage by remember { mutableStateOf("") }
        RoomChatPanel(
            modifier = modifier,
            history = UI.chatMessages,
            onComposerFocusChanged = onComposerFocusChanged,
            message = chatMessage,
            onMessageChange = { chatMessage = it },
            onSend = {
                if (chatMessage.isNotEmpty()) {
                    val outgoing = chatMessage
                    chatMessage = ""
                    uiScope.launch(io.github.rwpp.api.coroutines.GameMainDispatcher) {
                        room.sendChatMessageOrCommand(
                            outgoing
                        )
                    }
                }
            },
            chatFocusRequester = chatFocusRequester,
            isHost = isHost,
            isSandbox = game.state.sandbox,
            isLocked = isLocked,
            onChangeSite = {
                if (room.getPlayers().isNotEmpty()) {
                    selectedPlayer = room.localPlayer
                    playerOverrideVisible = true
                }
            },
            onAddAI = { count -> addRoomAi(room, uiScope, count, refresh = { update += 1 }) },
            onToggleLock = {
                isLocked = !isLocked
                room.lockedRoom = isLocked
            },
            chatTitle = readI18n("rwpp.multiplayer.room.tacticalComms"),
            chatTag = readI18n("rwpp.multiplayer.room.broadcastChannels"),
            changeSiteDescription = readI18n("rwpp.multiplayer.room.changeSite"),
            teamLockDescription = readI18n("rwpp.multiplayer.room.teamLock"),
            sendMessageDescription = readI18n("rwpp.multiplayer.room.sendMessage"),
            addAiLabel = readI18n("rwpp.multiplayer.room.addAI"),
            compact = LocalWindowManager.current == WindowManager.Small,
        )
    }

    @Composable
    fun PlayerOverrideDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit,
        update: () -> Unit,
        room: GameRoom,
        player: Team,
    ) {
        val game = koinInject<Game>()
        val items = remember {
            buildList {
                add(-1 to readI18n("rwpp.common.default"))
                addAll(game.getStartingUnitOptions())
            }
        }

        AnimatedAlertDialog(
            visible, onDismissRequest = { onDismissRequest(); update() }
        ) { dismiss ->

            var playerSpawnPoint by remember(player) {
                mutableStateOf<Int?>(if (player.isSpectator) SPECTATOR_SPAWN_POINT else player.spawnPoint + 1)
            }
            var previousNormalSpawnPoint by remember(player) {
                mutableStateOf(player.spawnPoint.takeIf {
                    !player.isSpectator && it in 0 until room.maxPlayerCount
                })
            }
            var playerTeam by remember(player) { mutableStateOf<Int?>(-1) }
            var playerColor by remember(player, player.colorOverride) {
                mutableIntStateOf(playerColorOverrideSelection(player.colorOverride))
            }
            var playerStartingUnits by remember(player) { mutableIntStateOf(items.indexOfFirst { it.first == player.startingUnit }) }
            var aiDifficulty by remember(player) { mutableStateOf(player.difficulty) }
            var aiPaused by remember(player) { mutableStateOf(false) }
            val canControlSpectatorMode = room.isHost || room.isHostServer || player == room.localPlayer

            LaunchedEffect(player, room.isHost) {
                if (player.isAI && room.isHost) {
                    GameScope.launch {
                        aiPaused = AiManager.getNativeAIs()
                            .firstOrNull { it.teamId == player.teamId }
                            ?.aiUpdatePaused ?: false
                    }
                }
            }

            AeroPanel(
                modifier = Modifier.fillMaxSize(largeProportion())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    AeroHeader(
                        title = "PLAYER CONFIG",
                        tag = "OPERATOR // ${player.name}"
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    AeroDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (player !== room.localPlayer && !player.isAI) {
                            ChatMute.observeRevision()
                            AeroToggleTile(
                                readI18n("rwpp.chat.mutePlayer"), ChatMute.isMuted(player),
                                { ChatMute.setMuted(player, it) }, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (canControlSpectatorMode && (room.allowSpectators || playerSpawnPoint == SPECTATOR_SPAWN_POINT)) {
                            AeroToggleTile(
                                label = readI18n("rwpp.common.spectator"),
                                checked = playerSpawnPoint == SPECTATOR_SPAWN_POINT,
                                onCheckedChange = { becomingSpectator ->
                                    if (becomingSpectator) {
                                        if (room.allowSpectators) {
                                            playerSpawnPoint?.takeIf { it in 1..room.maxPlayerCount }
                                                ?.let { previousNormalSpawnPoint = it - 1 }
                                            playerSpawnPoint = SPECTATOR_SPAWN_POINT
                                        }
                                    } else {
                                        val occupiedSpawnPoints = room.getPlayers()
                                            .asSequence()
                                            .filter { it != player && !it.isSpectator }
                                            .map { it.spawnPoint }
                                            .toSet()
                                        val restoredSpawnPoint = resolveSpectatorSpawnPointSelection(
                                            spectator = false,
                                            preferredSpawnPoint = previousNormalSpawnPoint,
                                            currentSpawnPoint = player.spawnPoint.takeUnless { it < 0 },
                                            maxPlayerCount = room.maxPlayerCount,
                                            occupiedSpawnPoints = occupiedSpawnPoints,
                                        )
                                        if (restoredSpawnPoint != null) {
                                            previousNormalSpawnPoint = restoredSpawnPoint
                                            playerSpawnPoint = restoredSpawnPoint + 1
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (playerSpawnPoint != SPECTATOR_SPAWN_POINT) {
                            AeroTextField(
                                value = playerSpawnPoint?.toString() ?: "",
                                onValueChange = {
                                    val n = it.toIntOrNull()
                                    if (n == null || n in 1..room.maxPlayerCount) {
                                        playerSpawnPoint = n
                                        n?.let { selected -> previousNormalSpawnPoint = selected - 1 }
                                    }
                                },
                                placeholder = readI18n("rwpp.common.spawnPoint")
                            )
                        }

                        Text(
                            readI18n("rwpp.multiplayer.room.spawnPointTip"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )

                        AeroTextField(
                            value = if (playerTeam == -1) readI18n("rwpp.common.auto") else playerTeam?.toString()
                                ?: "",
                            onValueChange = {
                                playerTeam = it.toIntOrNull()?.coerceAtMost(100)?.coerceAtLeast(1)
                            },
                            placeholder = readI18n("rwpp.common.team")
                        )

                        Text(
                            readI18n("rwpp.multiplayer.room.teamTip"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )

                        if (room.isHost) {
                            val colorItems = listOf(
                                readI18n("rwpp.common.color.default"),
                                readI18n("rwpp.common.color.green"),
                                readI18n("rwpp.common.color.red"),
                                readI18n("rwpp.common.color.blue"),
                                readI18n("rwpp.common.color.yellow"),
                                readI18n("rwpp.common.color.cyan"),
                                readI18n("rwpp.common.color.white"),
                                readI18n("rwpp.common.color.black"),
                                readI18n("rwpp.common.color.pink"),
                                readI18n("rwpp.common.color.orange"),
                                readI18n("rwpp.common.color.purple"),
                            )
                            AeroDropdownMenu(
                                label = readI18n("rwpp.common.color"),
                                items = colorItems,
                                selectedIndex = (playerColor + 1).coerceIn(0, colorItems.size - 1),
                                onItemSelected = { playerColor = it - 1 }
                            )

                            AeroDropdownMenu(
                                label = readI18n("rwpp.multiplayer.room.startingUnit"),
                                items = items.map { it.second },
                                selectedIndex = playerStartingUnits.coerceAtLeast(0),
                                onItemSelected = { playerStartingUnits = it }
                            )
                        }

                        if (player.isAI) {
                            val difficultyItems = listOf(readI18n("rwpp.multiplayer.room.aiDifficultyInherit")) +
                                    listOf(
                                        readI18n("rwpp.common.difficulty.veryEasy"),
                                        readI18n("rwpp.common.difficulty.easy"),
                                        readI18n("rwpp.common.difficulty.medium"),
                                        readI18n("rwpp.common.difficulty.hard"),
                                        readI18n("rwpp.common.difficulty.veryHard"),
                                        readI18n("rwpp.common.difficulty.impossible"),
                                    )
                            AeroDropdownMenu(
                                label = readI18n("rwpp.common.difficulty"),
                                items = difficultyItems,
                                selectedIndex = aiDifficulty?.plus(3)?.coerceIn(1, 6) ?: 0,
                                onItemSelected = { aiDifficulty = if (it == 0) null else it - 3 }
                            )
                            if (room.isHost) {
                                AeroButton(
                                    text = readI18n(if (aiPaused) "rwpp.multiplayer.room.aiResume" else "rwpp.multiplayer.room.aiPause"),
                                    onClick = {
                                        val ai = player as? AI
                                        if (ai != null) {
                                            GameScope.launch {
                                                if (aiPaused) AiManager.resumeNativeAI(ai)
                                                else AiManager.pauseNativeAI(ai)
                                                aiPaused = !aiPaused
                                                update()
                                            }
                                        }
                                    }
                                )
                            }
                        }

                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    AeroDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (player != room.localPlayer && (room.isHost || room.isHostServer)) {
                            AeroButton(
                                text = readI18n("rwpp.multiplayer.room.kick"),
                                isDanger = true,
                                modifier = Modifier.width(120.dp),
                                clickSound = Sounds.closePopup,
                                onClick = {
                                    val ai = if (player.isAI && room.isHost) player as? AI else null
                                    if (ai != null) {
                                        GameScope.launch {
                                            AiManager.removeNativeAI(ai)
                                            update()
                                        }
                                    } else {
                                        room.kickPlayer(player)
                                        update()
                                    }
                                    dismiss()
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        AeroButton(
                            text = readI18n("rwpp.multiplayer.room.apply"),
                            modifier = Modifier.width(140.dp),
                            clickSound = Sounds.closePopup,
                            onClick = {
                                player.applyConfigChange(
                                    if (playerSpawnPoint == SPECTATOR_SPAWN_POINT) SPECTATOR_SPAWN_POINT
                                    else ((playerSpawnPoint ?: 1) - 1).coerceAtLeast(0),
                                    if (playerTeam == -1) 0 else playerTeam ?: 1,
                                    if (playerColor > -1) playerColor else null,
                                    if (playerStartingUnits > 0) items[playerStartingUnits].first else null,
                                    player.difficulty,
                                    playerTeam == -1
                                )
                                val ai = if (player.isAI && room.isHost) player as? AI else null
                                if (ai != null && player.difficulty != aiDifficulty) {
                                    GameScope.launch {
                                        AiManager.setDifficulty(ai, aiDifficulty)
                                        update()
                                    }
                                } else {
                                    update()
                                }
                                dismiss()
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MultiplayerOption(
        visible: Boolean,
        onDismissRequest: () -> Unit,
        update: () -> Unit,
        onShowBanUnitDialog: () -> Unit,
        selectedRecyclingUnits: List<UnitTypeInterface>,
        onShowRecyclingUnitDialog: () -> Unit,
        onRecyclingUnitsChanged: (List<UnitTypeInterface>) -> Unit,
    ) = AnimatedAlertDialog(
        visible, onDismissRequest = { onDismissRequest(); update() }
    ) { dismiss ->
        val game = koinInject<Game>()
        val room = game.gameRoom
        val configIO = koinInject<ConfigIO>()
        val clientSettings = koinInject<Settings>()
        val scope = rememberCoroutineScope()

        val players = room.getPlayers()

        var noNukes by remember { mutableStateOf(room.noNukes) }
        var sharedControl by remember { mutableStateOf(room.sharedControl) }
        var allowSpectators by remember { mutableStateOf(room.allowSpectators) }
        var teamLock by remember { mutableStateOf(room.teamLock) }
        var fogMode by remember { mutableStateOf(room.fogMode) }
        var startingUnits by remember { mutableIntStateOf(room.startingUnits) }
        var teamMode by remember { mutableStateOf(room.teamMode) }
        var startingCredits by remember { mutableIntStateOf(room.startingCredits) }
        var maxPlayerCount by remember { mutableIntStateOf(room.maxPlayerCount) }
        var realIncomeMultiplier by remember { mutableFloatStateOf(room.incomeMultiplier) }
        var gameSpeed by remember {
            mutableFloatStateOf(
                room.gameModes.activeModes.filterIsInstance<GameSpeedMode>()
                    .firstOrNull { it.id == GameSpeedMode.ID }?.multiplier ?: 1f
            )
        }
        val existingRecyclingMode = remember(room) {
            room.gameModes.activeModes.filterIsInstance<RecyclingRulesMode>().firstOrNull()
        }
        var disableRecycling by remember(room) {
            mutableStateOf(existingRecyclingMode?.disableAllRecycling ?: false)
        }
        var showPresetPanel by remember { mutableStateOf(false) }
        val baseGameModes = remember { GameModes.registry.all().filter { it.kind == GameModeKind.BASE } }
        var baseGameMode by remember {
            mutableStateOf(room.gameModes.activeModes.firstOrNull { it.kind == GameModeKind.BASE })
        }
        var aiDifficulty by remember { mutableIntStateOf(room.aiDifficulty) }

        var presets by remember { mutableStateOf(clientSettings.roomPresets) }
        var presetName by remember { mutableStateOf("") }
        var presetIndex by remember { mutableIntStateOf(-1) }
        var pendingMap by remember { mutableStateOf<GameMap?>(null) }
        var pendingPreset by remember { mutableStateOf<RoomPreset?>(null) }

        AeroPanel(
            modifier = Modifier.fillMaxSize(largeProportion())
        ) {
            if (showPresetPanel) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AeroHeader(title = "ROOM PRESETS", tag = "MATCH // PRESETS")
                    AeroDivider()
                    if (room.isHost || room.isHostServer) {
                        AeroDropdownMenu(
                            label = readI18n("rwpp.roomPreset.select"),
                            items = listOf(readI18n("rwpp.roomPreset.select")) + presets.map { it.name },
                            selectedIndex = (presetIndex + 1).coerceIn(0, presets.size),
                            onItemSelected = {
                                presetIndex = it - 1
                                presetName = presets.getOrNull(presetIndex)?.name.orEmpty()
                            },
                        )
                        AeroTextField(
                            presetName,
                            { presetName = it.take(80) },
                            placeholder = readI18n("rwpp.roomPreset.name"),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AeroButton(readI18n("rwpp.roomPreset.save"), onClick = {
                                val name = presetName.trim()
                                if (name.isNotEmpty()) {
                                    val map = pendingMap ?: room.selectedMap
                                    val preset = RoomPreset(
                                        name,
                                        map.enginePath,
                                        map.mapType.name,
                                        maxPlayerCount,
                                        sharedControl,
                                        startingCredits,
                                        startingUnits,
                                        fogMode,
                                        aiDifficulty,
                                        realIncomeMultiplier,
                                        noNukes,
                                        allowSpectators,
                                        room.lockedRoom,
                                        teamLock,
                                        gameSpeed,
                                        baseGameMode?.id,
                                        room.getPlayers().map {
                                            RoomPresetPlayer(
                                                it.teamId,
                                                it.allyTeam,
                                                it.colorOverride,
                                                it.startingUnitsOverride,
                                            )
                                        },
                                        recyclingDisabled = disableRecycling,
                                        recyclingDisabledUnits = selectedRecyclingUnits.map { it.internalName },
                                    )
                                    val previous = clientSettings.roomPresets
                                    val next = previous.filterNot { it.name.equals(name, ignoreCase = true) } + preset
                                    clientSettings.roomPresets = next
                                    runCatching { configIO.saveConfig(clientSettings) }.onSuccess {
                                        presets = next
                                        presetIndex = next.lastIndex
                                    }.onFailure {
                                        clientSettings.roomPresets = previous
                                        UI.showWarning(it.message.orEmpty())
                                    }
                                }
                            })
                            AeroButton(readI18n("rwpp.roomPreset.load"), onClick = {
                                presets.getOrNull(presetIndex)?.let { preset ->
                                    scope.launch {
                                        val mapCatalog = if (mapsLoaded) {
                                            maps.toList()
                                        } else {
                                            ArrayList<GameMap>().also { loadedMaps ->
                                                game.getMapPages().collect { page -> loadedMaps.addAll(page.items) }
                                            }
                                        }
                                        val map = findPresetMap(preset, mapCatalog)
                                        if (map == null) {
                                            UI.showWarning(readI18n("rwpp.roomPreset.mapMissing", preset.mapPath))
                                        } else {
                                            pendingMap = map
                                            pendingPreset = preset
                                            maxPlayerCount = preset.maxPlayers.coerceAtLeast(players.size)
                                            sharedControl = preset.sharedControl
                                            startingCredits = preset.startingCredits
                                            startingUnits = preset.startingUnits
                                            fogMode = preset.fog
                                            aiDifficulty = preset.aiDifficulty
                                            realIncomeMultiplier = preset.income
                                            noNukes = preset.noNukes
                                            allowSpectators = preset.allowSpectators
                                            teamLock = preset.teamLock
                                            gameSpeed = preset.gameSpeed
                                            disableRecycling = preset.recyclingDisabled
                                            onRecyclingUnitsChanged(
                                                game.getAllUnitTypes().filter {
                                                    it.internalName in preset.recyclingDisabledUnits
                                                },
                                            )
                                            baseGameMode = baseGameModes.firstOrNull { it.id == preset.baseMode }
                                            teamMode = PresetTeamMode(preset.players)
                                        }
                                    }
                                }
                            })
                            AeroButton(readI18n("rwpp.common.delete"), isDanger = true, onClick = {
                                val current = presets.getOrNull(presetIndex)
                                if (current != null) {
                                    val previous = clientSettings.roomPresets
                                    val next = previous.filterNot { it === current || it.name == current.name }
                                    clientSettings.roomPresets = next
                                    runCatching { configIO.saveConfig(clientSettings) }.onSuccess {
                                        presets = next
                                        presetIndex = -1
                                        presetName = ""
                                    }.onFailure {
                                        clientSettings.roomPresets = previous
                                        UI.showWarning(it.message.orEmpty())
                                    }
                                }
                            })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AeroDivider()
                    AeroButton(
                        text = readI18n("rwpp.common.back"),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showPresetPanel = false },
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    AeroHeader(
                        title = "MULTIPLAYER PARAMETERS",
                        tag = "MATCH // CONFIG"
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    AeroDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AeroToggleTile(
                                    label = readI18n("rwpp.multiplayer.room.noNukes"),
                                    checked = noNukes,
                                    onCheckedChange = { noNukes = it },
                                    modifier = Modifier.weight(1f)
                                )
                                AeroToggleTile(
                                    label = readI18n("rwpp.multiplayer.room.sharedControl"),
                                    checked = sharedControl,
                                    onCheckedChange = { sharedControl = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AeroToggleTile(
                                    label = readI18n("rwpp.multiplayer.room.allowSpectators"),
                                    checked = allowSpectators,
                                    onCheckedChange = { if (room.isHost) allowSpectators = it },
                                    modifier = Modifier.weight(1f)
                                )
                                AeroToggleTile(
                                    label = readI18n("rwpp.multiplayer.room.teamLock"),
                                    checked = teamLock,
                                    onCheckedChange = { if (room.isHost) teamLock = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AeroDropdownMenu(
                                    label = readI18n("rwpp.common.difficulty"),
                                    items = listOf(
                                        readI18n("rwpp.common.difficulty.veryEasy"),
                                        readI18n("rwpp.common.difficulty.easy"),
                                        readI18n("rwpp.common.difficulty.medium"),
                                        readI18n("rwpp.common.difficulty.hard"),
                                        readI18n("rwpp.common.difficulty.veryHard"),
                                        readI18n("rwpp.common.difficulty.impossible"),
                                    ),
                                    selectedIndex = (aiDifficulty + 2).coerceIn(0, 5),
                                    onItemSelected = { aiDifficulty = it - 2 },
                                    modifier = Modifier.weight(1f)
                                )

                                AeroDropdownMenu(
                                    label = readI18n("rwpp.common.fog"),
                                    items = listOf(
                                        readI18n("rwpp.common.fog.none"),
                                        readI18n("rwpp.common.fog.basic"),
                                        readI18n("rwpp.common.fog.lineOfSight"),
                                    ),
                                    selectedIndex = fogMode.coerceIn(0, 2),
                                    onItemSelected = { fogMode = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (room.isHost || room.isHostServer) item {
                            // The native layout operation only runs on the server. Proxy hosts
                            // can use modes whose assignments are sent through room commands.
                            val modes = TeamModes.modes.filter { room.isHost || it !is InternalTeamMode }
                            val choices = listOf(null) + modes +
                                    listOfNotNull(room.teamMode, teamMode).filter { it !in modes }.distinct()
                            AeroDropdownMenu(
                                label = readI18n("rwpp.multiplayer.room.teamMode"),
                                items = choices.map { mode ->
                                    val key = when (mode) {
                                        null -> "manual"
                                        InternalTeamMode2t -> "twoTeams"
                                        InternalTeamMode3t -> "threeTeams"
                                        InternalTeamModeFFA -> "ffa"
                                        InternalTeamModeSpectators -> "spectators"
                                        RandomTeamMode -> "random"
                                        AllVsAI -> "allVsAi"
                                        AllVs2 -> "allVsTwo"
                                        is PresetTeamMode -> "preset"
                                        else -> return@map mode.displayName
                                    }
                                    readI18n("rwpp.multiplayer.room.teamMode.$key")
                                },
                                selectedIndex = choices.indexOf(teamMode),
                                onItemSelected = { teamMode = choices[it] },
                            )
                        }

                        item {
                            val startingOptionList = remember { game.getStartingUnitOptions() }
                            AeroDropdownMenu(
                                label = readI18n("rwpp.multiplayer.room.startingUnit"),
                                items = startingOptionList.map { it.second },
                                selectedIndex = startingOptionList.indexOfFirst { it.first == startingUnits }
                                    .coerceAtLeast(0),
                                onItemSelected = {
                                    startingUnits = startingOptionList[it].first
                                }
                            )
                        }

                        item {
                            val startingCreditList = remember {
                                listOf(readI18n("rwpp.multiplayer.room.defaultStartingCredits", "$4000")) +
                                        listOf(0, 1000, 2000, 5000, 10000, 50000, 100000, 200000).map { "$$it" }
                            }
                            AeroDropdownMenu(
                                label = readI18n("rwpp.multiplayer.room.startingCredits"),
                                items = startingCreditList,
                                selectedIndex = startingCredits.coerceIn(0, startingCreditList.size - 1),
                                onItemSelected = { startingCredits = it }
                            )
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                AeroSettingSlider(
                                    value = maxPlayerCount.coerceIn(players.size.coerceIn(0, 100), 100).toFloat(),
                                    onValueChange = { maxPlayerCount = normalizeRoomMaxPlayerCount(it, players.size) },
                                    valueRange = players.size.coerceIn(0, 100).toFloat()..100f,
                                    label = readI18n("rwpp.multiplayer.room.maxPlayer"),
                                    valueFormat = { formatRoomMaxPlayerCount(it) },
                                )
                                if (room.isHost) {
                                    AeroSettingSlider(
                                        label = readI18n("rwpp.multiplayer.room.gameSpeed"),
                                        value = gameSpeed,
                                        onValueChange = { gameSpeed = (it.coerceIn(0f, 5f) * 20f).roundToInt() / 20f },
                                        valueRange = 0f..5f,
                                        valueFormat = { "${"%.2f".format(it)}x" },
                                    )
                                }
                            }
                        }

                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AeroTextField(
                                    value = realIncomeMultiplier.toString(),
                                    onValueChange = { realIncomeMultiplier = it.toFloatOrNull() ?: 1f },
                                    placeholder = readI18n("rwpp.multiplayer.room.incomeMultiplier"),
                                    modifier = Modifier.weight(1f)
                                )

                                if (room.isHost) {
                                    var teamUnitCapHostedGame by remember {
                                        mutableStateOf(configIO.getGameConfig<Int?>("teamUnitCapHostedGame"))
                                    }
                                    AeroTextField(
                                        value = teamUnitCapHostedGame?.toString() ?: "",
                                        onValueChange = {
                                            val cap = it.toIntOrNull()
                                            teamUnitCapHostedGame = cap
                                            configIO.setGameConfig("teamUnitCapHostedGame", cap ?: 100)
                                            game.setTeamUnitCapHostGame(cap ?: 100)
                                        },
                                        placeholder = readI18n("rwpp.multiplayer.room.teamUnitCapHostedGame"),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        if (room.isHost) {
                            item {
                                AeroDropdownMenu(
                                    label = readI18n("rwpp.multiplayer.room.gameMode"),
                                    items = listOf(readI18n("rwpp.multiplayer.room.gameModeFromMap")) +
                                            baseGameModes.map { it.displayName },
                                    selectedIndex = baseGameModes.indexOfFirst { it.id == baseGameMode?.id } + 1,
                                    onItemSelected = { baseGameMode = baseGameModes.getOrNull(it - 1) },
                                )
                            }
                            item {
                                AeroButton(
                                    text = readI18n("rwpp.multiplayer.room.banUnits"),
                                    onClick = onShowBanUnitDialog
                                )
                            }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AeroToggleTile(
                                        label = readI18n("rwpp.multiplayer.room.disableRecycling"),
                                        checked = disableRecycling,
                                        onCheckedChange = { disableRecycling = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    AeroButton(
                                        text = readI18n("rwpp.multiplayer.room.recyclingUnits"),
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = onShowRecyclingUnitDialog,
                                    )
                                }
                            }
                        }
                        if (room.isHost || room.isHostServer) item {
                            AeroButton(
                                text = readI18n("rwpp.roomPreset.title"),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showPresetPanel = true },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    AeroDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        AeroButton(
                            text = readI18n("rwpp.multiplayer.room.apply"),
                            modifier = Modifier.width(140.dp),
                            clickSound = Sounds.closePopup,
                            onClick = {
                                GameScope.launch {
                                    pendingMap?.let { room.selectedMap = it }
                                    if (room.isHost) pendingPreset?.let { room.lockedRoom = it.lockedRoom }
                                    room.applyRoomConfig(
                                        maxPlayerCount,
                                        sharedControl,
                                        startingCredits,
                                        startingUnits,
                                        fogMode,
                                        aiDifficulty,
                                        realIncomeMultiplier,
                                        noNukes,
                                        allowSpectators,
                                        teamLock,
                                        teamMode
                                    )
                                    val selectedBase = baseGameMode
                                    if (room.isHost) {
                                        if (selectedBase == null) {
                                            room.gameModes.activeModes.filter { it.kind == GameModeKind.BASE }
                                                .forEach { room.gameModes.deactivate(it.id) }
                                        } else room.gameModes.replace(selectedBase)
                                        if (gameSpeed == 1f) room.gameModes.deactivate(GameSpeedMode.ID)
                                        else room.gameModes.replace(GameSpeedMode(gameSpeed))

                                        val disabledUnitNames =
                                            selectedRecyclingUnits.map { it.internalName }.toSet()
                                        if (disableRecycling || disabledUnitNames.isNotEmpty()) {
                                            room.gameModes.replace(
                                                RecyclingRulesMode(
                                                    disableAllRecycling = disableRecycling,
                                                    disabledUnitNames = disabledUnitNames,
                                                ),
                                            )
                                        } else if (room.gameModes.activeModes.any { it.id == RecyclingRulesMode.ID }) {
                                            room.gameModes.deactivate(RecyclingRulesMode.ID)
                                        }
                                    }
                                }
                                dismiss()
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PlayerTable(
        players: List<Team>,
        index: Int,
        modifier: Modifier = Modifier,
        compact: Boolean = false,
    ) {
        val game = koinInject<Game>()
        val room = game.gameRoom
        val options = remember { game.getStartingUnitOptions() }
        val player = players.getOrNull(index)

        val interactionSource = remember { MutableInteractionSource() }
        val isHovered by interactionSource.collectIsHoveredAsState()
        val isClicked by interactionSource.collectIsPressedAsState()

        LaunchedEffect(isHovered) {
            if (isHovered) Sounds.playSound(Sounds.hover2)
        }

        if (player != null) {
            val ping = remember(update) { player.pingStatus }
            val spectatorLabel = readI18n("rwpp.common.spectatorShort")
            val spawn = if (player.isSpectator) spectatorLabel
            else readI18n("rwpp.multiplayer.room.spawnPointShort", player.spawnPoint + 1)
            val tableSpawn = if (player.isSpectator) spectatorLabel else (player.spawnPoint + 1).toString()
            val startingUnit = if (player.startingUnit != -1) {
                options.firstOrNull { it.first == player.startingUnit }?.second ?: readI18n("rwpp.common.defaultShort")
            } else null
            Box(modifier = modifier.padding(vertical = 2.dp)) {
                val rowModifier = Modifier
                    .fillMaxWidth()
                    .clip(AeroSciFiTheme.ChamferAeroShape(6f))
                    .background(
                        if (isHovered || isClicked)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                    )
                    .border(
                        1.dp,
                        if (isHovered || isClicked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        AeroSciFiTheme.ChamferAeroShape(6f)
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = room.isHost || room.isHostServer || room.localPlayer == player || !player.isAI,
                    ) {
                        Sounds.playSound(Sounds.openPopup)
                        if (room.isHost || room.isHostServer || room.localPlayer == player) {
                            selectedPlayer = player
                            playerOverrideVisible = true
                        } else {
                            UI.showDialog { dismiss ->
                                AeroPanel(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(12.dp)) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        AeroHeader(title = "PLAYER", tag = player.name.orEmpty())
                                        ChatMute.observeRevision()
                                        AeroToggleTile(
                                            readI18n("rwpp.chat.mutePlayer"), ChatMute.isMuted(player),
                                            { ChatMute.setMuted(player, it) }, modifier = Modifier.fillMaxWidth()
                                        )
                                        AeroButton(
                                            readI18n("rwpp.common.close"),
                                            onClick = { UI.showDialog(null); dismiss() })
                                    }
                                }
                            }
                        }
                    }

                if (compact) {
                    Column(rowModifier.padding(vertical = 5.dp, horizontal = 7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = player.name + (startingUnit?.let { " • $it" } ?: ""),
                                modifier = Modifier.weight(1f),
                                color = if (player.color != -1) getTeamColor(player.color) else MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!player.ready) CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                player.teamAlias(),
                                Modifier.weight(0.36f),
                                color = getTeamColor(player.team),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                spawn,
                                Modifier.weight(0.40f),
                                color = if (player.isSpectator) MaterialTheme.colorScheme.outline else getTeamColor(
                                    player.spawnPoint
                                ),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                ping,
                                Modifier.weight(0.24f),
                                color = pingColor(player.ping),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = rowModifier.padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TableCell(
                            player.name + (startingUnit?.let { " [$it]" } ?: ""),
                            color = if (player.color != -1) getTeamColor(player.color) else MaterialTheme.colorScheme.onSurface,
                            weight = .55f,
                            drawStroke = false,
                        ) {
                            if (!player.ready) CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp).padding(start = 4.dp),
                            )
                        }
                        TableCell(
                            tableSpawn,
                            .15f,
                            color = if (player.isSpectator) MaterialTheme.colorScheme.outline else getTeamColor(player.spawnPoint),
                        )
                        TableCell(player.teamAlias(), .15f, color = getTeamColor(player.team))
                        TableCell(ping, .15f, color = pingColor(player.ping), drawStroke = false)
                    }
                }
            }
        }
    }

}
