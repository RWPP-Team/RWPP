package io.github.rwpp.api.event.hooks

import io.github.rwpp.api.mixin.game.stats.NativeStatsHistoryAccessMixin
import io.github.rwpp.api.mixin.game.stats.StatsHistoryLoadMixin
import kotlin.test.*

class StatsHistoryLoadHooksTest {
    @Test
    fun statsReadUpdateAndTeamArrayAccessorMatchTheNativeClass() {
        val target = "com/corrodinggames/rts/gameFramework/StatsHandler"
        val loader = javaClass.classLoader
        val bytes = requireNotNull(loader.getResourceAsStream("$target.class")).use { it.readBytes() }
        kim.der.asm.AsmRegistry.clear()
        try {
            kim.der.asm.AsmRegistry.register(StatsHistoryLoadMixin::class.java)
            kim.der.asm.AsmRegistry.register(NativeStatsHistoryAccessMixin::class.java)
            val result = kim.der.asm.transformer.AsmProcessor().transform(target, bytes, loader)
            val node = org.objectweb.asm.tree.ClassNode()
            org.objectweb.asm.ClassReader(result).accept(node, 0)
            assertTrue(node.interfaces.contains("io/github/rwpp/api/mixin/game/stats/NativeStatsHistoryAccess"))
            for ((method, handler) in listOf("read" to "repairStatsAfterRead", "update" to "guardHistorySnapshot")) {
                assertTrue(
                    node.methods.single { it.name == method }.instructions.toArray()
                        .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().any { it.name == handler })
            }
            assertTrue(node.methods.any { it.name == "rwppTeamStats" })
        } finally {
            kim.der.asm.AsmRegistry.clear()
        }
    }

    private data class RestoredStats(val value: Int)

    @Test
    fun missingSlotsAreInitializedWithoutReplacingRestoredStats() {
        val restoredAtOne = RestoredStats(17)
        val restoredAtThree = RestoredStats(43)
        val entries = arrayOfNulls<RestoredStats>(5).apply {
            this[1] = restoredAtOne
            this[3] = restoredAtThree
        }
        var nextValue = 0

        val initialized = fillMissingStatsSlots(entries) { RestoredStats(nextValue++) }

        assertEquals(3, initialized)
        assertSame(restoredAtOne, entries[1])
        assertSame(restoredAtThree, entries[3])
        assertEquals(listOf(0, 17, 1, 43, 2), entries.map { requireNotNull(it).value })
    }

    @Test
    fun historySnapshotsPauseDuringStatsReadAndResumeAfterward() {
        val gate = StatsHistoryLoadGate()

        assertTrue(gate.tryEnterUpdate())
        assertFalse(gate.tryEnterRead(), "a load must wait for an in-flight snapshot")
        gate.leaveUpdate()

        assertTrue(gate.tryEnterRead())
        assertFalse(gate.tryEnterUpdate(), "snapshots must not inspect the partially read stats array")
        gate.leaveRead()

        assertTrue(gate.tryEnterUpdate(), "history recording should resume after stats have been repaired")
        gate.leaveUpdate()
    }
}
