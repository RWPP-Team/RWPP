/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.hud


import io.github.rwpp.ui.PortableConfigActions
import kotlinx.serialization.builtins.ListSerializer
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.HudComponentData
import io.github.rwpp.config.Settings
import io.github.rwpp.widget.v2.*
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

class HudComponentState(
    val data: HudComponentData
) {
    var x by mutableFloatStateOf(data.xDp)
    var y by mutableFloatStateOf(data.yDp)
    var scale by mutableFloatStateOf(data.scale)

    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    fun updateViewport(width: Float, height: Float, legacyWidth: Float = width, legacyHeight: Float = height) {
        if (data.relativeX == null) data.relativeX = hudRelativePosition(data.xDp, legacyWidth, this.width * scale)
        if (data.relativeY == null) data.relativeY = hudRelativePosition(data.yDp, legacyHeight, this.height * scale)
        viewportWidth = width
        viewportHeight = height
        x = hudAbsolutePosition(data.relativeX ?: 0f, width, this.width * scale)
        y = hudAbsolutePosition(data.relativeY ?: 0f, height, this.height * scale)
    }

    fun syncToData() {
        data.xDp = x
        data.yDp = y
        data.scale = scale
        if (viewportWidth > 0f && viewportHeight > 0f) {
            data.relativeX = hudRelativePosition(x, viewportWidth, width * scale)
            data.relativeY = hudRelativePosition(y, viewportHeight, height * scale)
        }
    }

    fun moveBy(deltaX: Float, deltaY: Float, viewportWidth: Float, viewportHeight: Float) {
        x = (x + deltaX).coerceIn(0f, (viewportWidth - width * scale).coerceAtLeast(0f))
        y = (y + deltaY).coerceIn(0f, (viewportHeight - height * scale).coerceAtLeast(0f))
    }

    fun moveZoneBounds(viewportWidth: Float): Rect {
        val left = if (x >= HUD_CONSOLE_MOVE_ZONE_WIDTH_DP) {
            x - HUD_CONSOLE_MOVE_ZONE_WIDTH_DP
        } else {
            (x + width * scale).coerceAtMost((viewportWidth - HUD_CONSOLE_MOVE_ZONE_WIDTH_DP).coerceAtLeast(0f))
        }
        return Rect(
            left,
            y + HUD_CONSOLE_MOVE_ZONE_TOP_DP,
            left + HUD_CONSOLE_MOVE_ZONE_WIDTH_DP,
            y + HUD_CONSOLE_MOVE_ZONE_TOP_DP + HUD_CONSOLE_MOVE_ZONE_HEIGHT_DP
        )
    }
}

private const val HUD_CONSOLE_MOVE_ZONE_WIDTH_DP = 12f
private const val HUD_CONSOLE_MOVE_ZONE_HEIGHT_DP = 48f
private const val HUD_CONSOLE_MOVE_ZONE_TOP_DP = 12f

/** A component exists for one kind of game session; others never offer or render it. */
enum class HudComponentMode { ALWAYS, OBSERVER, SANDBOX }

class HudComponentTemplate(
    val name: String,
    val initialWidthDp: Float = 320f,
    val initialHeightDp: Float = 180f,
    val fillWidth: Boolean = false,
    val fillHeight: Boolean = false,
    val compactHeight: Boolean = false,
    val compactWidth: Boolean = false,
    val mode: HudComponentMode = HudComponentMode.ALWAYS,
    val content: @Composable () -> Unit
)

private data class SnapTarget(
    val candidatePos: Float,
    val guidePos: Float
)

private enum class HudSettingsScope {
    WORLD_EFFECTS,
    COMMAND_CONSOLE,
    CHAT_MESSAGES,
    STATUS_READOUT,
    COMPONENT
}

private fun hudSettingsScopeFor(componentId: String) = when (componentId) {
    "hud_console_vertical" -> HudSettingsScope.COMMAND_CONSOLE
    "chat" -> HudSettingsScope.CHAT_MESSAGES
    "telemetry" -> HudSettingsScope.STATUS_READOUT
    else -> HudSettingsScope.COMPONENT
}

