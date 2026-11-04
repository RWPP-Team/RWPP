package io.github.rwpp.logic.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BuilderQueueProtectionTest {
    private fun site(x: Int) = BuilderQueueSite("experimentalLandFactory", x, 400, 1)
    @Test
    fun retainsLongestQueueAndOnlyGroupsMatchingRemainingSites() {
        val full = listOf(site(100), site(200), site(300))
        val plan = assertNotNull(
            planBuilderQueueProtection(
                listOf(
                    BuilderQueueMember(5, full.takeLast(2)), BuilderQueueMember(9, full),
                    BuilderQueueMember(2, full), BuilderQueueMember(7, listOf(site(900))),
                    BuilderQueueMember(8, null),
                )
            )
        )
        assertEquals(2L, plan.leaderId)
        assertEquals(setOf(5L, 9L), plan.followerIds.toSet())
    }

    @Test
    fun singlePlacementsIdleAndManualOrdersAreNotReplaced() {
        assertNull(
            planBuilderQueueProtection(
                listOf(
                    BuilderQueueMember(1, listOf(site(100))),
                    BuilderQueueMember(2, listOf(site(100)))
                )
            )
        )
        assertNull(
            planBuilderQueueProtection(
                listOf(
                    BuilderQueueMember(1, listOf(site(100), site(200))),
                    BuilderQueueMember(2, null),
                    BuilderQueueMember(3, emptyList())
                )
            )
        )
    }

    @Test
    fun differentQueuedProjectsRemainIndependent() {
        assertNull(
            planBuilderQueueProtection(
                listOf(
                    BuilderQueueMember(1, listOf(site(100), site(200))),
                    BuilderQueueMember(2, listOf(site(900), site(950)))
                )
            )
        )
    }

    @Test
    fun comparesNativeSnappedSitesRatherThanRawPlacementCoordinates() {
        assertEquals(390, snappedBuilderSiteCoordinate(400f, 10f, 20))
        assertEquals(410, snappedBuilderSiteCoordinate(410f, 10f, 20))
    }

}
