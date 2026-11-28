/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.hud

import io.github.rwpp.config.HudComponentData
import io.github.rwpp.config.Settings
import kotlin.reflect.KMutableProperty0

/** One editor visit owns its layout and HUD options until the user explicitly saves. */
internal class HudEditSession(components: List<HudComponentState>, settings: Settings) {
    private val original = components.map { state ->
        state.syncToData()
        state to state.data.copy()
    }
    private val options = listOf(
        saved(settings::hudAnimations),
        saved(settings::aeroRangeUseTeamColors),
        saved(settings::showBuildingAttackRange),
        saved(settings::showAttackRangeUnit),
        saved(settings::aeroRangeEffects),
        saved(settings::aeroEnemyRangesOnly),
        saved(settings::aeroSelection),
        saved(settings::aeroUnitGlowHighlight),
        saved(settings::aeroDamageAnimation),
        saved(settings::aeroDamageNumbers),
        saved(settings::aeroOrderClickEffects),
        saved(settings::aeroOrderEffects),
        saved(settings::aeroHoverEffects),
        saved(settings::aeroGuardEffects),
        saved(settings::aeroWaypointEffects),
        saved(settings::aeroRallyPointEffects),
        saved(settings::aeroProductionLabels),
        saved(settings::aeroAlliedProductionLabels),
        saved(settings::aeroBuildEffects),
        saved(settings::aeroGroupOrders),
        saved(settings::aeroWorldUnitGroups),
        saved(settings::commandTooltipScale),
        saved(settings::minimapUsePlayerColors),
        saved(settings::maxDisplayUnitGroupCount),
        saved(settings::showExtraButton),
        saved(settings::hudChatShowTeam),
        saved(settings::hudChatShowTimestamp),
        saved(settings::hudCustomResources),
        saved(settings::hudCustomResourcesPosition),
        saved(settings::hudCustomResourcesLayout),
    )
    private var finished = false
    val isActive: Boolean get() = !finished

    fun hasChanges(components: List<HudComponentState>): Boolean {
        if (finished) return false
        components.forEach(HudComponentState::syncToData)
        return original.map { layoutValue(it.first, it.second) } != components.map { layoutValue(it, it.data) } ||
                options.any { it.changed() }
    }

    /** A failed write leaves the draft and rollback snapshot available for another choice. */
    fun save(components: List<HudComponentState>, persist: () -> Unit): Result<Unit> {
        if (finished) return Result.success(Unit)
        return runCatching {
            components.forEach(HudComponentState::syncToData)
            persist()
            finished = true
        }
    }

    fun discard(replaceLayout: (List<HudComponentData>) -> Unit) {
        if (finished) return
        original.forEach { (state, data) -> state.restoreFrom(data) }
        options.forEach { it.restore() }
        replaceLayout(original.map { it.first.data })
        finished = true
    }
}

/** Absolute coordinates follow viewport changes; anchors carry the user's layout intent. */
private fun layoutValue(state: HudComponentState, data: HudComponentData) = listOf(
    data.instanceId, data.id, state.layoutPosition(data), data.scale,
)

private interface SavedHudOption {
    fun changed(): Boolean
    fun restore()
}

private fun <T> saved(property: KMutableProperty0<T>): SavedHudOption {
    val value = property.get()
    return object : SavedHudOption {
        override fun changed() = property.get() != value
        override fun restore() = property.set(value)
    }
}
