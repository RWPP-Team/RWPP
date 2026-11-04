/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.*
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.widget.v2.AeroSettingSlider
import io.github.rwpp.widget.v2.AeroSettingToggle
import kotlin.math.roundToInt

@Composable
internal fun GameVisualPreferences(settings: Settings) {
    AeroSettingToggle(readI18n("rwpp.graphics.unitShadows"), settings.drawUnitShadows, onCheckedChange = {
        settings.drawUnitShadows = it
    })
    AeroSettingToggle(readI18n("rwpp.graphics.deathSmoke"), settings.suppressDeathSmoke, onCheckedChange = {
        settings.suppressDeathSmoke = it
    })
    var unitOpacity by remember { mutableFloatStateOf(settings.unitOpacity.coerceIn(0f, 1f)) }
    var projectileOpacity by remember { mutableFloatStateOf(settings.projectileOpacity.coerceIn(0f, 1f)) }
    AeroSettingSlider(readI18n("rwpp.graphics.unitOpacity"), unitOpacity, {
        unitOpacity = it; settings.unitOpacity = it
    }, valueRange = 0f..1f, valueFormat = { "${(it * 100).roundToInt()}%" })
    AeroSettingSlider(readI18n("rwpp.graphics.projectileOpacity"), projectileOpacity, {
        projectileOpacity = it; settings.projectileOpacity = it
    }, valueRange = 0f..1f, valueFormat = { "${(it * 100).roundToInt()}%" })
    var customFps by remember { mutableStateOf(settings.gameFrameLimit > 0) }
    var fps by remember { mutableIntStateOf(settings.gameFrameLimit.takeIf { it > 0 } ?: 120) }
    AeroSettingToggle(readI18n("rwpp.graphics.customFps"), customFps, onCheckedChange = {
        customFps = it; settings.gameFrameLimit = if (it) fps else 0
    })
    if (customFps) AeroSettingSlider(readI18n("rwpp.graphics.frameLimit"), fps.toFloat(), {
        fps = (it / 5f).roundToInt() * 5; settings.gameFrameLimit = fps
    }, valueRange = 15f..360f, valueFormat = { "${it.roundToInt()} FPS" })

    var customZoom by remember { mutableStateOf(settings.customZoomLimits) }
    var minimum by remember { mutableFloatStateOf(settings.minimumCameraZoom.coerceIn(0.05f, 1f)) }
    var maximum by remember { mutableFloatStateOf(settings.maximumCameraZoom.coerceIn(1f, 10f)) }
    AeroSettingToggle(readI18n("rwpp.graphics.customZoom"), customZoom, onCheckedChange = {
        customZoom = it; settings.customZoomLimits = it
    })
    if (customZoom) {
        AeroSettingSlider(readI18n("rwpp.graphics.zoomMinimum"), minimum, {
            minimum = (it * 100).roundToInt() / 100f; settings.minimumCameraZoom = minimum
        }, valueRange = 0.05f..1f, valueFormat = { "${(it * 100).roundToInt()}%" })
        AeroSettingSlider(readI18n("rwpp.graphics.zoomMaximum"), maximum, {
            maximum = (it * 10).roundToInt() / 10f; settings.maximumCameraZoom = maximum
        }, valueRange = 1f..10f, valueFormat = { "${(it * 100).roundToInt()}%" })
    }
}
