/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import io.github.rwpp.AppContext
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.config.ServerType
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.LoadingContext
import io.github.rwpp.api.game.RoomJoinTimeoutException
import io.github.rwpp.api.game.RoomOption
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.io.SizeUtils
import io.github.rwpp.api.logging.logger
import io.github.rwpp.api.net.Net
import io.github.rwpp.api.net.RoomDescription
import io.github.rwpp.api.net.sorted
import io.github.rwpp.api.server.ServerData
import io.github.rwpp.api.server.ServerInfoQuery
import io.github.rwpp.api.server.SocketServerInfoQuery
import io.github.rwpp.api.util.calculateSize
import io.github.rwpp.appKoin
import io.github.rwpp.config.Blacklist
import io.github.rwpp.config.Blacklists
import io.github.rwpp.config.MultiplayerPreferences
import io.github.rwpp.config.Settings
import io.github.rwpp.data.ComposeServerData
import io.github.rwpp.gameVersion
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.drag_30
import io.github.rwpp.generated.error_missingmap
import io.github.rwpp.generated.tune_30
import io.github.rwpp.maxModSize
import io.github.rwpp.platform.readPainterByBytes
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.autoClearFocus
import io.github.rwpp.widget.generalProportion
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import kotlin.math.roundToInt

@Single
class MultiplayerScreen : Screen {
    val instance by appKoin.inject<MultiplayerPreferences>()
    val settings by appKoin.inject<Settings>()
    val configIO by appKoin.inject<ConfigIO>()
    val game by appKoin.inject<Game>()
    val net by appKoin.inject<Net>()
    val blacklistsInstance by appKoin.inject<Blacklists>()

    /** Reads server descriptions over the RWPP server-info protocol; the socket client is API-provided. */
    val serverInfoQuery: ServerInfoQuery = SocketServerInfoQuery()

    val allServerData by lazy {
        SnapshotStateList<ServerData>().apply {
            addAll(instance.allServerConfig.map { ComposeServerData(it) })
        }
    }

    var currentViewList by mutableStateOf<List<RoomDescription>>(listOf())
    var throwable by mutableStateOf<Throwable?>(null)

    var userName by run {
        val lastName = configIO.getGameConfig<String?>("lastNetworkPlayerName")
        mutableStateOf((lastName ?: "RWPP${(0..999).random()}").also { game.setUserName(it) })
    }

    val refresh = Channel<Unit>(1)
    var updateServerConfig by mutableStateOf(false)
    val blacklists = mutableStateListOf<Blacklist>().apply { addAll(blacklistsInstance.blacklists) }

    var enableModFilter by mutableStateOf(false)
    var mapNameFilter by mutableStateOf(instance.mapNameFilter)
    var creatorNameFilter by mutableStateOf(instance.creatorNameFilter)
    var playerLimitRange by mutableStateOf(instance.playerLimitRangeFrom..instance.playerLimitRangeTo)
    var joinServerAddress by mutableStateOf(instance.joinServerAddress)
    val showWelcomeMessage by mutableStateOf(settings.showWelcomeMessage)
    var battleroom by mutableStateOf(instance.battleroom)

    var serverAddress by mutableStateOf("")
    var isConnecting by mutableStateOf(false)
    var joinAttemptNumber by mutableIntStateOf(0)
    private var joinNavigationCommitted by mutableStateOf(false)
    private var joinCancelled by mutableStateOf(false)

    var isShowingRoomList by mutableStateOf(false)
    var selectedServerConfig by run {
        val config = instance.allServerConfig.firstOrNull { it.useAsDefaultList }
        if (config != null) isShowingRoomList = true
        mutableStateOf(config)
    }

    var selectedDefaultRoomList by mutableStateOf(allServerData.firstOrNull { it.config.useAsDefaultList })
    var showServerInfoConfig by mutableStateOf(false)
    var selectedRoomDescription by mutableStateOf<RoomDescription?>(null)
    var showJoinRequestDialog by mutableStateOf(false)
    var showWelcomeMessageAdmittingDialog by mutableStateOf(showWelcomeMessage == null)

    var hostDialogVisible by mutableStateOf(false)
    var filterSurfaceDialogVisible by mutableStateOf(false)

    private fun beginJoinAttempt() {
        if (isConnecting) return
        joinAttemptNumber++
        joinNavigationCommitted = false
        joinCancelled = false
        isConnecting = true
        // The native host may send welcome text as soon as transport starts. Clear before that
        // can happen; BattleRoom composition can occur after those messages have been received.
        UI.clearChatMessages()
    }

