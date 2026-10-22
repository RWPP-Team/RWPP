/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.api.coroutines.GameScope
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.event.OnDispose
import io.github.rwpp.api.event.events.RefreshUIEvent
import io.github.rwpp.game.*
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.game.units.UnitTypeInterface
import io.github.rwpp.api.i18n.readI18n
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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import kotlin.math.roundToInt
import io.github.rwpp.game.noPlayer
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.sendChatMessageOrCommand
import io.github.rwpp.api.game.team.TeamModes
import io.github.rwpp.api.game.mode.BanUnitsMode
import io.github.rwpp.api.game.mode.GameModeKind
import io.github.rwpp.api.game.mode.GameModes
import io.github.rwpp.api.game.mode.GameSpeedMode
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.ai.AI
import io.github.rwpp.api.game.ai.AiManager

private fun addRoomAi(room: GameRoom, count: Int = 1, refresh: () -> Unit = {}) {
    if (!room.isHost) {
        room.addAI(count)
        refresh()
        return
    }
    GameScope.launch {
        repeat(count.coerceAtLeast(1)) { AiManager.createNativeAI() }
        refresh()
    }
}

@Single
class BattleRoomScreen : Screen {
    var selectedPlayer: Team by mutableStateOf(noPlayer)
    var playerOverrideVisible by mutableStateOf(false)
    var update by  mutableStateOf(false)
    var optionVisible by mutableStateOf(false)
    var banUnitVisible by mutableStateOf(false)
    var selectedBanUnits by mutableStateOf(listOf<UnitTypeInterface>())
    var showMapSelectView by mutableStateOf(false)

    val playerNameWeight = .55f
    val playerSpawnWeight = .15f
    val playerTeamWeight = .15f
    val playerPingWeight = .15f

    @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()
        val room = game.gameRoom
        val gameState = game.state

        var lastSelectedIndex by remember { mutableIntStateOf(0) }
        var selectedMap by remember(update) { mutableStateOf(room.selectedMap) }
        val displayMapName = remember(update) { room.displayMapName }
        val isHost = remember(update) { room.isHost || room.isHostServer }
        val updateAction = { update = !update }

        val scope = rememberCoroutineScope()

        DisposableEffect(Unit) {
            onDispose {
                playerOverrideVisible = false
                optionVisible = false
                banUnitVisible = false
                selectedBanUnits = listOf()
                showMapSelectView = false
            }
        }

        GlobalEventChannel.filter(RefreshUIEvent::class).OnDispose {
            subscribeAlways { updateAction() }
        }

        LaunchedEffect(UI.warning) {
            if (UI.warning != null) {
                if (UI.warning?.isKicked == true) {
                    navigator.pop()
                }
            }
        }

        val players = remember(update) { room.getPlayers().sortedBy { it.team } }
        LaunchedEffect(Unit) {
            selectedPlayer = players.firstOrNull() ?: noPlayer
            UI.chatMessages = AnnotatedString("")
        }

        PlayerOverrideDialog(
            playerOverrideVisible,
            { playerOverrideVisible = false },
            updateAction,
            room,
            selectedPlayer
        )

        var maps by remember { mutableStateOf(game.getAllMaps()) }
        MapViewDialog(
            showMapSelectView,
            { showMapSelectView = false },
            lastSelectedIndex,
            if (room.isHostServer) GameSetupMapType.skirmishMap else selectedMap.mapType,
            maps,
            if (room.isHost) GameSetupMapType.entries else listOf(GameSetupMapType.skirmishMap),
            {
                maps = game.getAllMaps(true)
            }
        ) { index, map ->
            selectedMap = map
            room.selectedMap = map
            lastSelectedIndex = index
        }

        MultiplayerOption(
            optionVisible,
            { optionVisible = false },
            updateAction,
            { banUnitVisible = true; optionVisible = false },
        )

        BanUnitViewDialog(banUnitVisible, { banUnitVisible = false }, selectedBanUnits) {
            selectedBanUnits = it
            val unitNames = it.map(UnitTypeInterface::getInternalName).toSet()
            GameScope.launch {
                if (unitNames.isEmpty()) room.gameModes.deactivate(BanUnitsMode.ID)
                else room.gameModes.replace(BanUnitsMode(unitNames))
            }
        }

        val chatFocusRequester = remember { FocusRequester() }

