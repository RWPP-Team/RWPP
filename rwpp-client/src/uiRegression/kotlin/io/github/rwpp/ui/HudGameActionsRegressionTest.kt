package io.github.rwpp.ui

import com.corrodinggames.rts.game.units.actions.ActionType
import io.github.rwpp.game.hud.HudActionRoute
import io.github.rwpp.game.hud.HudQueueCommandPlan
import io.github.rwpp.game.hud.hudActionRoute
import io.github.rwpp.game.hud.hudQueueCommandPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class HudGameActionsRegressionTest {
    @Test
    fun targetedActionRemovalReachesActiveModeCancellationWithoutQueueCounts() {
        for (type in listOf(ActionType.patrol, ActionType.guardUnit, ActionType.pingMap, ActionType.placeBuilding)) {
            assertEquals(
                HudActionRoute.CancelActiveAction,
                hudActionRoute(type, remove = true, queueEntryAvailable = false, activeActionMatches = true),
                "$type must cancel its active mode despite having no queue count"
            )
            assertEquals(
                HudActionRoute.Ignore,
                hudActionRoute(type, remove = true, queueEntryAvailable = false, activeActionMatches = false),
                "$type must not cancel a different active mode"
            )
        }
    }

    @Test
    fun queueRemovalUsesCountAvailabilityAndTargetGroundRemovesItsQueuedOrder() {
        assertEquals(
            HudActionRoute.RemoveQueueAction,
            hudActionRoute(ActionType.none, remove = true, queueEntryAvailable = true, activeActionMatches = false)
        )
        assertEquals(
            HudActionRoute.Ignore,
            hudActionRoute(
                ActionType.popupQueue,
                remove = true,
                queueEntryAvailable = false,
                activeActionMatches = false
            )
        )
        assertEquals(
            HudActionRoute.RemoveTargetedOrder,
            hudActionRoute(
                ActionType.targetGround,
                remove = true,
                queueEntryAvailable = false,
                activeActionMatches = false
            )
        )
        assertEquals(
            HudActionRoute.QueueAction,
            hudActionRoute(ActionType.none, remove = false, queueEntryAvailable = false, activeActionMatches = false)
        )
    }

    @Test
    fun removalCommandKeepsNativeUnitSpecificAndQueueFlags() {
        assertEquals(
            HudQueueCommandPlan(
                removeFromQueue = true, useUnitSpecificAction = true,
                queueExtraActionOrder = false
            ), hudQueueCommandPlan(unitSpecificAction = true, remove = true)
        )
        assertEquals(
            HudQueueCommandPlan(
                removeFromQueue = true, useUnitSpecificAction = false,
                queueExtraActionOrder = false
            ), hudQueueCommandPlan(unitSpecificAction = false, remove = true)
        )
        assertEquals(
            HudQueueCommandPlan(
                removeFromQueue = false, useUnitSpecificAction = true,
                queueExtraActionOrder = true
            ), hudQueueCommandPlan(unitSpecificAction = true, remove = false)
        )
    }

    @Test
    fun infoOnlyActionsNeverEnterTheGameCommandRoutes() {
        assertEquals(
            HudActionRoute.Ignore,
            hudActionRoute(
                ActionType.infoOnly,
                remove = false,
                queueEntryAvailable = false,
                activeActionMatches = false
            )
        )
        assertEquals(
            HudActionRoute.Ignore,
            hudActionRoute(ActionType.infoOnly, remove = true, queueEntryAvailable = true, activeActionMatches = true)
        )
    }
}