@Composable
private fun hudComponentDisplayName(id: String, fallback: String): String = when (id) {
    "telemetry" -> readI18n("rwpp.hud.component.telemetry")
    "chat" -> readI18n("rwpp.hud.component.chat")
    "events" -> readI18n("rwpp.hud.component.events")
    "touch_selection_controls" -> readI18n("rwpp.hud.component.touchControls")
    "hud_console_vertical" -> readI18n("rwpp.hud.component.commandConsole")
    else -> fallback
}

@Composable
private fun HudSettingToggle(
    key: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    AeroSettingToggle(
        label = readI18n(key),
        defaultValue = value,
        onCheckedChange = onValueChange,
    )
}

@Composable
private fun HudEditorSettingsDialog(
    scope: HudSettingsScope,
    onDismiss: () -> Unit
) {
    val settings = koinInject<Settings>()
    val configIO = koinInject<ConfigIO>()
    val title = when (scope) {
        HudSettingsScope.WORLD_EFFECTS -> "BATTLEFIELD EFFECTS"
        HudSettingsScope.COMMAND_CONSOLE -> "COMMAND CONSOLE SETTINGS"
        HudSettingsScope.CHAT_MESSAGES -> "CHAT MESSAGE SETTINGS"
        HudSettingsScope.STATUS_READOUT -> "STATUS READOUT SETTINGS"
        HudSettingsScope.COMPONENT -> "IN-GAME HUD SETTINGS"
    }

    AnimatedDialog(onDismissRequest = onDismiss) { _ ->
        AeroPanel(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AeroHeader(title)

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AeroSettingGroup(
                        title = "GENERAL",
                        tag = "General",
                    ) {
                        HudSettingToggle("rwpp.hud.editor.settings.animations", settings.hudAnimations) {
                            settings.hudAnimations = it
                        }
                    }

                    when (scope) {
                        HudSettingsScope.WORLD_EFFECTS -> {
                            AeroSettingGroup(
                                title = "UNIT OVERLAYS",
                                tag = "Unit overlays",
                            ) {
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.rangeTeamColors",
                                    settings.aeroRangeUseTeamColors
                                ) {
                                    settings.aeroRangeUseTeamColors = it
                                }
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.buildingRanges",
                                    settings.showBuildingAttackRange
                                ) {
                                    settings.showBuildingAttackRange = it
                                }
                                val rangeTypes = Settings.unitAttackRangeTypes
                                val rangeLabels = listOf(
                                    readI18n("rwpp.hud.editor.settings.rangeOff"),
                                    readI18n("rwpp.hud.editor.settings.rangeGround"),
                                    readI18n("rwpp.hud.editor.settings.rangeAir"),
                                    readI18n("rwpp.hud.editor.settings.rangeAll"),
                                )
                                var rangeTypeIndex by remember(settings) {
                                    mutableIntStateOf(rangeTypes.indexOf(settings.showAttackRangeUnit).coerceAtLeast(0))
                                }
                                AeroSettingDropDown(
                                    label = readI18n("rwpp.hud.editor.settings.unitRanges"),
                                    items = rangeLabels,
                                    selectedIndex = rangeTypeIndex,
                                    onItemSelected = { index, _ ->
                                        rangeTypeIndex = index
                                        settings.showAttackRangeUnit = rangeTypes[index]
                                    },
                                )
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.rangeAnimations",
                                    settings.aeroRangeEffects
                                ) {
                                    settings.aeroRangeEffects = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.enemyRanges", settings.aeroEnemyRangesOnly) {
                                    settings.aeroEnemyRangesOnly = it
                                }
                            }

                            AeroSettingGroup(
                                title = "AERO EFFECTS",
                                tag = "Aero effects",
                            ) {
                                HudSettingToggle("rwpp.hud.editor.settings.selection", settings.aeroSelection) {
                                    settings.aeroSelection = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.unitGlow", settings.aeroUnitGlowHighlight) {
                                    settings.aeroUnitGlowHighlight = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.damageTrail", settings.aeroDamageAnimation) {
                                    settings.aeroDamageAnimation = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.damageNumbers", settings.aeroDamageNumbers) {
                                    settings.aeroDamageNumbers = it
                                }
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.orderClickEffects",
                                    settings.aeroOrderClickEffects
                                ) {
                                    settings.aeroOrderClickEffects = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.orderEffects", settings.aeroOrderEffects) {
                                    settings.aeroOrderEffects = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.hoverEffects", settings.aeroHoverEffects) {
                                    settings.aeroHoverEffects = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.guardEffects", settings.aeroGuardEffects) {
                                    settings.aeroGuardEffects = it
                                }
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.waypointEffects",
                                    settings.aeroWaypointEffects
                                ) {
                                    settings.aeroWaypointEffects = it
                                }
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.rallyEffects",
                                    settings.aeroRallyPointEffects
                                ) {
                                    settings.aeroRallyPointEffects = it
                                }
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.productionLabels",
                                    settings.aeroProductionLabels
                                ) {
                                    settings.aeroProductionLabels = it
                                }
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.alliedProduction",
                                    settings.aeroAlliedProductionLabels
                                ) {
                                    settings.aeroAlliedProductionLabels = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.buildEffects", settings.aeroBuildEffects) {
                                    settings.aeroBuildEffects = it
                                }
                                HudSettingToggle("rwpp.hud.editor.settings.groupOrders", settings.aeroGroupOrders) {
                                    settings.aeroGroupOrders = it
                                }
                            }
                        }

                        HudSettingsScope.COMMAND_CONSOLE -> {
                            AeroSettingGroup(
                                title = "CONTROLS",
                                tag = "Controls",
                            ) {
                                HudSettingToggle(
                                    "rwpp.settings.minimapUsePlayerColors",
                                    settings.minimapUsePlayerColors
                                ) {
                                    settings.minimapUsePlayerColors = it
                                }
                                var maxGroups by remember(settings) {
                                    mutableIntStateOf(settings.maxDisplayUnitGroupCount.coerceIn(0, 10))
                                }
                                AeroSettingSlider(
                                    label = readI18n("rwpp.hud.editor.settings.visibleGroups"),
                                    value = maxGroups / 10f,
                                    onValueChange = {
                                        maxGroups = (it * 10).roundToInt().coerceIn(0, 10)
                                        settings.maxDisplayUnitGroupCount = maxGroups
                                    },
                                    valueRange = 0f..1f,
                                    valueFormat = { "${(it * 10f).roundToInt().coerceIn(0, 10)}" },
                                )
                                HudSettingToggle("rwpp.hud.editor.settings.attackActions", settings.showExtraButton) {
                                    settings.showExtraButton = it
                                }
                            }
                        }

                        HudSettingsScope.CHAT_MESSAGES -> {
                            AeroSettingGroup(
                                title = "CHAT",
                                tag = "Chat",
                            ) {
                                HudSettingToggle("rwpp.hud.editor.settings.chatTeam", settings.hudChatShowTeam) {
                                    settings.hudChatShowTeam = it
                                }
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.chatTimestamp",
                                    settings.hudChatShowTimestamp
                                ) {
                                    settings.hudChatShowTimestamp = it
                                }
                            }
                        }

                        HudSettingsScope.STATUS_READOUT -> {
                            AeroSettingGroup(
                                title = "RESOURCE ROWS",
                                tag = "Mod resources",
                            ) {
                                HudSettingToggle(
                                    "rwpp.hud.editor.settings.customResources",
                                    settings.hudCustomResources,
                                ) {
                                    settings.hudCustomResources = it
                                }
                                AeroSettingDropDown(
                                    label = readI18n("rwpp.hud.editor.settings.customResourcesPosition"),
                                    items = listOf(
                                        readI18n("rwpp.hud.editor.position.above"),
                                        readI18n("rwpp.hud.editor.position.below"),
                                    ),
                                    selectedIndex = if (settings.hudCustomResourcesPosition == "above") 0 else 1,
                                    onItemSelected = { index, _ ->
                                        settings.hudCustomResourcesPosition = if (index == 0) "above" else "below"
                                    },
                                )
                                AeroSettingDropDown(
                                    label = readI18n("rwpp.hud.editor.settings.customResourcesLayout"),
                                    items = listOf(
                                        readI18n("rwpp.hud.editor.layout.vertical"),
                                        readI18n("rwpp.hud.editor.layout.horizontal"),
                                    ),
                                    selectedIndex = if (settings.hudCustomResourcesLayout == "horizontal") 1 else 0,
                                    onItemSelected = { index, _ ->
                                        settings.hudCustomResourcesLayout =
                                            if (index == 1) "horizontal" else "vertical"
                                    },
                                )
                            }
                        }

                        HudSettingsScope.COMPONENT -> Unit
                    }
                }

                AeroButton(readI18n("rwpp.common.exit"), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun HudEditorToolbar(
    widthDp: Float,
    onAddComponent: () -> Unit,
    onLayoutFiles: (() -> Unit)?,
    onSaveAndExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addLabel = readI18n("rwpp.hud.editor.addComponent")
    val saveLabel = readI18n("rwpp.hud.editor.saveAndExit")
    val addSubtext = readI18n("rwpp.hud.editor.addSubtext")
    val saveSubtext = readI18n("rwpp.hud.editor.saveSubtext")

    if (widthDp >= 600f) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AeroButton(addLabel, onAddComponent, modifier = Modifier.weight(1f), subText = addSubtext)
            if (onLayoutFiles != null) AeroButton(
                readI18n("rwpp.portable.layout"), onLayoutFiles, modifier = Modifier.weight(1f),
            )
            AeroButton(saveLabel, onSaveAndExit, modifier = Modifier.weight(1f), subText = saveSubtext)
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (widthDp < 420f) {
                AeroButton(addLabel, onAddComponent, modifier = Modifier.fillMaxWidth(), subText = addSubtext)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AeroButton(addLabel, onAddComponent, modifier = Modifier.weight(1f), subText = addSubtext)
                    AeroButton(saveLabel, onSaveAndExit, modifier = Modifier.weight(1f), subText = saveSubtext)
                }
            }
            if (onLayoutFiles != null) AeroButton(
                readI18n("rwpp.portable.layout"), onLayoutFiles, modifier = Modifier.fillMaxWidth(),
            )
            if (widthDp < 420f) {
                AeroButton(saveLabel, onSaveAndExit, modifier = Modifier.fillMaxWidth(), subText = saveSubtext)
            }
        }
    }
}