        @Composable
        fun ContentView() {
            val globalFocusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            val interactionSource = remember { MutableInteractionSource() }
            val isDesktop = remember { appKoin.get<AppContext>().isDesktop() }

            AeroPanel(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .focusRequester(globalFocusRequester)
                    .clickable(
                        interactionSource,
                        null
                    ) {
                        globalFocusRequester.requestFocus()
                        keyboardController?.hide()
                    }
                    .onKeyEvent {
                        it.type == KeyEventType.KeyDown && !it.isCtrlPressed && !it.isShiftPressed && when (it.key) {
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
                                addRoomAi(room, refresh = updateAction)
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
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            AeroHeader(
                                title = "TACTICAL BATTLE ROOM",
                                tag = "NET // PROTOCOL V${protocolVersion}",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            AeroButton(
                                text = readI18n("rwpp.common.exit"),
                                isDanger = true,
                                modifier = Modifier.width(120.dp),
                                clickSound = Sounds.closePopup,
                                onClick = { navigator.pop() }
                            )
                        }

                        AeroDivider(modifier = Modifier.padding(bottom = 12.dp))

                        // 中间主内容区域
                        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                // 地图与房间信息面板
                                AeroPanel(
                                    modifier = Modifier
                                        .weight(.4f)
                                        .fillMaxHeight()
                                        .padding(end = 6.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        AeroHeader(
                                            title = "SECTOR INFO",
                                            tag = if (isHost) "HOST ACCESS" else "GUEST",
                                            color = MaterialTheme.colorScheme.tertiary
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        var details by remember { mutableStateOf("Getting details...") }
                                        remember(update) {
                                            scope.launch {
                                                details = room.roomDetails().split("\n")
                                                    .filter { !it.startsWith("Map:") && it.isNotBlank() }
                                                    .joinToString("\n")
                                            }
                                        }

                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            if (LocalWindowManager.current == WindowManager.Large) {
                                                AeroMapPreview(
                                                    selectedMap,
                                                    true
                                                )
                                            }
                                        }

                                        if (LocalWindowManager.current != WindowManager.Large) {
                                            AeroMapPreview(
                                                selectedMap,
                                                true
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        val mapType = remember(update) { room.mapType }
                                        Text(
                                            mapType.getDisplayName(),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                        )

                                        Text(
                                            displayMapName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            overflow = TextOverflow.Ellipsis,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))
                                        AeroDivider()
                                        Spacer(modifier = Modifier.height(10.dp))

                                        Text(
                                            details,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.weight(1f))

                                        if (isHost) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            OptionButtons({ showMapSelectView = true }) {
                                                optionVisible = true
                                            }
                                        }
                                    }
                                }

                                val state = rememberLazyListState()

                                AeroPanel(
                                    modifier = Modifier
                                        .weight(.6f)
                                        .fillMaxHeight()
                                        .padding(start = 6.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp)
                                    ) {
                                        AeroHeader(
                                            title = "OPERATOR ROSTER",
                                            tag = "${players.size} PLAYERS ONLINE"
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(AeroSciFiTheme.ChamferAeroShape(6f))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                .padding(vertical = 6.dp, horizontal = 8.dp)
                                        ) {
                                            TableCell(
                                                "OPERATOR",
                                                playerNameWeight,
                                                drawStroke = false,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            TableCell(
                                                "SPAWN",
                                                playerSpawnWeight,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            TableCell(
                                                "TEAM",
                                                playerTeamWeight,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            TableCell(
                                                "PING",
                                                playerPingWeight,
                                                drawStroke = false,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        LazyColumnScrollbar(
                                            state = state,
                                            modifier = Modifier.fillMaxWidth().weight(1f)
                                        ) {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                state = state
                                            ) {
                                                items(
                                                    count = players.size,
                                                    key = { players[it].connectHexId }
                                                ) { index ->
                                                    PlayerTable(
                                                        players,
                                                        index,
                                                        Modifier.aeroAnimateItem()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        AeroPanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.5f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    AeroHeader(
                                        title = "TACTICAL COMMS",
                                        tag = "CHANNELS // BROADCAST",
                                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        AeroButton(
                                            text = readI18n("rwpp.multiplayer.room.changeSite") + if (isDesktop) "(C)" else "",
                                            onClick = {
                                                if (players.isNotEmpty()) {
                                                    selectedPlayer = room.localPlayer
                                                    playerOverrideVisible = true
                                                }
                                            },
                                            clickSound = Sounds.openPopup,
                                            modifier = Modifier.width(130.dp)
                                        )

                                        if (isHost) {
                                            AeroButton(
                                                text = readI18n("rwpp.multiplayer.room.addAI") + if (isDesktop) "(A)" else "",
                                                onClick = { addRoomAi(room, refresh = updateAction) },
                                                modifier = Modifier.width(130.dp)
                                            )
                                        }

                                        var isLocked by remember(update) { mutableStateOf(room.lockedRoom) }
                                        if (!gameState.sandbox && isHost) {
                                            AeroIconButton(
                                                painter = rememberVectorPainter(Icons.Rounded.Lock),
                                                isDanger = isLocked,
                                                onClick = { isLocked = !isLocked; room.lockedRoom = isLocked },
                                                forceHover = isLocked,
                                                modifier = Modifier.width(50.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(AeroSciFiTheme.ChamferAeroShape(6f))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f))
                                            .padding(6.dp)
                                    ) {
                                        MessageView()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (LocalWindowManager.current != WindowManager.Large) {
            Scaffold(
                containerColor = Color.Transparent,
                floatingActionButton = {
                    if (isHost) {
                        LongPressFloatingActionButton(
                            Icons.Default.Add,
                            modifier = Modifier.padding(5.dp),
                            onClick = { addRoomAi(room, refresh = updateAction) },
                            onLongClick = { addRoomAi(room, 10, updateAction) }
                        )
                    }
                },
                floatingActionButtonPosition = FabPosition.End
            ) {
                ContentView()
            }
        } else {
            ContentView()
        }
    }

    @Composable
    fun OptionButtons(
        onShowMapSelectView: () -> Unit,
        onShowOption: () -> Unit
    ) {
        val game = koinInject<Game>()
        val room = game.gameRoom
        val isDesktop = remember { appKoin.get<AppContext>().isDesktop() }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AeroButton(
                    text = readI18n("rwpp.multiplayer.room.selectMap") + if (isDesktop) "(M)" else "",
                    onClick = onShowMapSelectView,
                    clickSound = Sounds.openPopup,
                    modifier = Modifier.weight(1f)
                )
                AeroButton(
                    text = readI18n("rwpp.multiplayer.room.option") + if (isDesktop) "(O)" else "",
                    onClick = onShowOption,
                    clickSound = Sounds.openPopup,
                    modifier = Modifier.weight(1f)
                )
            }

            AeroButton(
                text = readI18n("rwpp.common.start"),
                onClick = {
                    val unpreparedPlayers = game.gameRoom.getPlayers().filter { !it.ready }
                    if (unpreparedPlayers.isNotEmpty()) {
                        game.gameRoom.sendSystemMessage(
                            "Cannot start game. Players not ready: ${unpreparedPlayers.joinToString(", ") { it.name }}"
                        )
                    } else if (room.isHostServer) room.sendQuickGameCommand("-start") else room.startGame()
                },
                accentColor = MaterialTheme.colorScheme.tertiary
            )
        }
    }

    @Composable
    fun MessageView(
        modifier: Modifier = Modifier,
        chatFocusRequester: FocusRequester = remember { FocusRequester() }
    ) {
        val game = koinInject<Game>()
        val room = game.gameRoom

        var chatMessage by remember { mutableStateOf("") }
        var messageValue by remember(UI.chatMessages) { mutableStateOf(TextFieldValue(UI.chatMessages)) }

        fun sendMessage() {
            if (chatMessage.isNotEmpty()) {
                room.sendChatMessageOrCommand(chatMessage)
                chatMessage = ""
            }
        }

        // 1. 统一外层面板容器：将消息历史与输入框整合为一个完整的 UI 整体
        AeroPanel(
            modifier = modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // 2. 消息展示区：去除 TextField 默认背景与指示线，使其透明融入面板背景
                TextField(
                    value = messageValue,
                    onValueChange = { messageValue = it },
                    readOnly = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                // 3. 内置分割线：明确层次感，替代外边距堆叠
                AeroDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 4. 底部终端内联输入行：无缝嵌在控制台底部的输入区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 命令行前缀提示符（强化终端/聊天面板感）
                    Text(
                        text = "> ",
                        style = TextStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    // 内联透明输入框
                    BasicTextField(
                        value = chatMessage,
                        onValueChange = { chatMessage = it },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(chatFocusRequester)
                            .onKeyEvent {
                                if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && chatMessage.isNotEmpty()) {
                                    sendMessage()
                                    true
                                } else false
                            },
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (chatMessage.isEmpty()) {
                                    Text(
                                        text = readI18n("rwpp.multiplayer.room.sendMessage"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // 内嵌发送按钮
                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowForward),
                        onClick = { sendMessage() },
                        size = 28.dp,
                        iconSize = 14.dp
                    )
                }
            }
        }
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
                add(-1 to "Default")
                addAll(game.getStartingUnitOptions())
            }
        }

        AnimatedAlertDialog(
            visible, onDismissRequest = { onDismissRequest(); update() }
        ) { dismiss ->

            var playerSpawnPoint by remember(player) { mutableStateOf<Int?>(player.spawnPoint + 1) }
            var playerTeam by remember(player) { mutableStateOf<Int?>(-1) }
            var playerColor by remember(player) { mutableIntStateOf(player.color) }
            var playerStartingUnits by remember(player) { mutableIntStateOf(items.indexOfFirst { it.first == player.startingUnit }) }
            var aiDifficulty by remember(player) { mutableStateOf(player.difficulty) }
            var aiPaused by remember(player) { mutableStateOf(false) }

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
                        title = readI18n("rwpp.multiplayer.room.playerConfig"),
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
                        AeroTextField(
                            value = if (playerSpawnPoint == -3) "Spectator" else playerSpawnPoint?.toString() ?: "",
                            onValueChange = {
                                val n = it.toIntOrNull()
                                if (n == null || n <= room.maxPlayerCount) playerSpawnPoint = n
                            },
                            placeholder = readI18n("rwpp.common.spawnPoint")
                        )

                        Text(
                            readI18n("rwpp.multiplayer.room.spawnPointTip"),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )

                        AeroTextField(
                            value = if (playerTeam == -1) "auto" else playerTeam?.toString() ?: "",
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
                                "Default", "Green", "Red", "Blue", "Yellow",
                                "Cyan", "White", "Black", "Pink", "Orange", "Purple"
                            )
                            AeroDropdownMenu(
                                label = "Color",
                                items = colorItems,
                                selectedIndex = (playerColor + 1).coerceIn(0, colorItems.size - 1),
                                onItemSelected = { playerColor = it - 1 }
                            )

                            AeroDropdownMenu(
                                label = "Starting Units",
                                items = items.map { it.second },
                                selectedIndex = playerStartingUnits.coerceAtLeast(0),
                                onItemSelected = { playerStartingUnits = it }
                            )
                        }

                        if (player.isAI) {
                            val difficultyItems = listOf(readI18n("rwpp.multiplayer.room.aiDifficultyInherit")) +
                                listOf("VeryEasy", "Easy", "Medium", "Hard", "VeryHard", "Impossible")
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
                                    if (playerSpawnPoint == -3) -3 else ((playerSpawnPoint ?: 1) - 1).coerceAtLeast(0),
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
    ) = AnimatedAlertDialog(
        visible, onDismissRequest = { onDismissRequest(); update() }
    ) { dismiss ->
        val game = koinInject<Game>()
        val room = game.gameRoom
        val configIO = koinInject<ConfigIO>()

        val players = remember { room.getPlayers() }

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
            mutableFloatStateOf(room.gameModes.activeModes.filterIsInstance<GameSpeedMode>()
                .firstOrNull { it.id == GameSpeedMode.ID }?.multiplier ?: 1f)
        }
        val baseGameModes = remember { GameModes.registry.all().filter { it.kind == GameModeKind.BASE } }
        var baseGameMode by remember {
            mutableStateOf(room.gameModes.activeModes.firstOrNull { it.kind == GameModeKind.BASE })
        }
        var aiDifficulty by remember { mutableIntStateOf(room.aiDifficulty) }

        val teamModes = remember { TeamModes.modes }

        AeroPanel(
            modifier = Modifier.fillMaxSize(largeProportion())
        ) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            AeroDropdownMenu(
                                label = readI18n("rwpp.common.difficulty"),
                                items = listOf("VeryEasy", "Easy", "Medium", "Hard", "VeryHard", "Impossible"),
                                selectedIndex = (aiDifficulty + 2).coerceIn(0, 5),
                                onItemSelected = { aiDifficulty = it - 2 },
                                modifier = Modifier.weight(1f)
                            )

                            AeroDropdownMenu(
                                label = readI18n("rwpp.common.fog"),
                                items = listOf("NoFog", "BasicFog", "LOSFog"),
                                selectedIndex = fogMode.coerceIn(0, 2),
                                onItemSelected = { fogMode = it },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        val startingOptionList = remember { game.getStartingUnitOptions() }
                        var selectedIndex3 by remember(room) { mutableIntStateOf(startingOptionList.indexOfFirst { it.first == room.startingUnits }) }

                        AeroDropdownMenu(
                            label = readI18n("rwpp.multiplayer.room.startingUnit"),
                            items = startingOptionList.map { it.second },
                            selectedIndex = selectedIndex3.coerceAtLeast(0),
                            onItemSelected = {
                                selectedIndex3 = it
                                startingUnits = startingOptionList[it].first
                            }
                        )
                    }

                    item {
                        val startingCreditList = remember {
                            listOf("Default ($4000)", "$0", "$1000", "$2000", "$5000", "$10000", "$50000", "$100000", "$200000")
                        }
                        AeroDropdownMenu(
                            label = readI18n("rwpp.multiplayer.room.startingCredits"),
                            items = startingCreditList,
                            selectedIndex = startingCredits.coerceIn(0, startingCreditList.size - 1),
                            onItemSelected = { startingCredits = it }
                        )
                    }

                    item {
                        AeroSettingSlider(
                            value = maxPlayerCount.toFloat(),
                            onValueChange = { maxPlayerCount = it.roundToInt() },
                            valueRange = players.size.toFloat()..100f,
                            label = readI18n("rwpp.multiplayer.room.maxPlayer")
                        )
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            AeroTextField(
                                value = realIncomeMultiplier.toString(),
                                onValueChange = { realIncomeMultiplier = it.toFloatOrNull() ?: 1f },
                                placeholder = readI18n("rwpp.multiplayer.room.incomeMultiplier"),
                                modifier = Modifier.weight(1f)
                            )

                            var teamUnitCapHostedGame by remember { mutableStateOf(configIO.getGameConfig<Int?>("teamUnitCapHostedGame")) }
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

                    if (room.isHost) {
                        item {
                            AeroSettingSlider(
                                label = readI18n("rwpp.multiplayer.room.gameSpeed"),
                                value = gameSpeed,
                                onValueChange = { gameSpeed = (it.coerceIn(0f, 5f) * 20f).roundToInt() / 20f },
                                valueRange = 0f..5f,
                                valueFormat = { "${"%.2f".format(it)}x" },
                            )
                        }
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
                                }
                            }
                            dismiss()
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun PlayerTable(
        players: List<Team>,
        index: Int,
        modifier: Modifier = Modifier
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
            Box(modifier = modifier.padding(vertical = 2.dp)) {
                Row(
                    modifier = Modifier
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
                            if (isHovered || isClicked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(
                                alpha = 0.3f
                            ),
                            AeroSciFiTheme.ChamferAeroShape(6f)
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            room.isHost || room.isHostServer || room.localPlayer == player
                        ) {
                            Sounds.openPopup?.play(GameScope.coroutineContext)
                            selectedPlayer = player
                            playerOverrideVisible = true
                        }
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableCell(
                        player.name + if (player.startingUnit != -1) " [${options.firstOrNull { it.first == player.startingUnit }?.second ?: "DEF"}]" else "",
                        color = if (player.color != -1) getTeamColor(player.color) else MaterialTheme.colorScheme.onSurface,
                        weight = playerNameWeight,
                        drawStroke = false
                    ) {
                        if (!player.ready) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(12.dp)
                                    .padding(start = 4.dp)
                            )
                        }
                    }
                    TableCell(
                        if (player.isSpectator) "SPEC" else (player.spawnPoint + 1).toString(),
                        playerSpawnWeight,
                        color = if (player.isSpectator) MaterialTheme.colorScheme.outline else getTeamColor(
                            player.spawnPoint
                        )
                    )
                    TableCell(
                        player.teamAlias(),
                        playerTeamWeight,
                        color = getTeamColor(player.team)
                    )
                    val ping = remember(update) { player.pingStatus }
                    TableCell(
                        ping,
                        playerPingWeight,
                        drawStroke = false
                    )
                }
            }
        }
    }
}
