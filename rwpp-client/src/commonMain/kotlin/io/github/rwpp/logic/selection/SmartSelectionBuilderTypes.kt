/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.selection

internal const val DEFAULT_SMART_SELECTION_EXTRA_BUILDER_TYPES = "combatEngineer,mechEngineer"

/** Exact unit type IDs treated as builders only by native mixed-combat drag smart-selection. */
internal data class SmartSelectionBuilderTypeRules(val unitTypeIds: Set<String>) {
    fun contains(internalName: String): Boolean = internalName.trim().lowercase() in unitTypeIds

    companion object {
        fun fromConfig(raw: String): SmartSelectionBuilderTypeRules = SmartSelectionBuilderTypeRules(
            raw.split(',', ';', '\n', '\r')
                .asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toCollection(linkedSetOf())
        )
    }
}

/** Configured types do not count as combat units when vanilla decides whether to split a drag box. */
internal fun countsAsCombatForSmartSelection(
    canAttackIndependently: Boolean,
    canAttack: Boolean,
    isConfiguredExtraBuilder: Boolean,
): Boolean = canAttackIndependently && canAttack && !isConfiguredExtraBuilder

/** Mirrors the native mixed-combat builder eligibility predicate with configured extra builders. */
internal fun isSmartSelectionBuilderExcluded(
    mixedCombatMode: Boolean,
    nativeBuilder: Boolean,
    canAttack: Boolean,
    isConfiguredExtraBuilder: Boolean,
): Boolean = mixedCombatMode && (isConfiguredExtraBuilder || nativeBuilder && !canAttack)
