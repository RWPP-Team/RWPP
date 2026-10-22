/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.appKoin
import io.github.rwpp.config.HudComponentDataList
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.event.OnDispose
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.api.game.Game
import com.corrodinggames.rts.game.units.Unit as GameUnit
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.actions.SpecialActionCategory
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.game.units.UnitTypeInterface
import io.github.rwpp.hud.HudComponentState
import io.github.rwpp.hud.HudComponentTemplate
import io.github.rwpp.hud.HudEditor
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import io.github.rwpp.api.logging.logger

@Single
class GameScreen : Screen {
    val dataListInstance by appKoin.inject<HudComponentDataList>()
    val dataList by lazy {
        dataListInstance
            .hudComponentDataList
            .toMutableStateList()
            .also { dataListInstance.hudComponentDataList = it }
    }
    var selectedUnits by mutableStateOf<List<GameUnit>>(emptyList())
    var isEditMode by mutableStateOf(false)
    var showPauseMenu by mutableStateOf(false)

    val availableTemplates: MutableMap<String, HudComponentTemplate> = mutableStateMapOf(
        "hud_console" to HudComponentTemplate("Hud Console (Horizontal)") {
            SciFiRtsHudConsole(
                selectedUnits,
                { _, _ -> }
            )
        },
        "hud_console_vertical" to HudComponentTemplate("Hud Console (Vertical)") {
            SciFiRtsHudConsoleVertical(
                selectedUnits,
                { _, _ -> }
            )
        }
    )

    private fun isSelectionUnchanged(current: List<GameUnit>, engineList: List<GameUnit>): Boolean {
        if (current.size != engineList.size) return false
        for (i in current.indices) {
            if (current[i] !== engineList[i]) return false
        }
        return true
    }

