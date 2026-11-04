package io.github.rwpp.game.building

import io.github.rwpp.mixin.building.BuildingPlanningDragSelectionMixin
import io.github.rwpp.mixin.building.BuildingPlanningSuppressCapturedMoveMixin
import io.github.rwpp.mixin.hud.NativeSmartSelectionBuilderTypesMixin
import io.github.rwpp.mixin.orders.AeroNativeOrderClickMixin
import io.github.rwpp.mixin.orders.AeroNativeOrderEffectCaptureMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertTrue

class BuildingPlanningMixinCompositionTest {
    @Test
    fun interfaceEngineOrderCaptureAndPlanningCancellationComposeWithoutMovingNativePatrolBody() {
        assertTransformContainsHooks(
            target = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine",
            mixins = listOf(
                AeroNativeOrderEffectCaptureMixin::class.java,
                AeroNativeOrderClickMixin::class.java,
                BuildingPlanningSuppressCapturedMoveMixin::class.java,
            ),
            expectedOwnersAndNames = listOf(
                "AeroNativeOrderEffectCaptureMixin" to "patrolEffect",
                "BuildingPlanningSuppressCapturedMoveMixin" to "suppressCapturedPatrol",
            ),
        )
    }

    @Test
    fun battleInterfaceSmartSelectionOperationAndPlanningHeadInjectionCompose() {
        assertTransformContainsHooks(
            target = "com/corrodinggames/rts/gameFramework/gui/BattleInterface",
            mixins = listOf(
                NativeSmartSelectionBuilderTypesMixin::class.java,
                BuildingPlanningDragSelectionMixin::class.java,
            ),
            expectedOwnersAndNames = listOf(
                "NativeSmartSelectionBuilderTypesMixin" to "configuredBuilderDoesNotStartCombatMode",
                "NativeSmartSelectionBuilderTypesMixin" to "configuredTypeIsBuilderForMixedSelection",
                "NativeSmartSelectionBuilderTypesMixin" to "configuredTypeIsNonCombatBuilderForMixedSelection",
                "BuildingPlanningDragSelectionMixin" to "routePlanningDrag",
            ),
        )
    }

    private fun assertTransformContainsHooks(
        target: String,
        mixins: List<Class<*>>,
        expectedOwnersAndNames: List<Pair<String, String>>,
    ) {
        AsmRegistry.clear()
        try {
            val source = javaClass.classLoader.getResourceAsStream("$target.class")!!.use { it.readBytes() }
            mixins.forEach { AsmRegistry.register(it) }

            // A missing @WrapOperation target fails this transformation at require=1. Running the
            // existing order capture and planning mixins together protects against body-moving wrappers.
            val transformed = AsmProcessor().transform(target, source, javaClass.classLoader)
            val node = ClassNode().also { ClassReader(transformed).accept(it, 0) }
            val calls = node.methods.flatMap { method ->
                method.instructions.toArray().filterIsInstance<MethodInsnNode>()
            }

            expectedOwnersAndNames.forEach { (ownerPart, methodName) ->
                assertTrue(
                    calls.any { it.owner.contains(ownerPart) && it.name == methodName },
                    "Expected $ownerPart.$methodName in transformed $target",
                )
            }
        } finally {
            AsmRegistry.clear()
        }
    }
}
