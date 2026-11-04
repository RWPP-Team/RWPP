/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionFollowSessionTest {
    @Test
    fun capturedGroupIsDetachedFromLaterSelectionChanges() {
        val liveSelection = mutableListOf("tank", "engineer", "scout")
        val session = SelectionFollowSession(liveSelection)
        liveSelection.clear()

        assertEquals(listOf("tank", "engineer", "scout"), session.capturedUnits)
        assertEquals("tank", session.refreshAnchor { true })
    }

    @Test
    fun firstLivingUnitBecomesAnchorAndDeathAdvancesInCapturedOrder() {
        val alive = mutableSetOf("engineer", "scout")
        val session = SelectionFollowSession(listOf("tank", "engineer", "scout"))

        assertEquals("engineer", session.refreshAnchor { it in alive })
        alive.remove("engineer")
        assertEquals("scout", session.refreshAnchor { it in alive })
        assertEquals("scout", session.refreshAnchor { it in alive })
        alive.clear()
        assertNull(session.refreshAnchor { it in alive })
        assertNull(session.currentAnchor)
    }

    @Test
    fun rolloverWrapsToNextLivingMemberOnce() {
        val alive = mutableSetOf("tank", "engineer", "scout")
        val session = SelectionFollowSession(listOf("tank", "engineer", "scout"))

        assertEquals("tank", session.refreshAnchor { it in alive })
        alive.remove("tank")
        alive.remove("engineer")
        assertEquals("scout", session.refreshAnchor { it in alive })
        alive.add("tank")
        assertEquals("scout", session.refreshAnchor { it in alive })
    }
}
