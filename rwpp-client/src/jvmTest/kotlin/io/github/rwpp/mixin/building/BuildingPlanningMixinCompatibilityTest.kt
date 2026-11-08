package io.github.rwpp.mixin.building

import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import kotlin.test.*

/** Validate the actual game method signatures and callback parameter mapping before shipping. */
class BuildingPlanningMixinCompatibilityTest {
    @Test
    fun nativeTouchSelectionAndPlacementHooksTransformActualGameClasses() {
        AsmRegistry.clear()
        try {
            AsmRegistry.register(BuildingPlanningDragSelectionMixin::class.java)
            AsmRegistry.register(BuildingPlanningIndependentInputMixin::class.java)
            for (simpleName in listOf("InterfaceEngine", "BattleInterface")) {
                val name = "com/corrodinggames/rts/gameFramework/gui/$simpleName"
                val original = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
                val transformed = AsmProcessor().transform(name, original, javaClass.classLoader)
                assertFalse(original.contentEquals(transformed), "Native hooks were not applied to $simpleName")
            }
        } finally {
            AsmRegistry.clear()
        }
    }
}
