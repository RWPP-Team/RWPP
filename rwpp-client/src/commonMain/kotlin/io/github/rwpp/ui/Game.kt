/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.AppContext
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.logging.logger
import io.github.rwpp.appKoin
import io.github.rwpp.config.HudComponentData
import io.github.rwpp.config.HudComponentDataList
import io.github.rwpp.config.Settings
import io.github.rwpp.event.OnDispose
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.game.hotkeys.NativeGameHotkeys
import io.github.rwpp.game.hotkeys.SelectionCameraFollowController
import io.github.rwpp.game.hud.AeroDamageSignals
import io.github.rwpp.game.hud.normalizeHudComponents
import io.github.rwpp.game.room.nativePauseMenuOptions
import io.github.rwpp.hud.*
import io.github.rwpp.logic.hud.GameUiPauseState
import io.github.rwpp.logic.hud.NativeHudVisibility
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import com.corrodinggames.rts.game.units.Unit as GameUnit

private const val SANDBOX_HUD_ID = "sandbox_editor"

@Single
class GameScreen : Screen {
    val dataListInstance by appKoin.inject<HudComponentDataList>()
    val dataList by lazy {
        dataListInstance
            .hudComponentDataList
            .toMutableStateList()
            .also {
                // Add the touch panel once. Re-checking for its absence recreated it after the
                // user removed it from the HUD editor on every new game screen.
                if (appKoin.get<AppContext>().isAndroid() && !dataListInstance.touchControlsInitialized) {
                    if (it.none { data -> data.id == "touch_selection_controls" }) {
                        it.add(
                            HudComponentData(
                                "touch_selection_controls", 0f, 0f,
                                relativeX = 0.02f,
                                relativeY = 0.88f,
                            )
                        )
                    }
                    dataListInstance.touchControlsInitialized = true
                }
                normalizeHudComponents(it)
                dataListInstance.hudComponentDataList = it
            }
    }
    var selectedUnits by mutableStateOf<List<GameUnit>>(emptyList())
    var isEditMode by mutableStateOf(false)
    var showPauseMenu by mutableStateOf(false)
    private var saveDialog by mutableStateOf<Boolean?>(null)
    private var hudSettingsRevision by mutableIntStateOf(0)
    private val hideOriginalHud: Boolean
        get() {
            hudSettingsRevision
            return appKoin.get<Settings>().hideOriginalHud
        }

    val availableTemplates: MutableMap<String, HudComponentTemplate> = mutableStateMapOf(
        "quick_selection" to HudComponentTemplate(
            readI18n("rwpp.hud.quickSelect.title"),
            300f,
            68f
        ) { AeroQuickSelectionPanel() },
        "unit_groups" to HudComponentTemplate(readI18n("rwpp.hud.groups.title"), 360f, 56f) { AeroGroupsPanel() },
        "observer_statistics" to HudComponentTemplate(
            readI18n("rwpp.observer.statistics"), 268f, 480f, compactHeight = true,
            mode = HudComponentMode.OBSERVER,
        ) { AeroObserverStatistics(visible = !showPauseMenu && (hideOriginalHud || isEditMode)) },
        "telemetry" to HudComponentTemplate(
            readI18n("rwpp.hud.template.telemetry"),
            // Mod resource rows share this readout. The height cap must leave room for them,
            // otherwise the compact template clips every row below the credit line.
            420f,
            260f,
            compactHeight = true,
            compactWidth = true
        ) { AeroGameTelemetry() },
        "chat" to HudComponentTemplate(
            readI18n("rwpp.hud.template.chat"),
            330f,
            120f,
            compactHeight = true,
            compactWidth = true
        ) { AeroGameMessages(false) },
        "events" to HudComponentTemplate(
            readI18n("rwpp.hud.template.events"),
            330f,
            120f,
            compactHeight = true,
            compactWidth = true
        ) { AeroGameMessages(true) },
        "hud_console_vertical" to HudComponentTemplate(
            readI18n("rwpp.hud.template.commandConsole"),
            240f,
            720f,
            fillHeight = true
        ) {
            AeroCommandConsole()
        },
        SANDBOX_HUD_ID to HudComponentTemplate(
            readI18n("rwpp.hud.component.sandboxEditor"),
            340f,
            120f,
            compactHeight = true,
            mode = HudComponentMode.SANDBOX,
        ) { SandboxEditorHud() }
    ).apply {
        if (appKoin.get<AppContext>().isAndroid()) {
            put(
                "touch_selection_controls",
                HudComponentTemplate(
                    readI18n("rwpp.hud.component.touchControls"),
                    220f,
                    220f,
                    compactHeight = true,
                    compactWidth = true,
                ) { AeroTouchSelectionControls() }
            )
        }
    }