@Composable
fun HudEditor(
    components: List<HudComponentState>,
    isEditMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onAddComponent: (HudComponentData) -> Unit,
    onDeleteComponent: (HudComponentState) -> Unit,
    availableTemplates: Map<String, HudComponentTemplate> = mapOf(),
    modifier: Modifier = Modifier,
    onSaveConfig: () -> Unit = {},
    onImportLayout: ((List<HudComponentData>) -> Unit)? = null,
    layoutComponentIds: Set<String> = availableTemplates.keys,
) {
    var verticalGuidelineX by remember { mutableStateOf<Float?>(null) }
    var horizontalGuidelineY by remember { mutableStateOf<Float?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showLayoutFiles by remember { mutableStateOf(false) }
    var settingsScope by remember { mutableStateOf<HudSettingsScope?>(null) }
    val density = LocalDensity.current
    val latestSaveConfig = rememberUpdatedState(onSaveConfig)
    LaunchedEffect(isEditMode) {
        if (!isEditMode) {
            showAddDialog = false
            showLayoutFiles = false
            settingsScope = null
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthDp = maxWidth.value
        val maxHeightDp = maxHeight.value
        val snapThresholdDp = 6f
        val dragSlopPx = with(density) { 4.dp.toPx() }

        Box(modifier = Modifier.fillMaxSize()) {
            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { }
                )
            }

            components.forEach { state ->
                val template = availableTemplates[state.data.id] ?: return@forEach
                key(state.data.instanceId) {
                    HudComponentWrapper(
                        template = template,
                        state = state,
                        allComponents = components,
                        isEditMode = isEditMode,
                        maxWidthDp = maxWidthDp,
                        maxHeightDp = maxHeightDp,
                        snapThresholdDp = snapThresholdDp,
                        onOpenSettings = { settingsScope = hudSettingsScopeFor(state.data.id) },
                        onSnapGuidesChange = { vX, hY ->
                            verticalGuidelineX = vX
                            horizontalGuidelineY = hY
                        },
                        onDelete = { onDeleteComponent(state) },
                        content = { template.content() }
                    )
                }
            }

            if (!isEditMode) {
                components.filter { it.data.id == "hud_console_vertical" }.forEach { state ->
                    val moveZoneCoordinates = remember(state) { arrayOfNulls<LayoutCoordinates>(1) }
                    Box(
                        modifier = Modifier
                            .offset {
                                val moveZone = state.moveZoneBounds(maxWidthDp)
                                IntOffset(
                                    with(density) { moveZone.left.dp.roundToPx() },
                                    with(density) { moveZone.top.dp.roundToPx() }
                                )
                            }
                            .size(HUD_CONSOLE_MOVE_ZONE_WIDTH_DP.dp, HUD_CONSOLE_MOVE_ZONE_HEIGHT_DP.dp)
                            .onGloballyPositioned { moveZoneCoordinates[0] = it }
                            .pointerInput(state, maxWidthDp, maxHeightDp) {
                                var activePointerId: androidx.compose.ui.input.pointer.PointerId? = null
                                var lastPosition = Offset.Zero
                                var pendingDelta = Offset.Zero
                                var dragStarted = false
                                fun finishDrag() {
                                    if (dragStarted) {
                                        state.syncToData()
                                        latestSaveConfig.value()
                                    }
                                    activePointerId = null
                                    pendingDelta = Offset.Zero
                                    dragStarted = false
                                }
                                try {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull {
                                                activePointerId == null || it.id == activePointerId
                                            } ?: continue
                                            val coordinates = moveZoneCoordinates[0] ?: continue
                                            val rootPosition = coordinates.localToRoot(change.position)
                                            when (event.type) {
                                                PointerEventType.Press -> {
                                                    if (activePointerId == null && change.type == PointerType.Mouse &&
                                                        state.width > 0f && state.height > 0f
                                                    ) {
                                                        activePointerId = change.id
                                                        lastPosition = rootPosition
                                                        pendingDelta = Offset.Zero
                                                        dragStarted = false
                                                        change.consume()
                                                    }
                                                }

                                                PointerEventType.Move -> {
                                                    if (activePointerId == change.id && change.type == PointerType.Mouse) {
                                                        val delta = rootPosition - lastPosition
                                                        lastPosition = rootPosition
                                                        if (!dragStarted) {
                                                            pendingDelta += delta
                                                            if (pendingDelta.getDistance() >= dragSlopPx) {
                                                                dragStarted = true
                                                                state.moveBy(
                                                                    with(density) { pendingDelta.x.toDp().value },
                                                                    with(density) { pendingDelta.y.toDp().value },
                                                                    maxWidthDp,
                                                                    maxHeightDp
                                                                )
                                                            }
                                                        } else {
                                                            state.moveBy(
                                                                with(density) { delta.x.toDp().value },
                                                                with(density) { delta.y.toDp().value },
                                                                maxWidthDp,
                                                                maxHeightDp
                                                            )
                                                        }
                                                        change.consume()
                                                    }
                                                }

                                                PointerEventType.Release -> {
                                                    if (activePointerId == change.id && change.type == PointerType.Mouse) {
                                                        change.consume()
                                                        finishDrag()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    finishDrag()
                                }
                            },
                    )
                }
            }

            if (isEditMode) {
                verticalGuidelineX?.let { xDp ->
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .offset(x = xDp.dp)
                            .background(Color.Cyan.copy(alpha = 0.8f))
                    )
                }

                horizontalGuidelineY?.let { yDp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .offset(y = yDp.dp)
                            .background(Color.Cyan.copy(alpha = 0.8f))
                    )
                }

                val toolbarWidthDp = minOf(maxWidthDp, 640f)
                HudEditorToolbar(
                    widthDp = toolbarWidthDp,
                    onAddComponent = { showAddDialog = true },
                    onLayoutFiles = if (onImportLayout != null) ({ showLayoutFiles = true }) else null,
                    onSaveAndExit = {
                        components.forEach(HudComponentState::syncToData)
                        onEditModeChange(false)
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 16.dp),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    if (components.isEmpty()) Text(
                        readI18n("rwpp.hud.editor.emptyHelp"),
                        modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary
                    )
                }

                if (showAddDialog) {
                    AddComponentDialog(
                        availableTemplates = availableTemplates,
                        onDismiss = { showAddDialog = false },
                        onSelect = { templateId ->
                            val template = availableTemplates.getValue(templateId)
                            val scale =
                                minOf(1f, maxWidthDp / template.initialWidthDp, maxHeightDp / template.initialHeightDp)
                            onAddComponent(
                                HudComponentData(
                                    id = templateId,
                                    xDp = (maxWidthDp - template.initialWidthDp * scale) / 2,
                                    yDp = (maxHeightDp - template.initialHeightDp * scale) / 2,
                                    scale = scale,
                                    relativeX = 0.5f, relativeY = 0.5f
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    if (isEditMode) {
        if (showLayoutFiles && onImportLayout != null) {
            AnimatedDialog(onDismissRequest = { showLayoutFiles = false }) { dismiss ->
                AeroPanel(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AeroHeader(title = "HUD LAYOUT")
                        PortableConfigActions(
                            "rwpp-hud-layout.toml", ListSerializer(HudComponentData.serializer()),
                            snapshot = { components.map { it.syncToData(); it.data.copy() } },
                            apply = { imported ->
                                require(imported.size <= 128 && imported.all {
                                    it.id in layoutComponentIds && it.xDp.isFinite() && it.yDp.isFinite() &&
                                            it.scale in 0.05f..5f &&
                                            (it.relativeX == null || it.relativeX!! in 0f..1f) &&
                                            (it.relativeY == null || it.relativeY!! in 0f..1f)
                                })
                                onImportLayout(imported.map { it.copy(instanceId = java.util.UUID.randomUUID().toString()) })
                            },
                            save = { components.forEach(HudComponentState::syncToData); onSaveConfig() },
                        )
                        AeroButton(readI18n("rwpp.common.close"), dismiss, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        settingsScope?.let { scope ->
            HudEditorSettingsDialog(scope, onDismiss = { settingsScope = null })
        }
    }
}

@Composable
private fun HudComponentWrapper(
    template: HudComponentTemplate,
    state: HudComponentState,
    allComponents: List<HudComponentState>,
    isEditMode: Boolean,
    onOpenSettings: () -> Unit,
    maxWidthDp: Float,
    maxHeightDp: Float,
    snapThresholdDp: Float,
    onSnapGuidesChange: (Float?, Float?) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val legacyViewport = LocalLegacyHudViewport.current
    val contentWidth = if (template.fillWidth) maxWidthDp / state.scale else template.initialWidthDp
    val contentHeight = if (template.fillHeight) maxHeightDp / state.scale else template.initialHeightDp
    LaunchedEffect(state.width, state.height, maxWidthDp, maxHeightDp, state.scale) {
        if (state.width > 0 && state.height > 0) {
            state.updateViewport(maxWidthDp, maxHeightDp, legacyViewport.width, legacyViewport.height)
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(with(density) { state.x.dp.roundToPx() }, with(density) { state.y.dp.roundToPx() }) }
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .then(
                    if (template.compactHeight) (if (template.compactWidth) Modifier.widthIn(max = contentWidth.dp) else Modifier.requiredWidth(
                        contentWidth.dp
                    )).heightIn(max = contentHeight.dp)
                    else Modifier.requiredSize(contentWidth.dp, contentHeight.dp)
                )
                .onSizeChanged { size: IntSize ->
                    with(density) {
                        state.width = size.width.toDp().value
                        state.height = size.height.toDp().value
                    }
                }
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            content()
        }

        if (isEditMode && state.width > 0f && state.height > 0f) {
            var rawX by remember(state) { mutableFloatStateOf(state.x) }
            var rawY by remember(state) { mutableFloatStateOf(state.y) }

            val scaledWidth = state.width * state.scale
            val scaledHeight = state.height * state.scale

            Box(
                modifier = Modifier
                    .size(scaledWidth.dp, scaledHeight.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                    .pointerInput(state) {
                        detectDragGestures(
                            onDragStart = {
                                rawX = state.x
                                rawY = state.y
                            },
                            onDragEnd = {
                                state.syncToData()
                                onSnapGuidesChange(null, null)
                            },
                            onDragCancel = {
                                state.syncToData()
                                onSnapGuidesChange(null, null)
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaXDp = with(density) { dragAmount.x.toDp().value }
                            val deltaYDp = with(density) { dragAmount.y.toDp().value }

                            rawX += deltaXDp
                            rawY += deltaYDp

                            val currentScaledWidth = state.width * state.scale
                            val currentScaledHeight = state.height * state.scale

                            val xTargets = mutableListOf<SnapTarget>()
                            xTargets.add(SnapTarget(0f, 0f))
                            xTargets.add(SnapTarget((maxWidthDp - currentScaledWidth) / 2f, maxWidthDp / 2f))
                            xTargets.add(SnapTarget(maxWidthDp - currentScaledWidth, maxWidthDp))

                            val yTargets = mutableListOf<SnapTarget>()
                            yTargets.add(SnapTarget(0f, 0f))
                            yTargets.add(SnapTarget((maxHeightDp - currentScaledHeight) / 2f, maxHeightDp / 2f))
                            yTargets.add(SnapTarget(maxHeightDp - currentScaledHeight, maxHeightDp))

                            allComponents.forEach { other ->
                                if (other !== state && other.width > 0f && other.height > 0f) {
                                    val oLeft = other.x
                                    val oScaledWidth = other.width * other.scale
                                    val oRight = other.x + oScaledWidth
                                    val oCenterX = other.x + oScaledWidth / 2f

                                    xTargets.add(SnapTarget(oLeft, oLeft))
                                    xTargets.add(SnapTarget(oRight, oRight))
                                    xTargets.add(SnapTarget(oLeft - currentScaledWidth, oLeft))
                                    xTargets.add(SnapTarget(oRight - currentScaledWidth, oRight))
                                    xTargets.add(SnapTarget(oCenterX - currentScaledWidth / 2f, oCenterX))

                                    val oTop = other.y
                                    val oScaledHeight = other.height * other.scale
                                    val oBottom = other.y + oScaledHeight
                                    val oCenterY = other.y + oScaledHeight / 2f

                                    yTargets.add(SnapTarget(oTop, oTop))
                                    yTargets.add(SnapTarget(oBottom, oBottom))
                                    yTargets.add(SnapTarget(oTop - currentScaledHeight, oTop))
                                    yTargets.add(SnapTarget(oBottom - currentScaledHeight, oBottom))
                                    yTargets.add(SnapTarget(oCenterY - currentScaledHeight / 2f, oCenterY))
                                }
                            }

                            var bestX = rawX
                            var guideX: Float? = null
                            var minXDist = snapThresholdDp

                            for ((candidatePos, guidePos) in xTargets) {
                                val dist = abs(rawX - candidatePos)
                                if (dist < minXDist) {
                                    minXDist = dist
                                    bestX = candidatePos
                                    guideX = guidePos
                                }
                            }

                            var bestY = rawY
                            var guideY: Float? = null
                            var minYDist = snapThresholdDp

                            for ((candidatePos, guidePos) in yTargets) {
                                val dist = abs(rawY - candidatePos)
                                if (dist < minYDist) {
                                    minYDist = dist
                                    bestY = candidatePos
                                    guideY = guidePos
                                }
                            }

                            val minX = minOf(0f, maxWidthDp - currentScaledWidth)
                            val maxX = maxOf(0f, maxWidthDp - currentScaledWidth)
                            val clampedX = bestX.coerceIn(minX, maxX)
                            if (clampedX != bestX && minXDist < snapThresholdDp) {
                                guideX = null
                            }

                            val minY = minOf(0f, maxHeightDp - currentScaledHeight)
                            val maxY = maxOf(0f, maxHeightDp - currentScaledHeight)
                            val clampedY = bestY.coerceIn(minY, maxY)
                            if (clampedY != bestY && minYDist < snapThresholdDp) {
                                guideY = null
                            }

                            state.x = clampedX
                            state.y = clampedY
                            onSnapGuidesChange(guideX, guideY)
                        }
                    }
            )

            val titleTargetX = state.x + 4f
            val titleTargetY = state.y - 12f
            val titleClampedX = titleTargetX.coerceIn(0f, (maxWidthDp - 60f).coerceAtLeast(0f))
            val titleClampedY = titleTargetY.coerceIn(0f, (maxHeightDp - 20f).coerceAtLeast(0f))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.offset(
                    x = (titleClampedX - state.x).dp,
                    y = (titleClampedY - state.y).dp
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 6.dp, end = 3.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(
                        text = hudComponentDisplayName(state.data.id, template.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenSettings),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = readI18n("rwpp.hud.editor.componentOptions"),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            val deleteTargetX = state.x + scaledWidth - 12f
            val deleteTargetY = state.y - 12f
            val deleteClampedX = deleteTargetX.coerceIn(0f, (maxWidthDp - 24f).coerceAtLeast(0f))
            val deleteClampedY = deleteTargetY.coerceIn(0f, (maxHeightDp - 24f).coerceAtLeast(0f))

            Box(
                modifier = Modifier
                    .offset(
                        x = (deleteClampedX - state.x).dp,
                        y = (deleteClampedY - state.y).dp
                    )
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = readI18n("rwpp.hud.editor.delete"),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
            }

            val resizeTargetX = state.x + scaledWidth - 12f
            val resizeTargetY = state.y + scaledHeight - 12f
            val resizeClampedX = resizeTargetX.coerceIn(0f, (maxWidthDp - 24f).coerceAtLeast(0f))
            val resizeClampedY = resizeTargetY.coerceIn(0f, (maxHeightDp - 24f).coerceAtLeast(0f))

            Box(
                modifier = Modifier
                    .offset(
                        x = (resizeClampedX - state.x).dp,
                        y = (resizeClampedY - state.y).dp
                    )
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color.Black, CircleShape)
                    .pointerInput(state) {
                        detectDragGestures(
                            onDragEnd = { state.syncToData() },
                            onDragCancel = { state.syncToData() }
                        ) { change, dragAmount ->
                            change.consume()
                            val dragDp = with(density) { dragAmount.x.toDp().value }
                            if (state.width > 0f) {
                                val maxScaleX = if (maxWidthDp > state.x) (maxWidthDp - state.x) / state.width else 3.0f
                                val maxScaleY =
                                    if (maxHeightDp > state.y) (maxHeightDp - state.y) / state.height else 3.0f
                                val maxScale = minOf(3.0f, maxScaleX, maxScaleY)

                                val scaleDelta = dragDp / state.width
                                val newScale = (state.scale + scaleDelta).coerceIn(0.5f, maxScale.coerceAtLeast(0.5f))
                                state.scale = newScale
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = readI18n("rwpp.hud.editor.resize"),
                    modifier = Modifier.size(14.dp),
                    tint = Color.Black
                )
            }
        }
    }
}

@Composable
private fun AddComponentDialog(
    availableTemplates: Map<String, HudComponentTemplate>,
    onDismiss: () -> Unit,
    onSelect: (id: String) -> Unit
) {
    AnimatedDialog(onDismissRequest = onDismiss) { dismiss ->
        AeroPanel(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AeroHeader(
                    "ADD HUD COMPONENT",
                    tag = "HUD modules",
                )
                AeroDivider()
                availableTemplates.forEach { (id, template) ->
                    AeroButton(
                        text = hudComponentDisplayName(id, template.name),
                        onClick = {
                            onSelect(id)
                            dismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                AeroButton(readI18n("rwpp.hud.editor.cancel"), onClick = dismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
