package io.github.rwpp.ui

import com.corrodinggames.rts.game.units.actions.ActionType
import io.github.rwpp.game.graphics.AeroOrderClicks
import io.github.rwpp.game.hud.HudActionRoute
import io.github.rwpp.game.hud.hudActionRoute
import io.github.rwpp.mixin.orders.AeroNativeOrderClickMixin
import io.github.rwpp.mixin.orders.AeroNativeOrderEffectCaptureMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudTouchControlsTest {
    @Test
    fun queuePanelPreservesDesktopAndTargetModeRoutes() {
        assertTrue(hudUsesTouchQueuePanel(true, ActionType.popupQueue, false))
        assertTrue(hudUsesTouchQueuePanel(true, ActionType.none, true))
        assertFalse(hudUsesTouchQueuePanel(false, ActionType.popupQueue, true))
        assertFalse(hudUsesTouchQueuePanel(true, ActionType.placeBuilding, true))
        assertFalse(hudUsesTouchQueuePanel(true, ActionType.none, false))
        assertFalse(
            hudUsesTouchQueuePanel(true, ActionType.none, true, queueKnown = false),
            "Search and other direct callbacks must execute immediately"
        )
        assertEquals(
            HudActionRoute.RemoveQueueAction,
            hudActionRoute(ActionType.popupQueue, true, queueEntryAvailable = true, activeActionMatches = false)
        )
    }

    @Test
    fun everyTapAfterSelectingTheSameProductionBuildsUntilDismissedOrChanged() {
        val selection = HudProductionSelection()
        assertFalse(selection.tap("factoryA:tank"))
        repeat(5) { assertTrue(selection.tap("factoryA:tank"), "Every later click builds, not just each second click") }
        assertFalse(selection.tap("factoryA:builder"), "Changing the production type selects it first")
        assertTrue(selection.tap("factoryA:builder"))
        assertFalse(selection.tap("factoryB:builder"), "A different selected factory starts a new production selection")
        selection.clear()
        assertFalse(
            selection.tap("factoryB:builder"),
            "Outside dismissal must reset production even before a model refresh"
        )
    }

    @Test
    fun queuePopupUsesFreeSpaceOutsideTheConsole() {
        val anchor = androidx.compose.ui.unit.IntRect(700, 100, 1000, 600)
        val popup = androidx.compose.ui.unit.IntSize(280, 180)
        val p = queuePopupPosition(anchor, androidx.compose.ui.unit.IntSize(1000, 700), popup, 8)
        assertTrue(p.x + popup.width <= anchor.left, "production panel must not obscure console buttons")
        val above = queuePopupPosition(
            androidx.compose.ui.unit.IntRect(0, 400, 350, 700),
            androidx.compose.ui.unit.IntSize(350, 700), popup, 8
        )
        assertTrue(above.y + popup.height <= 400, "use space above a console at the bottom of a narrow window")
    }

    @Test
    fun orderFeedbackIsBoundedAndDoesNotSurviveAClockReset() {
        AeroOrderClicks.clear()
        try {
            repeat(40) { AeroOrderClicks.add(it.toFloat(), 3f, false, 1000L) }
            assertEquals(16, AeroOrderClicks.frame(1001L).size)
            assertTrue(AeroOrderClicks.frame(1701L).isEmpty())
            AeroOrderClicks.add(1f, 2f, true, 2000L)
            assertTrue(AeroOrderClicks.frame(1000L).isEmpty())
        } finally {
            AeroOrderClicks.clear()
        }
    }

    @Test
    fun nativeCommandWrappersAndParticleCaptureMatchTheActualGameTogether() {
        val target = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine"
        val loader = javaClass.classLoader
        val bytes = loader.getResourceAsStream("$target.class")!!.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(AeroNativeOrderClickMixin::class.java)
            AsmRegistry.register(AeroNativeOrderEffectCaptureMixin::class.java)
            val rewritten = AsmProcessor().transform(target, bytes, loader)
            val node = ClassNode(); ClassReader(rewritten).accept(node, 0)
            val calls = node.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
                .filter {
                    it.owner in setOf(
                        "io/github/rwpp/mixin/orders/AeroNativeOrderClickMixin",
                        "io/github/rwpp/mixin/orders/AeroNativeOrderEffectCaptureMixin"
                    )
                }
            val original = ClassNode(); ClassReader(bytes).accept(original, 0)
            fun nativeSounds(n: ClassNode) =
                n.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
                    .count { it.name == "playInterfaceSound" || it.name == "anySelectedUnitSupportsCommand" }
            assertTrue(nativeSounds(original) > 0, "fixture must exercise native movement/attack audio")
            assertEquals(
                nativeSounds(original),
                nativeSounds(node),
                "visual command wrappers must retain original generic and unit-specific order sounds"
            )
            for (name in listOf(
                "move",
                "attackMove",
                "patrol",
                "attack",
                "moveEffect",
                "attackMoveEffect",
                "patrolEffect",
                "attackEffect"
            ))
                assertTrue(calls.any { it.name == name }, "Missing native command or effect interception: $name")
        } finally {
            AsmRegistry.clear()
        }
    }
}
