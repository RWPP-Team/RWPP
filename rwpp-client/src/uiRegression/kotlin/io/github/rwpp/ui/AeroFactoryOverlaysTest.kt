package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import com.corrodinggames.rts.game.units.buildings.Factory
import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.game.units.buildings.LandFactory
import io.github.rwpp.game.hud.NativeFactoryOverlays
import io.github.rwpp.game.hud.productionRemainingSeconds
import io.github.rwpp.game.nativeaccess.NativeFactoryQueueAccess
import io.github.rwpp.graphics.productionTimeLabel
import io.github.rwpp.mixin.hud.AeroCustomRallyMixin
import io.github.rwpp.mixin.hud.AeroFactoryRallyMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class AeroFactoryOverlaysTest {
    private class FactoryFixture : LandFactory(false), NativeFactoryQueueAccess {
        lateinit var testQueue: FactoryBuildQueue
        override fun rwppBuildQueue() = testQueue
        override fun getUnitTypeInterface(): com.corrodinggames.rts.game.units.UnitTypeInterface =
            com.corrodinggames.rts.game.units.UnitType.landFactory
    }

    @Test
    fun etaUsesNativeProgressPerTickAndCurrentFactoryMultiplier() {
        val speed = 1f / 960f
        assertEquals(16, productionRemainingSeconds(0f, speed, 1f))
        assertEquals(12, productionRemainingSeconds(0.25f, speed, 1f))
        assertEquals(6, productionRemainingSeconds(0.25f, speed, 2f))
        assertEquals(0, productionRemainingSeconds(1f, speed, 1f))
        assertNull(productionRemainingSeconds(0.5f, speed, 0f))
        assertNull(productionRemainingSeconds(Float.NaN, speed, 1f))
        assertEquals("1:05", productionTimeLabel(65, false, "Paused", "Waiting"))
        assertEquals("暂停 · 1:05", productionTimeLabel(65, true, "暂停", "等待"))
        assertEquals("等待 · 1:05", productionTimeLabel(65, false, "暂停", "等待", waiting = true))
    }

    @Test
    fun readsTheRealNativeFactoryQueueAndRallyPoint() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val factory = unsafe.allocateInstance(FactoryFixture::class.java) as FactoryFixture
        val queue = FactoryBuildQueue(factory)
        factory.testQueue = queue
        Factory::class.java.getDeclaredField("buildQueue").apply { isAccessible = true }.set(factory, queue)
        assertSame(queue, NativeFactoryOverlays.queue(factory))
        assertNull(NativeFactoryOverlays.rallyPoint(factory))
        factory.setRallyPoint(android.graphics.PointF(400f, 250f))
        assertEquals(Offset(400f, 250f), NativeFactoryOverlays.rallyPoint(factory))
        val actionId = com.corrodinggames.rts.game.units.actions.ActionId.getOrCreate("rwpp-test-produce")
        queue.buildQueue.add(com.corrodinggames.rts.game.units.buildings.BuildQueueItem().apply {
            this.actionId = actionId; buildCount = 2
        })
        queue.buildQueue.add(com.corrodinggames.rts.game.units.buildings.BuildQueueItem().apply {
            this.actionId = actionId; buildCount = 3
        })
        assertEquals(5, factory.getQueuedCountOf(actionId, false), "Count includes separate native queue entries")
        queue.rallyPoint.x = Float.NaN
        assertNull(NativeFactoryOverlays.rallyPoint(factory))
    }

    @Test
    fun replacesBothNativeAndCustomRallyDrawersWithoutChangingRallyState() {
        AsmRegistry.clear()
        try {
            for ((type, mixin) in listOf(
                "com/corrodinggames/rts/game/units/buildings/Factory" to AeroFactoryRallyMixin::class.java,
                "com/corrodinggames/rts/game/units/custom/CustomUnit" to AeroCustomRallyMixin::class.java
            )) {
                AsmRegistry.register(mixin)
                val node = ClassNode()
                javaClass.classLoader.getResourceAsStream("$type.class")!!.use { ClassReader(it).accept(node, 0) }
                assertTrue(AsmProcessor().applyAsms(type, node))
                assertTrue(
                    node.methods.single { it.name == "drawRallyPointLine" }.instructions.toArray()
                        .filterIsInstance<MethodInsnNode>().any { it.name == "rally" })
            }
        } finally {
            AsmRegistry.clear()
        }
    }
}