    fun setOriginalHudHidden(hidden: Boolean) {
        appKoin.get<Settings>().hideOriginalHud = hidden
        hudSettingsRevision++
        NativeHudVisibility.update()
        if (hidden) {
            fun add(id: String, x: Float, y: Float) {
                if (dataList.none { it.id == id }) dataList.add(
                    HudComponentData(
                        id, 0f, 0f,
                        relativeX = x, relativeY = y
                    )
                )
            }
            if (dataList.none { it.id == "hud_console_vertical" }) add("hud_console_vertical", 1f, 0f)
            add("quick_selection", 0.02f, 0.98f)
            add("unit_groups", 0.48f, 0.98f)
            add("telemetry", 0.06f, 0.01f)
            add("chat", 0.02f, 0.28f)
            add("events", 0.5f, 0.10f)
            add("observer_statistics", 0.70f, 0.16f)
        }
        appKoin.get<io.github.rwpp.api.config.ConfigIO>().saveAllConfig()
    }

    private fun isSelectionUnchanged(current: List<GameUnit>, engineList: List<GameUnit>): Boolean {
        if (current.size != engineList.size) return false
        for (i in current.indices) {
            if (current[i] !== engineList[i]) return false
        }
        return true
    }

    @Composable
    override fun Content() {
        HudViewport { GameContent() }
    }

