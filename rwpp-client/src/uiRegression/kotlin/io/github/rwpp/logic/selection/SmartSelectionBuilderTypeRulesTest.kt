/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.selection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartSelectionBuilderTypeRulesTest {
    @Test
    fun defaultsContainCombatEngineerAndMechanicNativeIds() {
        val rules = SmartSelectionBuilderTypeRules.fromConfig(DEFAULT_SMART_SELECTION_EXTRA_BUILDER_TYPES)

        assertTrue(rules.contains("combatEngineer"))
        assertTrue(rules.contains("mechEngineer"))
        assertTrue(rules.contains("COMBATENGINEER"))
        assertFalse(rules.contains("engineer"))
    }

    @Test
    fun configuredBuilderLikeDoesNotTriggerCombatClassification() {
        assertFalse(countsAsCombatForSmartSelection(true, canAttack = true, isConfiguredExtraBuilder = true))
        assertFalse(countsAsCombatForSmartSelection(false, canAttack = true, isConfiguredExtraBuilder = false))
        assertTrue(countsAsCombatForSmartSelection(true, canAttack = true, isConfiguredExtraBuilder = false))
    }

    @Test
    fun extrasAreFilteredOnlyWhenNativeMixedCombatModeFiltersBuilders() {
        // Pure engineer/build-only selection keeps both native builders and configured extras.
        assertFalse(
            isSmartSelectionBuilderExcluded(
                false,
                nativeBuilder = true,
                canAttack = false,
                isConfiguredExtraBuilder = false
            )
        )
        assertFalse(
            isSmartSelectionBuilderExcluded(
                false,
                nativeBuilder = false,
                canAttack = true,
                isConfiguredExtraBuilder = true
            )
        )

        // Mixed combat mode retains the native builder predicate and adds configured builder-likes.
        assertTrue(
            isSmartSelectionBuilderExcluded(
                true,
                nativeBuilder = true,
                canAttack = false,
                isConfiguredExtraBuilder = false
            )
        )
        assertFalse(
            isSmartSelectionBuilderExcluded(
                true,
                nativeBuilder = true,
                canAttack = true,
                isConfiguredExtraBuilder = false
            )
        )
        assertTrue(
            isSmartSelectionBuilderExcluded(
                true,
                nativeBuilder = false,
                canAttack = true,
                isConfiguredExtraBuilder = true
            )
        )
        assertFalse(
            isSmartSelectionBuilderExcluded(
                true,
                nativeBuilder = false,
                canAttack = true,
                isConfiguredExtraBuilder = false
            )
        )
    }
}