    @Composable
    override fun Content() {
        val game = koinInject<Game>()
        val navigator = LocalScreenNavigator.current
        OnGameUpdate {
            @Suppress("UNCHECKED_CAST")
            val engineUnits = GameEngine.getInstance().interfaceEngine.selectedUnits.toList() as List<GameUnit>
            if (!isSelectionUnchanged(selectedUnits, engineUnits)) {
                selectedUnits = engineUnits.toList()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                isEditMode = false
                showPauseMenu = false
            }
        }

        LaunchedEffect(showPauseMenu) {
            logger().info("showPauseMenu: $showPauseMenu multiplayer: ${game.state.multiplayer}")
            if (!game.state.multiplayer) {
                game.gameRoom.pauseOrResumeGame(showPauseMenu)
            }
        }

        GlobalEventChannel.filter(KeyboardEvent::class).OnDispose {
            subscribeAlways { e ->
                if (e.keyEvent.key == Key.Escape && e.keyEvent.type == KeyEventType.KeyDown) {
                    showPauseMenu = !showPauseMenu
                }
            }
        }

        val components = remember(dataList.size) {
            dataList.map { HudComponentState(it) }
        }
        HudEditor(
            components,
            isEditMode,
            { isEditMode = it },
            { dataList.add(it) },
            { dataList.remove(it.data) },
            availableTemplates
        )

        AnimatedVisibility(
            visible = showPauseMenu,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            PauseMenuOverlay(
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
    }

    @Composable
    fun OnGameUpdate(onUpdate: () -> Unit) {
        DisposableEffect(Unit) {
            val task = Runnable { onUpdate() }
            logger().info("added task: $task")
            GameMainDispatcher.addUpdateTask(task)
            onDispose {
                GameMainDispatcher.removeUpdateTask(task)
            }
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
        var isMuteAudio by remember { mutableStateOf(false) }
        var isShowFps by remember { mutableStateOf(true) }
        var masterVolume by remember { mutableFloatStateOf(0.8f) }

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
                        .padding(24.dp),
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
                                text = "SYSTEM PAUSED",
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
                        text = "SECTOR 07 — MISSION TIME 00:42:19",
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
                            label = "静音音效(无效)",
                            checked = isMuteAudio,
                            onCheckedChange = { isMuteAudio = it },
                            modifier = Modifier.weight(1f)
                        )
                        AeroToggleTile(
                            label = "显示 FPS(无效)",
                            checked = isShowFps,
                            onCheckedChange = { isShowFps = it },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    AeroSettingSlider(
                        value = masterVolume,
                        onValueChange = { masterVolume = it },
                        label = "主音量调节(无效)"
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    AeroDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AeroButton(
                            text = readI18n("rwpp.ingame.continueGame"),
                            subText = "RESUME",
                            onClick = onResume,
                            clickSound = Sounds.closePopup,
                            accentColor = MaterialTheme.colorScheme.primary
                        )

                        AeroButton(
                            text = "保存游戏(无效)",
                            subText = "SAVE",
                            onClick = onSave
                        )

                        AeroButton(
                            text = "读取存档(无效)",
                            subText = "LOAD",
                            onClick = onLoad
                        )

                        AeroButton(
                            text = "设置",
                            subText = "SETTINGS",
                            clickSound = Sounds.openPopup,
                            onClick = onSettings
                        )

                        AeroButton(
                            text = "编辑模式",
                            subText = "EDIT MODE",
                            clickSound = Sounds.openPopup,
                            onClick = onEditMode
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        AeroButton(
                            text = "退出",
                            subText = "EXIT",
                            isDanger = true,
                            clickSound = Sounds.closePopup,
                            onClick = onExit
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "AERO SCI-FI CORE v2.4.0 — BUILD 2026.08",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }

    class SciFiChamferShape(private val cornerSize: Float = 24f) : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            val path = Path().apply {
                moveTo(cornerSize, 0f)
                lineTo(size.width - cornerSize, 0f)
                lineTo(size.width, cornerSize)
                lineTo(size.width, size.height - cornerSize)
                lineTo(size.width - cornerSize, size.height)
                lineTo(cornerSize, size.height)
                lineTo(0f, size.height - cornerSize)
                lineTo(0f, cornerSize)
                close()
            }
            return Outline.Generic(path)
        }
    }

    val SciFiCyan = Color(0xFF00F0FF)
    val SciFiGold = Color(0xFFFFD700)
    val SciFiOrange = Color(0xFFFF8C00)
    val SciFiRed = Color(0xFFFF3366)
    val SciFiDarkBg = Color(0xDC091018)
    val SciFiDisabled = Color(0x444A5568)

    @Composable
    fun SciFiActionButton(
        action: SpecialAction,
        targetUnit: GameUnit,
        onActionClick: (SpecialAction) -> Unit,
        modifier: Modifier = Modifier
    ) {
        var progress by remember { mutableFloatStateOf(0f) }
        var isAvailable by remember { mutableStateOf(false) }
        var isActivated by remember { mutableStateOf(false) }
        var affordableCount by remember { mutableIntStateOf(0) }

        OnGameUpdate {
            progress = action.getProgress(targetUnit).coerceIn(0f, 1f)
            isAvailable = action.isAvailableForUnit(targetUnit)
            isActivated = action.isGuiBlinkingForUnit(targetUnit)
            affordableCount = action.getCountForUnit(targetUnit, false)
        }

        val cost = action.getPriceValue()
        val isAffordable = cost <= 0 || affordableCount > 0

        val transition = rememberInfiniteTransition(label = "glow")
        val alphaGlow by transition.animateFloat(
            initialValue = 0.4f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "alpha"
        )

        val mainColor = when {
            !isAvailable || !isAffordable -> SciFiDisabled
            action.isHighPriorityQueueItem() -> SciFiGold
            isActivated -> SciFiOrange
            else -> SciFiCyan
        }

        val shape = remember { SciFiChamferShape(12f) }

        Box(
            modifier = modifier
                .size(72.dp)
                .clip(shape)
                .background(SciFiDarkBg)
                .border(
                    width = if (action.isHighPriorityQueueItem() || isActivated) 2.dp else 1.dp,
                    color = if (isAvailable) mainColor.copy(alpha = alphaGlow) else SciFiDisabled,
                    shape = shape
                )
                .clickable(enabled = isAvailable && isAffordable) {
                    onActionClick(action)
                }
                .padding(4.dp)
        ) {
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(progress)
                        .align(Alignment.BottomCenter)
                        .background(mainColor.copy(alpha = 0.3f))
                )
            }

            if (action.isHighPriorityQueueItem()) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .align(Alignment.TopEnd)
                        .background(SciFiGold, shape = RoundedCornerShape(2.dp))
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = action.getDisplayName(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAvailable && isAffordable) Color.White else Color.Gray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (cost > 0) {
                        Text(
                            text = "$${cost}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAffordable) SciFiCyan else SciFiRed
                        )
                    }

                    if (isActivated) {
                        Text(
                            text = "ACTIVE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            color = SciFiOrange
                        )
                    }
                }
            }

            if (progress > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        color = mainColor,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }

    @Composable
    fun MultiUnitSelectionHeader(
        groupedUnits: Map<UnitTypeInterface, List<GameUnit>>,
        activeUnitType: UnitTypeInterface?,
        onSelectUnitType: (UnitTypeInterface) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color(0xCC050A0E)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "选中单位:",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            groupedUnits.forEach { (type, units) ->
                val isSelected = type == activeUnitType
                val shape = remember { SciFiChamferShape(8f) }

                Row(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(shape)
                        .background(if (isSelected) SciFiCyan.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                        .border(1.dp, if (isSelected) SciFiCyan else Color.Transparent, shape)
                        .clickable { onSelectUnitType(type) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = type.getInternalName(),
                        fontSize = 11.sp,
                        color = if (isSelected) Color.White else Color.LightGray,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "x${units.size}",
                        fontSize = 10.sp,
                        color = SciFiCyan
                    )
                }
            }
        }
    }

    @Composable
    fun SciFiActionGrid(
        actions: List<SpecialAction>,
        targetUnit: GameUnit,
        onActionClick: (SpecialAction) -> Unit,
        modifier: Modifier = Modifier
    ) {
        var selectedCategory by remember { mutableStateOf(SpecialActionCategory.building) }

        val filteredActions = remember(actions, selectedCategory, targetUnit) {
            actions
                .filter { it.getActionCategory() == selectedCategory }
                .sortedWith(
                    compareByDescending<SpecialAction> { it.isHighPriorityQueueItem() }
                        .thenBy { it.getSortOrder() }
                )
        }

        Column(modifier = modifier) {
            SecondaryTabRow(
                selectedCategory.ordinal,
                Modifier, Color.Transparent,
                SciFiCyan,
                {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(selectedCategory.ordinal, true),
                        color = SciFiCyan
                    )
                },
                @Composable { HorizontalDivider() }) {
                SpecialActionCategory.values().forEach { category ->
                    Tab(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        text = {
                            Text(
                                text = category.name.replaceFirstChar { it.titlecase() },
                                fontSize = 11.sp,
                                fontWeight = if (selectedCategory == category) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (filteredActions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("该分类无可用指令", color = Color.Gray, fontSize = 11.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 72.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = filteredActions,
                        key = { "${it.getDisplayName()}_${it.getSortOrder()}" }
                    ) { action ->
                        SciFiActionButton(
                            action = action,
                            targetUnit = targetUnit,
                            onActionClick = onActionClick
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun SciFiRtsHudConsole(
        selectedUnits: List<GameUnit>,
        onExecuteAction: (action: SpecialAction, targetUnits: List<GameUnit>) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val groupedUnits = remember(selectedUnits) {
            selectedUnits.groupBy { it.getUnitTypeInterface() }
        }

        var activeUnitType by remember(groupedUnits) {
            mutableStateOf(groupedUnits.keys.firstOrNull())
        }

        val currentTypeUnits = remember(selectedUnits) { groupedUnits[activeUnitType] ?: emptyList() }
        val primaryUnit = remember(selectedUnits) { currentTypeUnits.firstOrNull() }

        val availableActions = remember(primaryUnit) {
            primaryUnit?.getSpecialActions()?.filterIsInstance<SpecialAction>() ?: emptyList()
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(230.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, SciFiDarkBg)
                    )
                )
                .padding(6.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                if (groupedUnits.size > 1) {
                    MultiUnitSelectionHeader(
                        groupedUnits = groupedUnits,
                        activeUnitType = activeUnitType,
                        onSelectUnitType = { activeUnitType = it }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Row(modifier = Modifier.fillMaxSize()) {

                    SciFiUnitStatusPanel(
                        selectedUnits = currentTypeUnits,
                        primaryUnit = primaryUnit,
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    if (primaryUnit != null) {
                        SciFiActionGrid(
                            actions = availableActions,
                            targetUnit = primaryUnit,
                            onActionClick = { action ->
                                onExecuteAction(action, currentTypeUnits)
                            },
                            modifier = Modifier
                                .weight(0.7f)
                                .fillMaxHeight()
                                .clip(SciFiChamferShape(12f))
                                .background(Color(0x8803070A))
                                .border(1.dp, SciFiCyan.copy(alpha = 0.3f), SciFiChamferShape(12f))
                                .padding(6.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(0.7f)
                                .fillMaxHeight()
                                .clip(SciFiChamferShape(12f))
                                .background(Color(0x4403070A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("未选择单位 - 全局监控中", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SciFiRtsHudConsoleVertical(
        selectedUnits: List<GameUnit>,
        onExecuteAction: (action: SpecialAction, targetUnits: List<GameUnit>) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val groupedUnits = remember(selectedUnits) {
            selectedUnits.groupBy { it.getUnitTypeInterface() }
        }

        var activeUnitType by remember(groupedUnits) {
            mutableStateOf(groupedUnits.keys.firstOrNull())
        }

        val currentTypeUnits = groupedUnits[activeUnitType] ?: emptyList()
        val primaryUnit = currentTypeUnits.firstOrNull()

        val availableActions = remember(primaryUnit) {
            primaryUnit?.getSpecialActions()?.filterIsInstance<SpecialAction>() ?: emptyList()
        }

        val shape = remember { SciFiChamferShape(16f) }

        Box(
            modifier = modifier
                .width(280.dp)
                .fillMaxHeight()
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0x11000000), SciFiDarkBg)
                    )
                )
                .border(1.dp, SciFiCyan.copy(alpha = 0.25f), shape)
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                if (groupedUnits.size > 1) {
                    MultiUnitSelectionHeader(
                        groupedUnits = groupedUnits,
                        activeUnitType = activeUnitType,
                        onSelectUnitType = { activeUnitType = it }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                SciFiUnitStatusPanelVertical(
                    selectedUnits = currentTypeUnits,
                    primaryUnit = primaryUnit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (primaryUnit != null) {
                    SciFiActionGrid(
                        actions = availableActions,
                        targetUnit = primaryUnit,
                        onActionClick = { action ->
                            onExecuteAction(action, currentTypeUnits)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(SciFiChamferShape(12f))
                            .background(Color(0x8803070A))
                            .border(1.dp, SciFiCyan.copy(alpha = 0.3f), SciFiChamferShape(12f))
                            .padding(6.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(SciFiChamferShape(12f))
                            .background(Color(0x4403070A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未选择单位 - 全局监控中", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun SciFiUnitStatusPanel(
        selectedUnits: List<GameUnit>,
        primaryUnit: GameUnit?,
        modifier: Modifier = Modifier
    ) {
        var avgHpRatio by remember { mutableDoubleStateOf(0.0) }
        var avgShieldRatio by remember { mutableDoubleStateOf(0.0) }

        OnGameUpdate {
            val size = selectedUnits.size
            if (selectedUnits.isNotEmpty()) {
                logger().info("avg: $avgHpRatio")
                avgHpRatio = selectedUnits.sumOf { (it.hp / it.maxHp).toDouble() } / size
                avgShieldRatio = selectedUnits.sumOf { (it.shield / it.maxShield).toDouble() } / size
            }
        }

        val shape = remember { SciFiChamferShape(12f) }

        Box(
            modifier = modifier
                .clip(shape)
                .background(Color(0x8803070A))
                .border(1.dp, SciFiCyan.copy(alpha = 0.3f), shape)
                .padding(8.dp)
        ) {
            if (primaryUnit != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = primaryUnit.getUnitTypeInterface().getInternalName(),
                        color = SciFiCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "数量: ${selectedUnits.size}",
                        color = Color.LightGray,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = "HP (${(avgHpRatio * 100).toInt()}%)", color = Color.Gray, fontSize = 9.sp)
                    LinearProgressIndicator(
                        progress = { avgHpRatio.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (avgHpRatio > 0.3f) Color.Green else SciFiRed,
                        trackColor = Color.DarkGray
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (primaryUnit.maxShield > 0) {
                        Text(text = "SHIELD", color = Color.Gray, fontSize = 9.sp)
                        LinearProgressIndicator(
                            progress = { avgShieldRatio.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = SciFiCyan,
                            trackColor = Color.DarkGray
                        )
                    }
                }
            } else {
                Text(text = "待命", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    @Composable
    fun SciFiUnitStatusPanelVertical(
        selectedUnits: List<GameUnit>,
        primaryUnit: GameUnit?,
        modifier: Modifier = Modifier
    ) {
        var avgHpRatio by remember { mutableFloatStateOf(0f) }
        var avgShieldRatio by remember { mutableFloatStateOf(0f) }

        OnGameUpdate {
            if (selectedUnits.isNotEmpty()) {
                avgHpRatio = selectedUnits.map { (it.hp / it.maxHp).coerceAtLeast(1f) }.average().toFloat()
                avgShieldRatio = selectedUnits.map {
                    if (it.maxShield > 0) it.shield.toFloat() / it.maxShield else 0f
                }.average().toFloat()
            }
        }

        val shape = remember { SciFiChamferShape(12f) }

        Box(
            modifier = modifier
                .clip(shape)
                .background(Color(0x8803070A))
                .border(1.dp, SciFiCyan.copy(alpha = 0.3f), shape)
                .padding(10.dp)
        ) {
            if (primaryUnit != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                        text = primaryUnit.getUnitTypeInterface().getInternalName(),
                            color = SciFiCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "x${selectedUnits.size}",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "HP", color = Color.Gray, fontSize = 9.sp)
                        Text(
                            text = "${(avgHpRatio * 100).toInt()}%",
                            color = if (avgHpRatio > 0.3f) Color.Green else SciFiRed,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { avgHpRatio },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (avgHpRatio > 0.3f) Color.Green else SciFiRed,
                        trackColor = Color.DarkGray
                    )

                    if (primaryUnit.maxShield > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "SHIELD", color = Color.Gray, fontSize = 9.sp)
                            Text(
                                text = "${(avgShieldRatio * 100).toInt()}%",
                                color = SciFiCyan,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { avgShieldRatio },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = SciFiCyan,
                            trackColor = Color.DarkGray
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "待命", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}
