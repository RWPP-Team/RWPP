package io.github.rwpp.logic.mouse

import io.github.rwpp.mixin.building.BuildingPlanningDragSelectionMixin
import io.github.rwpp.mixin.hud.NativeInterfaceAccessMixin
import io.github.rwpp.mixin.hud.NativeMouseOrderButtonsMixin
import io.github.rwpp.mixin.hud.NativeMouseSelectionButtonMixin
import io.github.rwpp.mixin.hud.NativeSmartSelectionBuilderTypesMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeMouseControlMixinCompositionTest {
    @Test
    fun interfaceMouseOrderAndActionButtonHooksComposeWithPlanningOrderHooks() {
        assertTransformContainsHooks(
            "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine",
            listOf(
                NativeMouseOrderButtonsMixin::class.java,
                NativeInterfaceAccessMixin::class.java,
            ),
            listOf("NativeMouseOrderButtonsMixin" to "updateMouseOrders"),
        )
    }

    @Test
    fun battleSelectionButtonHookComposesWithSmartSelectionAndPlanning() {
        assertTransformContainsHooks(
            "com/corrodinggames/rts/gameFramework/gui/BattleInterface",
            listOf(
                NativeMouseSelectionButtonMixin::class.java,
                NativeSmartSelectionBuilderTypesMixin::class.java,
                BuildingPlanningDragSelectionMixin::class.java,
            ),
            listOf(
                "NativeMouseSelectionButtonMixin" to "dragSelectionMouseOrders",
                "NativeSmartSelectionBuilderTypesMixin" to "configuredTypeIsBuilderForMixedSelection",
                "BuildingPlanningDragSelectionMixin" to "routePlanningDrag",
            ),
        )
    }

    private fun assertTransformContainsHooks(
        target: String,
        mixins: List<Class<*>>,
        expected: List<Pair<String, String>>
    ) {
        AsmRegistry.clear()
        try {
            val source = javaClass.classLoader.getResourceAsStream("$target.class")!!.use { it.readBytes() }
            mixins.forEach(AsmRegistry::register)
            val transformed = AsmProcessor().transform(target, source, javaClass.classLoader)
            val node = ClassNode().also { ClassReader(transformed).accept(it, 0) }
            val calls = node.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
            expected.forEach { (mixin, method) ->
                assertTrue(
                    calls.any { it.owner.contains(mixin) && it.name == method },
                    "Missing $mixin.$method in $target"
                )
            }
            if (target.endsWith("InterfaceEngine")) {
                assertTrue(node.interfaces.contains("io/github/rwpp/mixin/hud/NativeInterfaceAccess"))
            }
        } finally {
            AsmRegistry.clear()
        }
    }
}