    @Composable
    private fun GameContent() {
        val game = koinInject<Game>()
        val uiScope = rememberCoroutineScope()
        val navigator = LocalScreenNavigator.current
        var interfaceHidden by remember { mutableStateOf(false) }
        val hotkeySettings = remember { appKoin.get<Settings>() }
        var showPerformance by remember { mutableStateOf(hotkeySettings.showPerformanceUsage) }
        var blueprintLibraryOpen by remember { mutableStateOf(false) }
        val hotkeysBlocked =
            rememberUpdatedState(
                showPauseMenu || isEditMode || saveDialog != null || UI.showPlayerList || blueprintLibraryOpen
            )
        // A destroyed gameplay screen must not leave the engine paused; the flag also decides
        // whether the native mouse may be released while a Compose screen owns the window.
        DisposableEffect(showPauseMenu, isEditMode, saveDialog, blueprintLibraryOpen) {
            GameUiPauseState.blocked = showPauseMenu || isEditMode || saveDialog != null || blueprintLibraryOpen
            onDispose { GameUiPauseState.blocked = false }
        }
        DisposableEffect(hotkeySettings) {
            // Poll native input on the dispatcher owner without allocating a coroutine from the
            // display-frame callback. The settings/state snapshots remain live across recomposes.
            val updateHotkeys = Runnable {
                NativeGameHotkeys.update(hotkeySettings, hotkeysBlocked.value || isShowSendMessageDialog)
            }
            GameMainDispatcher.addUpdateTask(updateHotkeys)
            onDispose {
                GameMainDispatcher.removeUpdateTask(updateHotkeys)
                GameScope.launch {
                    io.github.rwpp.game.hotkeys.NativeTacticalActions.reset()
                    io.github.rwpp.game.hud.OverflowProductionQueue.clear()
                }
            }
        }
        var hudMode by remember { mutableStateOf(HudComponentMode.ALWAYS) }
        var sandboxHudSeeded by remember { mutableStateOf(false) }
        OnGameUpdate {
            showPerformance = hotkeySettings.showPerformanceUsage
            val engine = GameEngine.getInstance()
            interfaceHidden = engine.cameraScrollActive
            NativeHudVisibility.update()
            @Suppress("UNCHECKED_CAST")
            val engineUnits = engine.interfaceEngine.selectedUnits as List<GameUnit>
            if (!isSelectionUnchanged(selectedUnits, engineUnits)) {
                selectedUnits = engineUnits.toList()
                CommandHover.current.value = null
            }
            // Only the components that belong to the running session are offered in the editor,
            // so the list cannot mix observer, sandbox and ordinary battle modules.
            val nextMode = when {
                io.github.rwpp.game.sandbox.sandboxUnitDataUiAllowed(engine) -> HudComponentMode.SANDBOX
                engine.replayEngine.isPlayingReplay || io.github.rwpp.game.hud.isAeroLiveObserver(engine) ->
                    HudComponentMode.OBSERVER

                else -> HudComponentMode.ALWAYS
            }
            if (nextMode != hudMode) hudMode = nextMode
            if (hudMode == HudComponentMode.SANDBOX && !sandboxHudSeeded) {
                sandboxHudSeeded = true
                if (dataList.none { it.id == SANDBOX_HUD_ID }) {
                    dataList.add(
                        HudComponentData(
                            SANDBOX_HUD_ID, 0f, 0f, relativeX = 0.02f, relativeY = 0.62f
                        )
                    )
                }
            }
        }
        val activeTemplates = remember(hudMode) {
            availableTemplates.filterValues {
                it.mode == HudComponentMode.ALWAYS || it.mode == hudMode
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                SelectionCameraFollowController.stop()
                isEditMode = false
                showPauseMenu = false
                NativeHudVisibility.hidden = false
                NativeHudVisibility.aeroSelection = false
                NativeHudVisibility.aeroBars = false
                NativeHudVisibility.aeroOrders = false
                NativeHudVisibility.aeroRallyPoints = false
                AeroDamageSignals.enabled = false
                AeroDamageSignals.clear()
                CommandPointer.inPanel = false
            }
        }

        LaunchedEffect(showPauseMenu) {
            if (showPauseMenu) HudQueuePopupTouch.dismiss()
            logger().info("showPauseMenu: $showPauseMenu multiplayer: ${game.state.multiplayer}")
            if (!game.state.multiplayer) {
                game.gameRoom.pauseOrResumeGame(showPauseMenu)
            }
        }

        GlobalEventChannel.filter(KeyboardEvent::class).OnDispose {
            subscribeAlways { e ->
                uiScope.launch {
                    if (e.keyEvent.key == Key.Escape && e.keyEvent.type == KeyEventType.KeyDown) {
                        if (UI.showPlayerList) return@launch // The modal owns this Escape.
                        if (interfaceHidden) {
                            GameScope.launch {
                                GameEngine.getInstance().cameraScrollActive = false
                            }
                        } else if (isEditMode) {
                            isEditMode = false
                            appKoin.get<io.github.rwpp.api.config.ConfigIO>().saveAllConfig()
                        } else if (saveDialog != null) saveDialog = null
                        else showPauseMenu = !showPauseMenu
                    }
                }
            }
        }

        val components = dataList.map { data ->
            key(data.instanceId) { remember { HudComponentState(data) } }
        }
        if (!interfaceHidden) {
            AeroWorldEffects()
            HudEditor(
                components,
                isEditMode,
                { isEditMode = it; if (!it) appKoin.get<io.github.rwpp.api.config.ConfigIO>().saveAllConfig() },
                { dataList.add(it) },
                { dataList.remove(it.data) },
                activeTemplates,
                onSaveConfig = { appKoin.get<io.github.rwpp.api.config.ConfigIO>().saveAllConfig() },
                onImportLayout = { imported ->
                    dataList.clear()
                    dataList.addAll(imported)
                    appKoin.get<io.github.rwpp.api.config.ConfigIO>().saveAllConfig()
                },
                layoutComponentIds = availableTemplates.keys,
            )

            CommandTooltipLayer()

        }

        if (hideOriginalHud && !showPauseMenu && !isEditMode && !interfaceHidden) Box(Modifier.fillMaxSize()) {
            AeroIconButton(
                rememberVectorPainter(Icons.Default.Menu), { showPauseMenu = true },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                contentDescription = readI18n("rwpp.common.menu"), size = 32.dp, iconSize = 18.dp,
                animated = appKoin.get<Settings>().hudAnimations
            )
        }

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 52.dp)) {
                AeroReplayControls(visible = !showPauseMenu && !isEditMode && !interfaceHidden)
            }
        }

        DeveloperPerformanceOverlay(showPerformance && !showPauseMenu && !interfaceHidden)
        BuildingPlanningFrame(visible = !showPauseMenu && !isEditMode && !interfaceHidden)
        BlueprintPlacementToolbar(
            visible = !showPauseMenu && !isEditMode && !interfaceHidden,
            libraryOpen = blueprintLibraryOpen,
            onLibraryOpenChanged = { blueprintLibraryOpen = it },
        )

        // showImportantScreenMessage and friends are painted by the Aero HUD while the native one is hidden.
        AeroScreenMessage(visible = hideOriginalHud && !showPauseMenu && !isEditMode && !interfaceHidden)

        AnimatedVisibility(
            visible = showPauseMenu,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            PauseMenuOverlay(
                onSave = { saveDialog = false },
                onLoad = { saveDialog = true },
                onResume = {
                    showPauseMenu = false
                },
                onEditMode = {
                    isEditMode = true
                    showPauseMenu = false
                },
                onSettings = {
                    navigator.push<SettingsScreen>("RWPP")
                },
                onExit = {
                    showPauseMenu = false
                    game.gameRoom.disconnect()
                }
            )
        }
        saveDialog?.let { load ->
            GameSaveDialog(load, onDismiss = { saveDialog = null }, onLoaded = {
                saveDialog = null
                showPauseMenu = false
            })
        }
    }

    @Composable
    fun OnGameUpdate(onUpdate: () -> Unit) {
        val currentUpdate by rememberUpdatedState(onUpdate)
        DisposableEffect(Unit) {
            val task = Runnable {
                val engine = GameEngine.getInstance() ?: return@Runnable
                synchronized(engine.gameUpdateLock) {
                    if (io.github.rwpp.graphics.nativeWorldFrame(engine) != null) currentUpdate()
                }
            }
            val registration = registerHudFrame(task)
            onDispose { registration.close() }
        }
    }

    @Composable
    fun PauseMenuOverlay(
        modifier: Modifier = Modifier,
        onResume: () -> Unit = {},
        onSave: () -> Unit = {},
        onLoad: () -> Unit = {},
        onSettings: () -> Unit = {},
        onEditMode: () -> Unit = {},
        onExit: () -> Unit = {}
    ) {
        val config = koinInject<io.github.rwpp.api.config.ConfigIO>()
        var isMuteAudio by remember { mutableStateOf(!config.getGameConfig<Boolean>("enableSounds")) }
        var isShowFps by remember { mutableStateOf(config.getGameConfig<Boolean>("showFps")) }
        var masterVolume by remember { mutableFloatStateOf(config.getGameConfig("masterVolume")) }
        var interfaceVolume by remember { mutableFloatStateOf(config.getGameConfig("interfaceVolume")) }

        var options by remember { mutableStateOf(nativePauseMenuOptions(GameEngine.getInstance())) }
        OnGameUpdate { options = nativePauseMenuOptions(GameEngine.getInstance()) }
        var confirmation by remember { mutableStateOf<String?>(null) }
        var exportMapVisible by remember { mutableStateOf(false) }
        if (exportMapVisible) NetworkMapExportDialog(koinInject<Game>()) { exportMapVisible = false }
        AnimatedAlertDialog(confirmation != null, { confirmation = null }) { dismiss ->
            AeroPanel(Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        readI18n(if (confirmation == "room") "rwpp.ingame.returnRoomNotice" else "rwpp.ingame.surrenderNotice"),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AeroButton(readI18n("rwpp.common.confirm"), onClick = {
                            val action = confirmation
                            dismiss(); onResume()
                            GameScope.launch {
                                val engine = GameEngine.getInstance()
                                val current = nativePauseMenuOptions(engine)
                                if (action == "room" && current.returnRoom) engine.networkEngine.scheduleReturnToBattleroomSoon()
                                else if (action == "surrender" && current.surrender) engine.networkEngine.sendChatMessage(
                                    "-surrender"
                                )
                            }
                        }, modifier = Modifier.weight(1f))
                        AeroButton(
                            readI18n("rwpp.common.cancel"), onClick = dismiss,
                            modifier = Modifier.weight(1f), clickSound = Sounds.cancel
                        )
                    }
                }
            }
        }

        val transition = rememberInfiniteTransition(label = "pulse")
        val alphaPulse by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
            label = "pulseAlpha"
        )

        val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridSpacing = 40.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += gridSpacing
                }
            }

            AeroPanel(
                modifier = Modifier
                    .width(380.dp)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alphaPulse))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = readI18n("rwpp.ingame.systemPaused").uppercase(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        AeroBadge(text = "ESC")
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = readI18n("rwpp.ingame.missionTime", GameEngine.getInstance().gameTimeMillis / 1000),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AeroToggleTile(
                            label = readI18n("rwpp.ingame.muteSound"),
                            checked = isMuteAudio,
                            onCheckedChange = {
                                isMuteAudio = it; config.setGameConfig(
                                "enableSounds",
                                !it
                            ); config.saveAllConfig()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        AeroToggleTile(
                            label = readI18n("rwpp.ingame.showFps"),
                            checked = isShowFps,
                            onCheckedChange = {
                                isShowFps = it; config.setGameConfig(
                                "showFps",
                                it
                            ); config.saveAllConfig()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    var hideHud by remember { mutableStateOf(appKoin.get<Settings>().hideOriginalHud) }
                    AeroSettingToggle(readI18n("rwpp.ingame.hideNativeHud"), hideHud, onCheckedChange = {
                        hideHud = it
                        setOriginalHudHidden(it)
                    })
                    AeroSettingSlider(
                        value = masterVolume,
                        onValueChange = { masterVolume = it; config.setGameConfig("masterVolume", it) },
                        label = readI18n("rwpp.ingame.masterVolume")
                    )
                    AeroSettingSlider(
                        value = interfaceVolume,
                        onValueChange = { interfaceVolume = it; config.setGameConfig("interfaceVolume", it) },
                        label = readI18n("rwpp.ingame.interfaceVolume")
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    AeroDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AeroButton(
                            text = readI18n("rwpp.ingame.continueGame"),
                            subText = readI18n("rwpp.ingame.resume").uppercase(),
                            onClick = onResume,
                            clickSound = Sounds.closePopup,
                            accentColor = MaterialTheme.colorScheme.primary
                        )

                        if (options.chat) AeroButton(readI18n("menus.ingame.chat"), onClick = {
                            onResume(); isSendingTeamChat = false; showSendMessageDialog()
                        }, clickSound = Sounds.openPopup)
                        if (options.players) AeroButton(readI18n("menus.ingame.players"), onClick = {
                            onResume()
                            UI.showPlayerList = true
                        }, clickSound = Sounds.openPopup)
                        if (options.chat || GameEngine.getInstance().networkEngine.sandboxMode ||
                            GameEngine.getInstance().debugEditorMode
                        ) AeroButton(readI18n("rwpp.mapExport.action"), onClick = {
                            exportMapVisible = true
                        }, clickSound = Sounds.openPopup)
                        if (options.returnRoom) AeroButton(
                            readI18n("menus.ingame.multiplayerClose.returnToBattleroom"),
                            onClick = { confirmation = "room" }, clickSound = Sounds.openPopup
                        )
                        if (options.surrender) AeroButton(
                            readI18n("menus.ingame.surrender"),
                            onClick = { confirmation = "surrender" }, isDanger = true, clickSound = Sounds.openPopup
                        )
                        if (options.briefing) AeroButton(readI18n("menus.ingame.briefing"), onClick = {
                            onResume()
                            GameScope.launch {
                                GameEngine.getInstance().triggerEngine?.briefingText?.let { UI.showWarning(it.resolve()) }
                            }
                        }, clickSound = Sounds.openPopup)
                        if (options.hideInterface) AeroButton(readI18n("menus.ingame.hideInterface"), onClick = {
                            GameEngine.getInstance().cameraScrollActive = true; onResume()
                        })

                        if (options.saveLoad) {
                            AeroButton(
                                text = readI18n("rwpp.ingame.saveGame"),
                                subText = readI18n("rwpp.common.save").uppercase(),
                                onClick = onSave
                            )
                            AeroButton(
                                text = readI18n("rwpp.ingame.loadGame"),
                                subText = readI18n("rwpp.common.load").uppercase(),
                                clickSound = Sounds.openPopup,
                                onClick = onLoad
                            )
                        }

                        AeroButton(
                            text = readI18n("rwpp.menu.settings"),
                            subText = readI18n("rwpp.menu.settings").uppercase(),
                            clickSound = Sounds.openPopup,
                            onClick = onSettings
                        )

                        AeroButton(
                            text = readI18n("rwpp.ingame.editMode"),
                            subText = readI18n("rwpp.ingame.editMode").uppercase(),
                            clickSound = Sounds.openPopup,
                            onClick = onEditMode
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        AeroButton(
                            text = readI18n("rwpp.common.exit"),
                            subText = readI18n("rwpp.common.exit").uppercase(),
                            isDanger = true,
                            clickSound = Sounds.closePopup,
                            onClick = onExit
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "RWPP / AERO HUD",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }

}
