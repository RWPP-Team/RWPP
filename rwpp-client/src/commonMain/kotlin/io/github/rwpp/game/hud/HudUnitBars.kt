/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.land.ExperimentalHoverTank
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** Hide only the health row when several fully healthy units are selected together. */
internal fun hideFullHealthBarDuringMultiSelection(
    selected: Boolean,
    selectedUnitCount: Int,
    hp: Float,
    maxHp: Float,
): Boolean = selected && selectedUnitCount > 1 && maxHp > 0f && hp >= maxHp

/**
 * The Aero shield row is a replacement for the native unit bars, so its visibility must not
 * depend on selection, hover, health visibility, or the current shield amount. In particular,
 * this keeps full and depleted shields visible in the same way as partially depleted shields.
 */
@Suppress("UNUSED_PARAMETER")
internal fun aeroShieldVisible(
    aeroSelection: Boolean,
    selected: Boolean,
    hovered: Boolean,
    currentShield: Float,
    maxShield: Float,
): Boolean = aeroSelection && maxShield > 0f

/**
 * Whether CustomUnit.getSecondaryBarFill() is using its shield branch.
 *
 * Keep this in lockstep with the native method's ordering: an enabled, incomplete energy bar
 * takes precedence over the shield bar, even when the shield is also incomplete.
 */
internal fun aeroCustomSecondaryIsShield(
    maxEnergy: Float,
    currentEnergy: Float,
    showEnergyBar: Boolean,
    maxShield: Float,
    shield: Float,
    showShieldBar: Boolean,
): Boolean {
    val energyBranch = maxEnergy > 0f && currentEnergy < maxEnergy && showEnergyBar
    return !energyBranch && maxShield > 0f && shield < maxShield && showShieldBar
}

/** Whether ExperimentalHoverTank.getSecondaryBarFill() is returning its native shield bar. */
internal fun aeroExperimentalHoverTankSecondaryIsShield(maxShield: Float, shield: Float): Boolean =
    maxShield > 0f && shield < maxShield

/** Custom units and attachment slots can explicitly hide or shrink the native health row. */
internal fun nativeUnitHealthBarAllowed(unit: GameUnit): Boolean =
    (unit !is CustomUnit || unit.meta?.showHealthBar != false) && unit.getAttachmentSlot()?.hideHp != true

/**
 * Return the native secondary value unless the native implementation is using it for shield.
 * Shield gets its own pale-blue Aero row; retaining the same value as a generic secondary row
 * would display the same shield twice.
 */
internal fun aeroNonShieldSecondary(unit: GameUnit): Float {
    val secondary = unit.secondaryBarFill
    return when (unit) {
        is CustomUnit -> {
            val metadata = unit.meta ?: return secondary
            val unitData = unit.unitData ?: return secondary
            if (aeroCustomSecondaryIsShield(
                    maxEnergy = unitData.maxEnergy,
                    currentEnergy = unit.currentEnergy,
                    showEnergyBar = metadata.showEnergyBar,
                    maxShield = unit.maxShield,
                    shield = unit.shield,
                    showShieldBar = metadata.showShieldBar,
                )
            ) {
                -1f
            } else {
                secondary
            }
        }

        is ExperimentalHoverTank ->
            if (aeroExperimentalHoverTankSecondaryIsShield(unit.maxShield, unit.shield)) -1f else secondary

        else -> secondary
    }
}
