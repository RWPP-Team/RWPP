package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import io.github.rwpp.game.hud.AeroDamageTimeline
import io.github.rwpp.game.hud.aeroDamageLabel
import io.github.rwpp.graphics.WorldVisualRefreshSchedule
import io.github.rwpp.mixin.hud.AeroDamageNumbersMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AeroDamageNumbersTest {
    @Test
    fun rapidHitsMergeAndNumbersExpireInGameTime() {
        val timeline = AeroDamageTimeline()
        timeline.hit(7, Offset(12f, 20f), 10f, 1000)
        timeline.hit(7, Offset(12f, 20f), 5f, 1060)
        assertEquals(15f, timeline.frame(1060).single().amount)
        assertEquals(timeline.frame(1060), timeline.frame(1060), "Paused game time freezes animation")
        timeline.hit(7, Offset(12f, 20f), 3f, 1150)
        assertEquals(2, timeline.frame(1150).size)
        assertEquals(1, timeline.frame(1900).size)
        assertTrue(timeline.frame(2050).isEmpty())
    }

    @Test
    fun invalidDamageIsIgnoredAndMassBattlesStayBounded() {
        val timeline = AeroDamageTimeline()
        timeline.hit(1, Offset.Zero, -10f, 0)
        timeline.hit(1, Offset.Zero, Float.NaN, 0)
        assertTrue(timeline.frame(0).isEmpty())
        repeat(2000) { timeline.hit(it, Offset.Zero, 1f, 0) }
        assertEquals(128, timeline.frame(0).size)
        assertTrue(timeline.frame(-1).isEmpty(), "A new match must discard old animation time")
        timeline.clear()
        assertTrue(timeline.frame(0).isEmpty())
    }

    @Test
    fun damageHooksTransformTheNativeDamageMethodWithoutReplacingIt() {
        AsmRegistry.clear()
        try {
            AsmRegistry.register(AeroDamageNumbersMixin::class.java)
            val target = "com/corrodinggames/rts/game/units/Unit"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use { ClassReader(it).accept(node, 0) }
            assertTrue(AsmProcessor().applyAsms(target, node))
            val calls =
                node.methods.single { it.name == "damage" }.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertTrue(calls.any { it.owner == "io/github/rwpp/mixin/hud/AeroDamageNumbersMixin" && it.name == "before" })
            assertTrue(calls.any { it.owner == "io/github/rwpp/mixin/hud/AeroDamageNumbersMixin" && it.name == "after" })
            assertTrue(calls.any { it.name == "checkDeath" }, "Native health/death processing is preserved")
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun damageLabelsKeepSmallHitsVisible() {
        assertEquals("−12", aeroDamageLabel(12.2f))
        assertEquals("−0.1", aeroDamageLabel(0.03f))
    }

    @Test
    fun animationCanAdvanceAtDisplayCadenceBetweenHeavySamplesWithoutAdvancingPausedTime() {
        val schedule = WorldVisualRefreshSchedule()
        val timeline = AeroDamageTimeline()
        timeline.hit(1, Offset(30f, 40f), 12f, 1000)
        assertTrue(schedule.due(1_000_000_000L))
        var age = 0f
        for (step in 1..3) {
            assertFalse(schedule.due(1_000_000_000L + step * 8_333_333L))
            val number = timeline.frame(1000 + step * 8L).single()
            assertTrue(number.age > age, "jump text must not hold its old age between 30Hz geometry samples")
            assertEquals(Offset(30f, 40f), number.center)
            age = number.age
        }
        assertEquals(timeline.frame(1024), timeline.frame(1024), "pause must freeze age despite display frames")
    }

}
