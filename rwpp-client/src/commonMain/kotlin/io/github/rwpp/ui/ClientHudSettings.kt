/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.widget.v2.AeroSettingDropDown
import io.github.rwpp.widget.v2.AeroSettingGroup
import io.github.rwpp.widget.v2.AeroSettingToggle
import org.koin.compose.koinInject

/** Both settings entry points edit the existing persisted fields. No migration or reset. */
@Composable
internal fun ClientHudSettings(settings: Settings) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AeroSettingGroup("GENERAL") {
            AeroSettingToggle(
                readI18n("rwpp.settings.hideOriginalHud"),
                settings.hideOriginalHud
            ) { appKoin.get<GameScreen>().setOriginalHudHidden(it) }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.animations"),
                settings.hudAnimations
            ) { settings.hudAnimations = it }
            AeroSettingToggle(
                readI18n("rwpp.settings.improvedHealthBar"),
                settings.improvedHealthBar
            ) { settings.improvedHealthBar = it }
            if (koinInject<AppContext>().isDesktop()) {
                val rates = remember { listOf(30, 60, 90, 120, 144, 240, 300) }
                var selectedRate by remember {
                    mutableIntStateOf(
                        rates.indexOf(settings.hudRefreshRate).coerceAtLeast(0)
                    )
                }
                AeroSettingDropDown(
                    label = readI18n("rwpp.settings.hudRefreshRate"),
                    items = rates.map { readI18n("rwpp.settings.hudRefreshRate.value", it) },
                    selectedIndex = selectedRate,
                    onItemSelected = { index, _ -> selectedRate = index; settings.hudRefreshRate = rates[index] },
                )
            }
        }
        AeroSettingGroup("AERO EFFECTS") {
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.selection"),
                settings.aeroSelection
            ) { settings.aeroSelection = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.damageTrail"),
                settings.aeroDamageAnimation
            ) { settings.aeroDamageAnimation = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.damageNumbers"),
                settings.aeroDamageNumbers
            ) { settings.aeroDamageNumbers = it }
            AeroSettingToggle(readI18n("rwpp.hud.editor.settings.orderClickEffects"), settings.aeroOrderClickEffects) {
                settings.aeroOrderClickEffects = it
            }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.orderEffects"),
                settings.aeroOrderEffects
            ) { settings.aeroOrderEffects = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.waypointEffects"),
                settings.aeroWaypointEffects
            ) { settings.aeroWaypointEffects = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.guardEffects"),
                settings.aeroGuardEffects
            ) { settings.aeroGuardEffects = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.hoverEffects"),
                settings.aeroHoverEffects
            ) { settings.aeroHoverEffects = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.unitGlow"),
                settings.aeroUnitGlowHighlight
            ) { settings.aeroUnitGlowHighlight = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.rallyEffects"),
                settings.aeroRallyPointEffects
            ) { settings.aeroRallyPointEffects = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.productionLabels"),
                settings.aeroProductionLabels
            ) { settings.aeroProductionLabels = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.alliedProduction"),
                settings.aeroAlliedProductionLabels
            ) { settings.aeroAlliedProductionLabels = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.buildEffects"),
                settings.aeroBuildEffects
            ) { settings.aeroBuildEffects = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.groupOrders"),
                settings.aeroGroupOrders
            ) { settings.aeroGroupOrders = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.rangeAnimations"),
                settings.aeroRangeEffects
            ) { settings.aeroRangeEffects = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.enemyRanges"),
                settings.aeroEnemyRangesOnly
            ) { settings.aeroEnemyRangesOnly = it }
        }
        AeroSettingGroup("UNIT OVERLAYS") {
            AeroSettingToggle(readI18n("rwpp.hud.editor.settings.rangeTeamColors"), settings.aeroRangeUseTeamColors) {
                settings.aeroRangeUseTeamColors = it
            }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.buildingRanges"),
                settings.showBuildingAttackRange
            ) { settings.showBuildingAttackRange = it }
            val rangeTypes = Settings.unitAttackRangeTypes
            var selected by remember {
                mutableIntStateOf(
                    rangeTypes.indexOf(settings.showAttackRangeUnit).coerceAtLeast(0)
                )
            }
            AeroSettingDropDown(
                label = readI18n("rwpp.hud.editor.settings.unitRanges"),
                items = listOf(
                    "rangeOff",
                    "rangeGround",
                    "rangeAir",
                    "rangeAll"
                ).map { readI18n("rwpp.hud.editor.settings.$it") },
                selectedIndex = selected,
                onItemSelected = { index, _ -> selected = index; settings.showAttackRangeUnit = rangeTypes[index] },
            )
        }
        AeroSettingGroup("MINIMAP") {
            AeroSettingToggle(
                readI18n("rwpp.settings.minimapUsePlayerColors"),
                settings.minimapUsePlayerColors
            ) { settings.minimapUsePlayerColors = it }
        }
        AeroSettingGroup("CONTROLS") {
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.attackActions"),
                settings.showExtraButton
            ) { settings.showExtraButton = it }
            val counts = remember { (0..10).map(Int::toString) }
            var selected by remember { mutableIntStateOf(settings.maxDisplayUnitGroupCount.coerceIn(0, 10)) }
            AeroSettingDropDown(
                readI18n("rwpp.hud.editor.settings.visibleGroups"), counts, selectedIndex = selected,
                onItemSelected = { index, _ -> selected = index; settings.maxDisplayUnitGroupCount = index })
        }
        AeroSettingGroup("CHAT") {
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.chatTeam"),
                settings.hudChatShowTeam
            ) { settings.hudChatShowTeam = it }
            AeroSettingToggle(
                readI18n("rwpp.hud.editor.settings.chatTimestamp"),
                settings.hudChatShowTimestamp
            ) { settings.hudChatShowTimestamp = it }
        }
    }
}
