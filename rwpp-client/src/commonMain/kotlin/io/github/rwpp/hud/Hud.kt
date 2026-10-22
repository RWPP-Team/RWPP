/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rwpp.config.HudComponentData
import kotlin.math.abs

class HudComponentState(
    val data: HudComponentData
) {
    var x by mutableFloatStateOf(data.xDp)
    var y by mutableFloatStateOf(data.yDp)
    var scale by mutableFloatStateOf(data.scale)

    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)

    fun syncToData() {
        data.xDp = x
        data.yDp = y
        data.scale = scale
    }
}

class HudComponentTemplate(
    val name: String,
    val content: @Composable () -> Unit
)

private data class SnapTarget(
    val candidatePos: Float,
    val guidePos: Float
)

@Composable
fun HudEditor(
    components: List<HudComponentState>,
    isEditMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onAddComponent: (HudComponentData) -> Unit,
    onDeleteComponent: (HudComponentState) -> Unit,
    availableTemplates: Map<String, HudComponentTemplate> = mapOf(),
    modifier: Modifier = Modifier
) {
    var verticalGuidelineX by remember { mutableStateOf<Float?>(null) }
    var horizontalGuidelineY by remember { mutableStateOf<Float?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthDp = maxWidth.value
        val maxHeightDp = maxHeight.value
        val snapThresholdDp = 6f

        if (isEditMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
            )
        }

        components.forEach { state ->
            val template = availableTemplates[state.data.id] ?: return@forEach
            HudComponentWrapper(
                template = template,
                state = state,
                allComponents = components,
                isEditMode = isEditMode,
                maxWidthDp = maxWidthDp,
                maxHeightDp = maxHeightDp,
                snapThresholdDp = snapThresholdDp,
                onSnapGuidesChange = { vX, hY ->
                    verticalGuidelineX = vX
                    horizontalGuidelineY = hY
                },
                onDelete = { onDeleteComponent(state) },
                content = { template.content() }
            )
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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                        Text(" Add Component", modifier = Modifier.padding(start = 4.dp))
                    }

                    Button(
                        onClick = { onEditModeChange(false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Exit", modifier = Modifier.size(16.dp))
                        Text(" Exit Edit Mode", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            if (showAddDialog) {
                AddComponentDialog(
                    availableTemplates = availableTemplates,
                    onDismiss = { showAddDialog = false },
                    onSelect = { templateId ->
                        showAddDialog = false
                        onAddComponent(
                            HudComponentData(
                                id = templateId,
                                xDp = (maxWidthDp / 2) - 40f,
                                yDp = (maxHeightDp / 2) - 40f,
                                scale = 1f
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun HudComponentWrapper(
    template: HudComponentTemplate,
    state: HudComponentState,
    allComponents: List<HudComponentState>,
    isEditMode: Boolean,
    maxWidthDp: Float,
    maxHeightDp: Float,
    snapThresholdDp: Float,
    onSnapGuidesChange: (Float?, Float?) -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset { IntOffset(with(density) { state.x.dp.roundToPx() }, with(density) { state.y.dp.roundToPx() }) }
    ) {
        Box(
            modifier = Modifier
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
                    .border(1.dp, Color.Yellow.copy(alpha = 0.8f))
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

                            for (target in xTargets) {
                                val dist = abs(rawX - target.candidatePos)
                                if (dist < minXDist) {
                                    minXDist = dist
                                    bestX = target.candidatePos
                                    guideX = target.guidePos
                                }
                            }

                            var bestY = rawY
                            var guideY: Float? = null
                            var minYDist = snapThresholdDp

                            for (target in yTargets) {
                                val dist = abs(rawY - target.candidatePos)
                                if (dist < minYDist) {
                                    minYDist = dist
                                    bestY = target.candidatePos
                                    guideY = target.guidePos
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
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
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
                    contentDescription = "Delete",
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
                                val maxScaleY = if (maxHeightDp > state.y) (maxHeightDp - state.y) / state.height else 3.0f
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
                    contentDescription = "Resize",
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add HUD Component") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                availableTemplates.forEach { (id, template) ->
                    OutlinedButton(
                        onClick = { onSelect(id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(template.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}