    private fun cancelCurrentJoin() {
        if (!isConnecting || joinNavigationCommitted || joinCancelled) return
        joinCancelled = true
        isConnecting = false
        game.cancelJoinServer()
    }

    private fun releaseJoinOnDispose() {
        // A server may start a verification map before the lobby/loading coroutine commits.
        // The native gameplay transition transfers ownership, rather than cancelling its socket.
        if (isConnecting && game.state.state != io.github.rwpp.api.State.Menu && game.completeJoinServer()) {
            joinNavigationCommitted = true
            isConnecting = false
        } else cancelCurrentJoin()
    }

    @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val lazyListState = rememberLazyListState()
        var isRefreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
            allServerData.add(to.index, allServerData.removeAt(from.index))
            instance.allServerConfig.add(to.index, instance.allServerConfig.removeAt(from.index))
        }

        LaunchedEffect(blacklists.size) {
            blacklistsInstance.blacklists = blacklists.toMutableList()
        }
        LaunchedEffect(mapNameFilter) { instance.mapNameFilter = mapNameFilter }
        LaunchedEffect(creatorNameFilter) { instance.creatorNameFilter = creatorNameFilter }
        LaunchedEffect(joinServerAddress) { instance.joinServerAddress = joinServerAddress }
        LaunchedEffect(playerLimitRange) {
            instance.playerLimitRangeFrom = playerLimitRange.first
            instance.playerLimitRangeTo = playerLimitRange.last
        }

        DisposableEffect(Unit) {
            onDispose {
                releaseJoinOnDispose()
                game.setUserName(userName)
                showServerInfoConfig = false
                showJoinRequestDialog = false
                showWelcomeMessageAdmittingDialog = false
                hostDialogVisible = false
                filterSurfaceDialogVisible = false
            }
        }

        WelcomeMessageAdmittingDialog(showWelcomeMessageAdmittingDialog) {
            showWelcomeMessageAdmittingDialog = false
        }

        JoinServerRequestDialog(
            showJoinRequestDialog,
            { showJoinRequestDialog = false },
            selectedRoomDescription,
            blacklists
        ) { dismiss ->
            serverAddress = selectedRoomDescription!!.addressProvider()
            beginJoinAttempt()
            dismiss()
        }

        key(joinAttemptNumber) {
            AeroLoadingView(
                visible = isConnecting,
                onLoaded = {
                    cancelCurrentJoin()
                    isConnecting = false
                },
                cancellable = true,
                onCancel = ::cancelCurrentJoin,
            ) {
                val activeAttemptNumber = joinAttemptNumber
                val updateLoadingMessage: (String) -> Unit = this::message
                if (serverAddress.isBlank()) {
                    message(readI18n("rwpp.multiplayer.join.serverNotFound"))
                    return@AeroLoadingView false
                }

                message(readI18n("rwpp.multiplayer.join.connecting"))

                game.setUserName(userName)
                configIO.setGameConfig("lastNetworkIP", serverAddress)

                val result = game.directJoinServer(
                    serverAddress,
                    selectedRoomDescription?.uuid2,
                    LoadingContext { messageKey -> updateLoadingMessage(readI18n(messageKey)) },
                )
                selectedRoomDescription = null
                if (result.isSuccess) {
                    withContext(GameMainDispatcher) {
                        if (activeAttemptNumber != joinAttemptNumber || joinCancelled || !game.completeJoinServer()) {
                            false
                        } else {
                            joinNavigationCommitted = true
                            isConnecting = false
                            if (game.state.state == io.github.rwpp.api.State.Menu) {
                                if (navigator.current !is BattleRoomScreen) navigator.push<BattleRoomScreen>()
                            } else if (navigator.current !is GameScreen) navigator.push<GameScreen>()
                            true
                        }
                    }
                } else {
                    val failure = result.exceptionOrNull()
                    withContext(GameMainDispatcher) {
                        if (activeAttemptNumber == joinAttemptNumber && !joinCancelled) {
                            message(
                                when (failure) {
                                    is RoomJoinTimeoutException -> readI18n("rwpp.multiplayer.join.timeout")
                                    else -> failure?.message?.takeIf(String::isNotBlank)
                                        ?: readI18n("rwpp.multiplayer.join.failed")
                                }
                            )
                        }
                    }
                    false
                }
            }
        }

        with(net) {
            LaunchedEffect(Unit) {
                refresh.send(Unit)
                for (u in refresh) {
                    if (isRefreshing) continue
                    throwable = null
                    isRefreshing = true
                    try {
                        val roomList = selectedServerConfig
                        if (isShowingRoomList && roomList != null) {
                            if (roomList.customRoomListProvider == null) {
                                // Show the last successful list immediately; the refresh below
                                // replaces it without ever showing a timeout error.
                                net.cachedRoomList()?.let { cached ->
                                    if (cached.isNotEmpty()) currentViewList = cached
                                }
                                currentViewList = getRoomListFromSourceUrl(
                                    roomList.ip.split(";")
                                )
                            } else {
                                currentViewList = net.roomListProvider[roomList.customRoomListProvider!!]!!()
                            }
                        } else {
                            for (s in allServerData) {
                                if (s.config.type == ServerType.Server) {
                                    launch(Dispatchers.IO) {
                                        s.isLoading = true
                                        s.infoPacket = runCatching {
                                            serverInfoQuery.query(s.config)
                                        }.onFailure {
                                            logger().error(it.stackTraceToString())
                                        }.getOrNull()
                                        s.isLoading = false
                                    }
                                }
                            }
                        }

                    } catch (e: Throwable) {
                        throwable = e
                    }

                    isRefreshing = false
                }
            }
        }


        FilterSurfaceDialog(filterSurfaceDialogVisible) { filterSurfaceDialogVisible = false }
        HostGameDialog(hostDialogVisible, { hostDialogVisible = false }) {
            navigator.push<BattleRoomScreen>()
            game.setUserName(userName)
        }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                AeroPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AeroButton(
                                text = readI18n("rwpp.multiplayer.host"),
                                modifier = Modifier.width(130.dp),
                                clickSound = Sounds.openPopup,
                                onClick = { hostDialogVisible = true }
                            )

                            AeroButton(
                                text = readI18n("rwpp.multiplayer.joinLastGame"),
                                modifier = Modifier.width(160.dp),
                                clickSound = Sounds.openPopup,
                                onClick = {
                                    val lastIp = configIO.getGameConfig<String?>("lastNetworkIP")
                                    if (lastIp != null) {
                                        serverAddress = lastIp
                                        beginJoinAttempt()
                                    }
                                }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isShowingRoomList) {
                                AeroIconButton(
                                    painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
                                    onClick = { isShowingRoomList = false }
                                )
                            } else {
                                AeroIconButton(
                                    painter = rememberVectorPainter(Icons.Default.Add),
                                    onClick = { selectedServerConfig = null; showServerInfoConfig = true }
                                )
                            }

                            AeroIconButton(
                                painter = painterResource(Res.drawable.tune_30),
                                onClick = { filterSurfaceDialogVisible = true },
                            )

                            Box {
                                val requester = remember { FocusRequester() }

                                LaunchedEffect(Unit) {
                                    requester.requestFocus()
                                }

                                AeroIconButton(
                                    painter = rememberVectorPainter(Icons.Default.Refresh),
                                    onClick = { if (!isRefreshing) scope.launch { refresh.trySend(Unit) } },
                                    modifier = Modifier.focusRequester(requester)
                                )

                                val appContext = koinInject<AppContext>()
                                if (appContext.isDesktop()) {
                                    AeroBadge(
                                        text = "SPACE",
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                AeroPanel(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AeroHeader(
                                    title = "MULTIPLAYER LOBBY",
                                    tag = "${currentViewList.size} ONLINE",
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                AeroButton(
                                    text = readI18n("rwpp.common.exit"),
                                    isDanger = true,
                                    onClick = {
                                        // The exit button is also the cache reset: the next visit
                                        // must fetch a fresh list instead of the stored one.
                                        net.clearRoomListCache()
                                        navigator.pop()
                                    },
                                    clickSound = Sounds.closePopup,
                                    modifier = Modifier.width(100.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AeroTextField(
                                    value = userName,
                                    onValueChange = { userName = it },
                                    placeholder = readI18n("rwpp.multiplayer.userName"),
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Person,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.width(180.dp)
                                )

                                AeroTextField(
                                    value = joinServerAddress,
                                    onValueChange = {
                                        joinServerAddress = it
                                        configIO.setGameConfig("lastNetworkIP", it)
                                    },
                                    placeholder = readI18n("rwpp.multiplayer.joinServer"),
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Add,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    trailingIcon = {
                                        AeroIconButton(
                                            painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowForward),
                                            onClick = {
                                                if (joinServerAddress.isNotBlank()) {
                                                    serverAddress = joinServerAddress
                                                    beginJoinAttempt()
                                                }
                                            },
                                            size = 28.dp,
                                            iconSize = 14.dp
                                        )
                                    },
                                    modifier = Modifier.width(360.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            AeroDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            val realList = remember(
                                currentViewList,
                                enableModFilter,
                                playerLimitRange,
                                mapNameFilter,
                                creatorNameFilter,
                                blacklists.size,
                                battleroom
                            ) {
                                currentViewList.filter { room ->
                                    if (blacklists.any { it.uuid == room.uuid }) return@filter false

                                    if (battleroom && room.status == "ingame") return@filter false
                                    if (enableModFilter) {
                                        if (!room.version.contains("mod", true) && room.mods.isBlank()) {
                                            return@filter false
                                        }
                                    }

                                    if (mapNameFilter.isNotBlank()) {
                                        return@filter room.mapName.contains(mapNameFilter, true)
                                    }

                                    if (creatorNameFilter.isNotBlank()) {
                                        return@filter room.creator.contains(creatorNameFilter, true)
                                    }

                                    if (room.playerMaxCount != null) {
                                        return@filter room.playerMaxCount in playerLimitRange || room.playerMaxCount!! > 100
                                    }

                                    true
                                }.sorted.distinctBy { it.uuid }
                            }

                            AnimatedServerConfigInfo(
                                showServerInfoConfig,
                                selectedServerConfig,
                            ) { showServerInfoConfig = false }

                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurface
                            ) {
                                LazyColumnScrollbar(
                                    state = lazyListState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        state = lazyListState
                                    ) {
                                        if (isShowingRoomList) {
                                            if (realList.isEmpty()) {
                                                item {
                                                    Text(
                                                        readI18n("rwpp.multiplayer.roomListEmpty"),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            } else {
                                                RoomListAnimated(realList)
                                            }
                                        } else {
                                            ServerList(reorderableLazyListState, allServerData)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HostGameDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit,
        onHost: () -> Unit,
    ) = AnimatedAlertDialog(
        visible, onDismissRequest = onDismissRequest
    ) { dismiss ->
        AeroPanel(
            modifier = Modifier
                .width(520.dp)
                .padding(10.dp)
                .autoClearFocus()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AeroHeaderWithCloseButton(
                    title = "HOST GAME",
                    tag = "NET_HOST",
                    onDismissRequest = dismiss
                )
                AeroDivider()

                var enableMods by remember { mutableStateOf(false) }
                var hostByProtocol by remember { mutableStateOf(false) }
                var transferMod by remember { mutableStateOf(false) }
                val selectedRoomListHostProtocol =
                    remember(selectedServerConfig) {
                        selectedServerConfig.takeIf { it?.type == ServerType.RoomList }?.customRoomHostProtocol ?: "RCN"
                    }
                val modSize by remember {
                    mutableLongStateOf(
                        GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>()
                            .filter { it.isEnabled }
                            .sumOf { mod ->
                                val path = mod.getSourcePath() ?: mod.path.orEmpty()
                                runCatching { File(path).calculateSize() }.getOrDefault(0L)
                            }
                    )
                }

                LaunchedEffect(enableMods) {
                    if (!enableMods) transferMod = false
                }

                AeroToggleTile(
                    label = readI18n("rwpp.multiplayer.enableMods"),
                    checked = enableMods,
                    onCheckedChange = { enableMods = !enableMods },
                    modifier = Modifier.fillMaxWidth()
                )

                AeroToggleTile(
                    label = readI18n("rwpp.multiplayer.hostByProtocol", selectedRoomListHostProtocol),
                    checked = hostByProtocol,
                    onCheckedChange = { hostByProtocol = !hostByProtocol },
                    modifier = Modifier.fillMaxWidth()
                )

                AeroToggleTile(
                    label = if (modSize > maxModSize) {
                        readI18n(
                            "rwpp.multiplayer.transferModDisabled",
                            SizeUtils.byteToMB(modSize),
                            SizeUtils.byteToMB(maxModSize),
                        )
                    } else {
                        readI18n("rwpp.multiplayer.transferMod")
                    },
                    checked = transferMod,
                    onCheckedChange = { if (enableMods && modSize <= maxModSize) transferMod = !transferMod },
                    modifier = Modifier.fillMaxWidth()
                )

                var password by remember { mutableStateOf("") }
                AeroTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = readI18n("rwpp.multiplayer.password"),
                    enabled = !hostByProtocol,
                    modifier = Modifier.fillMaxWidth()
                )

                var maxPlayer: Int? by remember { mutableStateOf(10) }
                AeroTextField(
                    value = maxPlayer?.toString() ?: "",
                    onValueChange = { maxPlayer = it.toIntOrNull()?.coerceAtMost(100) },
                    placeholder = readI18n("rwpp.multiplayer.room.maxPlayer"),
                    enabled = hostByProtocol,
                    typeInNumberOnly = true,
                    typeInOnlyInteger = true,
                    modifier = Modifier.fillMaxWidth()
                )

                var port by remember { mutableStateOf(configIO.getGameConfig<Int?>("networkPort")) }
                AeroTextField(
                    value = port?.toString() ?: "",
                    onValueChange = {
                        port = it.toIntOrNull()
                        configIO.setGameConfig("networkPort", port ?: 5123)
                    },
                    placeholder = readI18n("rwpp.multiplayer.port"),
                    lengthLimitCount = 5,
                    typeInNumberOnly = true,
                    typeInOnlyInteger = true,
                    enabled = !hostByProtocol,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AeroButton(
                        text = readI18n("rwpp.multiplayer.hostPrivate"),
                        modifier = Modifier.weight(1f),
                        clickSound = Sounds.openPopup,
                        onClick = {
                            dismiss()
                            game.gameRoom.option = RoomOption(transferMod, modSize.toInt())
                            if (hostByProtocol) {
                                serverAddress = net.roomListHostProtocol[selectedRoomListHostProtocol]!!(
                                    maxPlayer?.coerceAtMost(100)?.coerceAtLeast(10) ?: 10, enableMods, false
                                )
                                beginJoinAttempt()
                            } else {
                                onHost()
                                game.hostStartWithPasswordAndMods(
                                    false, password.ifBlank { null }, enableMods,
                                )
                            }
                        }
                    )
                    AeroButton(
                        text = readI18n("rwpp.multiplayer.hostPublic"),
                        modifier = Modifier.weight(1f),
                        clickSound = Sounds.openPopup,
                        onClick = {
                            dismiss()
                            game.gameRoom.option = RoomOption(transferMod, modSize.toInt())
                            if (hostByProtocol) {
                                serverAddress = net.roomListHostProtocol[selectedRoomListHostProtocol]!!(
                                    maxPlayer?.coerceAtMost(100)?.coerceAtLeast(10) ?: 10, enableMods, true
                                )
                                beginJoinAttempt()
                            } else {
                                onHost()
                                game.hostStartWithPasswordAndMods(
                                    true, password.ifBlank { null }, enableMods,
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    fun LazyListScope.RoomListAnimated(
        descriptions: List<RoomDescription>
    ) {
        items(
            count = descriptions.size,
            key = { descriptions[it].uuid }
        ) { index ->
            val desc = descriptions[index]

            val accentColor: Color = MaterialTheme.colorScheme.primary
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()
            val isPressed by interactionSource.collectIsPressedAsState()

            LaunchedEffect(isHovered) {
                if (isHovered) Sounds.playSound(Sounds.hover2)
            }

            val scale by animateFloatAsState(
                targetValue = when {
                    isPressed -> 0.96f
                    isHovered -> 1.02f
                    else -> 1.0f
                },
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
                label = "menuBtnScale"
            )

            val animBgColor by animateColorAsState(
                targetValue = when {
                    isPressed -> accentColor.copy(alpha = 0.35f)
                    isHovered -> accentColor.copy(alpha = 0.2f)
                    else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
                },
                animationSpec = tween(150),
                label = "menuBtnBg"
            )

            val animBorderColor by animateColorAsState(
                targetValue = when {
                    isPressed -> Color.White
                    isHovered -> accentColor
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                },
                animationSpec = tween(150),
                label = "menuBtnBorder"
            )

            val shape = remember { AeroSciFiTheme.ChamferAeroShape(12f) }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(shape)
                    .background(animBgColor)
                    .border(1.dp, animBorderColor, shape)
                    .animateItem()
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .clickable(interactionSource = interactionSource, indication = null) {
                        selectedRoomDescription = desc
                        showJoinRequestDialog = true
                        Sounds.playSound(Sounds.openPopup)
                    }
            ) {
                val color: Color =
                    if (desc.gameVersion != gameVersion) {
                        Color.Gray
                    } else if (desc.isUpperCase) {
                        Color.White
                    } else if (desc.isLocal) {
                        Color(255, 127, 80)
                    } else if (desc.isOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Gray
                    }
                val open: String =
                    if (desc.requiredPassword) {
                        "P"
                    } else if (desc.isLocal) {
                        "L"
                    } else if (desc.isOpen) {
                        "Y"
                    } else {
                        "N"
                    }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        AeroBadge(text = desc.status, color = color)
                        Text(
                            text = desc.creator,
                            color = color,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.3f)
                        )
                        Text(
                            text = desc.mapName.removeSuffix(".tmx"),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.5f)
                        )
                        Text(
                            text = "${desc.playerCurrentCount ?: ""}/${desc.playerMaxCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AeroBadge(text = desc.version, color = MaterialTheme.colorScheme.outline)
                        AeroBadge(
                            text = open,
                            color = if (open == "Y") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun AnimatedServerConfigInfo(
        visible: Boolean,
        serverConfig: ServerConfig?,
        onDismissRequest: () -> Unit,
    ) {
        AnimatedAlertDialog(
            visible,
            onDismissRequest = onDismissRequest
        ) { dismiss ->
            AeroPanel(
                modifier = Modifier
                    .width(480.dp)
                    .padding(10.dp)
                    .autoClearFocus()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AeroHeaderWithCloseButton(
                        title = "SERVER CONFIGURATION",
                        tag = "EDIT",
                        onDismissRequest = dismiss
                    )
                    AeroDivider()

                    var type by remember { mutableStateOf(serverConfig?.type ?: ServerType.Server) }
                    var url by remember { mutableStateOf(serverConfig?.ip ?: "") }
                    var name by remember { mutableStateOf(serverConfig?.name ?: "") }

                    if (serverConfig == null) {
                        AeroDropdownMenu(
                            label = readI18n("rwpp.multiplayer.server.type.label"),
                            items = ServerType.entries.map { serverType ->
                                readI18n("rwpp.multiplayer.server.type.${serverType.name.lowercase()}")
                            },
                            selectedIndex = type.ordinal,
                            onItemSelected = { type = ServerType.entries[it] },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AeroTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = readI18n("rwpp.common.name"),
                        modifier = Modifier.fillMaxWidth()
                    )

                    AeroTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = readI18n("rwpp.multiplayer.server.address"),
                        modifier = Modifier.fillMaxWidth()
                    )

                    AeroButton(
                        text = readI18n("rwpp.multiplayer.room.apply").uppercase(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (serverConfig != null) {
                                serverConfig.ip = url
                                serverConfig.name = name
                            } else {
                                val config = ServerConfig(url, name, type)
                                instance.allServerConfig.add(config)
                                allServerData.add(ComposeServerData(config))
                            }

                            updateServerConfig = !updateServerConfig
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }

    fun LazyListScope.ServerList(
        state: ReorderableLazyListState,
        serverDataList: List<ServerData>
    ) {
        items(
            serverDataList,
            key = { it.hashCode() }
        ) { serverData ->
            ReorderableItem(state, serverData.hashCode()) {
                val interactionSource = remember { MutableInteractionSource() }
                val cardInteractionSource = remember { MutableInteractionSource() }
                val cardHovered by cardInteractionSource.collectIsHoveredAsState()
                val cardPressed by cardInteractionSource.collectIsPressedAsState()
                val cardBorderColor by animateColorAsState(
                    targetValue = when {
                        cardPressed -> Color.White
                        cardHovered -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    },
                    animationSpec = tween(150),
                    label = "serverCardBorder"
                )

                LaunchedEffect(cardHovered) {
                    if (cardHovered) Sounds.playSound(Sounds.hover2)
                }

                AeroPanel(
                    modifier = Modifier
                        .then(Modifier.animateItem())
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .clickable(interactionSource = cardInteractionSource, indication = null) {
                            Sounds.playSound(Sounds.select)
                            if (serverData.config.type == ServerType.Server) {
                                val ip = serverData.config.ip
                                serverAddress = ip
                                configIO.setGameConfig("lastNetworkIP", ip)
                                beginJoinAttempt()
                            } else if (serverData.config.type == ServerType.RoomList) {
                                selectedServerConfig = serverData.config
                                isShowingRoomList = true
                                currentViewList = listOf()
                                refresh.trySend(Unit)
                            }
                        },
                    borderColor = cardBorderColor
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val info = remember(serverData.infoPacket) { serverData.infoPacket }
                            val configName = remember(updateServerConfig) { serverData.config.name }
                            val version = remember(info) { info?.run { version } ?: "" }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = info?.name ?: configName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (version.isNotEmpty()) {
                                    AeroBadge(text = version, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (serverData.config.editable) {
                                    AeroIconButton(
                                        painter = rememberVectorPainter(Icons.Default.Delete),
                                        onClick = {
                                            instance.allServerConfig.remove(serverData.config)
                                            allServerData.remove(serverData)
                                        },
                                        size = 32.dp,
                                        iconSize = 16.dp,
                                        isDanger = true
                                    )

                                    AeroIconButton(
                                        painter = rememberVectorPainter(Icons.Default.Info),
                                        onClick = {
                                            selectedServerConfig = serverData.config
                                            showServerInfoConfig = true
                                        },
                                        size = 32.dp,
                                        iconSize = 16.dp
                                    )
                                }

                                Box(
                                    modifier = Modifier.draggableHandle(
                                        onDragStopped = {
                                            updateServerConfig = !updateServerConfig
                                        },
                                        interactionSource = interactionSource,
                                    )
                                ) {
                                    AeroIconButton(
                                        painter = painterResource(Res.drawable.drag_30),
                                        onClick = {},
                                        size = 32.dp,
                                        iconSize = 16.dp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val iconPainter = remember {
                                runCatching {
                                    serverData.infoPacket?.iconBytes?.let { readPainterByBytes(it) }
                                }.getOrNull()
                            }

                            val checked = remember(selectedDefaultRoomList) {
                                selectedDefaultRoomList == serverData
                            }

                            if (serverData.config.type == ServerType.Server) {
                                Box(
                                    modifier = Modifier.size(80.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = iconPainter ?: painterResource(Res.drawable.error_missingmap),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                                    )
                                    if (serverData.isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            val info = remember(serverData.infoPacket) { serverData.infoPacket }
                            val playerCount = remember(info) { info?.run { "$currentPlayer / $maxPlayerSize" } ?: "" }
                            val description = remember(info) { info?.run { description } ?: "" }
                            val mapName = remember(info) { info?.run { mapName } ?: "" }
                            val mods = remember(info) { info?.run { mods } ?: "" }

                            Column(modifier = Modifier.weight(1f)) {
                                if (serverData.config.type == ServerType.Server) {
                                    if (playerCount.isNotEmpty()) {
                                        Text(
                                            text = readI18n(
                                                "rwpp.multiplayer.server.playersAndMap",
                                                playerCount,
                                                mapName
                                            ),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (description.isNotEmpty()) {
                                        Text(
                                            text = description,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (mods.isNotBlank()) {
                                        Text(
                                            text = readI18n("rwpp.multiplayer.server.mods", mods),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                } else if (serverData.config.type == ServerType.RoomList) {
                                    AeroToggleTile(
                                        label = readI18n("rwpp.multiplayer.useAsDefaultList"),
                                        checked = checked,
                                        onCheckedChange = { itChecked ->
                                            if (itChecked) {
                                                instance.allServerConfig.forEach { c -> c.useAsDefaultList = false }
                                                serverData.config.useAsDefaultList = true
                                                selectedDefaultRoomList = serverData
                                            } else {
                                                selectedDefaultRoomList = null
                                                serverData.config.useAsDefaultList = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun resetFilter() {
        enableModFilter = false
        playerLimitRange = 0..100
        mapNameFilter = ""
        creatorNameFilter = ""
    }

    @Composable
    fun FilterSurfaceDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit
    ) {
        AnimatedAlertDialog(
            visible, onDismissRequest = onDismissRequest
        ) { dismiss ->
            AeroPanel(
                modifier = Modifier
                    .fillMaxSize(generalProportion())
                    .padding(10.dp)
                    .autoClearFocus()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AeroHeaderWithCloseButton(
                        title = "FILTER",
                        tag = "FILTER",
                        onDismissRequest = dismiss
                    )
                    AeroDivider()

                    var showBlacklistDialog by remember { mutableStateOf(false) }
                    BlacklistTargetDialog(showBlacklistDialog) { showBlacklistDialog = false }

                    AeroTextField(
                        value = mapNameFilter,
                        onValueChange = { mapNameFilter = it },
                        placeholder = readI18n("rwpp.multiplayer.filter.gameMapNameFilter"),
                        modifier = Modifier.fillMaxWidth()
                    )

                    AeroTextField(
                        value = creatorNameFilter,
                        onValueChange = { creatorNameFilter = it },
                        placeholder = readI18n("rwpp.multiplayer.filter.creatorNameFilter"),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        var range by remember(playerLimitRange) { mutableStateOf(playerLimitRange) }
                        Text(
                            text = "${readI18n("rwpp.multiplayer.filter.playerLimitRange")}: ${range.first} ~ ${range.last}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        RangeSlider(
                            valueRange = 0f..100f,
                            modifier = Modifier.fillMaxWidth(),
                            value = range.first.toFloat()..range.last.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            onValueChange = { range = it.start.roundToInt()..it.endInclusive.roundToInt() },
                            onValueChangeFinished = { playerLimitRange = range }
                        )
                    }

                    AeroToggleTile(
                        label = readI18n("rwpp.multiplayer.enableMods"),
                        checked = enableModFilter,
                        onCheckedChange = { enableModFilter = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    AeroToggleTile(
                        label = readI18n("rwpp.multiplayer.battleroom"),
                        checked = battleroom,
                        onCheckedChange = { battleroom = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AeroButton(
                            text = readI18n("rwpp.multiplayer.filter.blacklist"),
                            modifier = Modifier.weight(1f),
                            onClick = { showBlacklistDialog = true }
                        )

                        AeroButton(
                            text = readI18n("rwpp.multiplayer.filter.reset"),
                            modifier = Modifier.weight(1f),
                            onClick = { resetFilter() }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun BlacklistTargetDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit,
    ) {
        var showBlacklistInfo by remember { mutableStateOf(false) }
        var infoSelectedIndex by remember { mutableIntStateOf(0) }
        var addMode by remember { mutableStateOf(false) }

        DisposableEffect(key1 = visible) {
            onDispose {
                if (!visible) {
                    showBlacklistInfo = false
                    addMode = false
                }
            }
        }

        AnimatedAlertDialog(
            visible = visible,
            onDismissRequest = { onDismissRequest() },
        ) { dismiss ->
            AeroPanel(
                modifier = Modifier.fillMaxSize(generalProportion())
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AeroHeaderWithCloseButton(
                            title = "BLACKLIST",
                            tag = "LIST",
                            onDismissRequest = dismiss
                        )
                        AeroDivider()

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(count = blacklists.size) { index ->
                                val blacklist = blacklists[index]
                                AeroPanel(
                                    modifier = Modifier
                                        .then(if (koinInject<Settings>().enableAnimations) Modifier.animateItem() else Modifier)
                                        .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = blacklist.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = blacklist.uuid,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            AeroIconButton(
                                                painter = rememberVectorPainter(Icons.Default.Delete),
                                                onClick = {
                                                    blacklists.removeAt(index)
                                                    infoSelectedIndex = 0
                                                },
                                                size = 32.dp,
                                                iconSize = 16.dp,
                                                isDanger = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun JoinServerRequestDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit,
        roomDescription: RoomDescription?,
        blacklists: SnapshotStateList<Blacklist>,
        onJoin: (dismiss: () -> Unit) -> Unit,
    ) {
        roomDescription ?: return
        AnimatedAlertDialog(
            visible, onDismissRequest = onDismissRequest
        ) { dismiss ->
            AeroPanel(
                modifier = Modifier
                    .width(480.dp)
                    .padding(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AeroHeaderWithCloseButton(
                        title = "JOIN SERVER?",
                        tag = "ROOM",
                        onDismissRequest = dismiss
                    )
                    AeroDivider()

                    Text(
                        text = readI18n(
                            "rwpp.multiplayer.roomInfo",
                            roomDescription.creator,
                            roomDescription.mapName,
                            roomDescription.playerCurrentCount?.toString() ?: "",
                            roomDescription.playerMaxCount?.toString() ?: "",
                            roomDescription.version,
                            roomDescription.mods
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AeroButton(
                            text = readI18n("rwpp.multiplayer.addToBlackList"),
                            modifier = Modifier.weight(1f),
                            isDanger = true,
                            onClick = {
                                blacklists.add(
                                    Blacklist(
                                        "${roomDescription.creator}: ${roomDescription.mapName}",
                                        roomDescription.uuid
                                    )
                                )
                                dismiss()
                            }
                        )

                        AeroButton(
                            text = readI18n("rwpp.multiplayer.join"),
                            modifier = Modifier.weight(1f),
                            onClick = { onJoin(dismiss) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun WelcomeMessageAdmittingDialog(
        visible: Boolean,
        onDismissRequest: () -> Unit,
    ) {
        val settings = koinInject<Settings>()

        AnimatedAlertDialog(
            visible, onDismissRequest = onDismissRequest, enableDismiss = false
        ) { dismiss ->
            AeroPanel(
                modifier = Modifier
                    .width(420.dp)
                    .padding(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AeroHeader(title = "WELCOME MESSAGE", tag = "INFO")
                    AeroDivider()

                    Text(
                        text = readI18n("rwpp.multiplayer.admitting").trimIndent(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AeroButton(
                            text = readI18n("rwpp.common.yes"),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                settings.showWelcomeMessage = true
                                dismiss()
                            }
                        )

                        AeroButton(
                            text = readI18n("rwpp.common.no"),
                            modifier = Modifier.weight(1f),
                            isDanger = true,
                            onClick = {
                                settings.showWelcomeMessage = false
                                dismiss()
                            }
                        )
                    }
                }
            }
        }
    }

}
