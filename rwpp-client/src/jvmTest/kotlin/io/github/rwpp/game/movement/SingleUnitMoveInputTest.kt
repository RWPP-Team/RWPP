package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import io.github.rwpp.mixin.building.BuildingPlanningSuppressCapturedMoveMixin
import io.github.rwpp.mixin.hud.NativeInterfaceAccessMixin
import io.github.rwpp.mixin.movement.SingleUnitMoveInputMixin
import io.github.rwpp.mixin.movement.SingleUnitMoveCommandMixin
import io.github.rwpp.mixin.orders.AeroNativeOrderClickMixin
import io.github.rwpp.mixin.orders.AeroNativeOrderEffectCaptureMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class SingleUnitMoveInputTest {
    @Test
    fun localInputHooksComposeWithExistingMoveCaptureAndBuildingSuppression() {
        val name = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine"
        val loader = javaClass.classLoader
        val source = loader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            listOf(
                AeroNativeOrderEffectCaptureMixin::class.java,
                AeroNativeOrderClickMixin::class.java,
                BuildingPlanningSuppressCapturedMoveMixin::class.java,
                NativeInterfaceAccessMixin::class.java,
                SingleUnitMoveInputMixin::class.java,
            ).forEach(AsmRegistry::register)
            val node = ClassNode().also { ClassReader(AsmProcessor().transform(name, source, loader)).accept(it, 0) }
            fun calls(method: String, owner: String, call: String): Boolean = node.methods.filter { it.name == method }
                .flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
                .any { it.owner.endsWith(owner) && it.name == call }
            assertFalse(calls("getPlayerCommand", "SingleUnitMoveInputMixin", "beforePlayerCommand"),
                "An empty command has no addressed unit and must not interrupt assistance")
            assertTrue(calls("addSelectedUnitsToCommand", "SingleUnitMoveInputMixin", "afterSelectedUnits"))
            assertTrue(calls("update", "SingleUnitMoveInputMixin", "afterLocalUpdate"))
            assertTrue(node.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
                .any { it.owner.contains("AeroNativeOrderEffectCaptureMixin") && it.name == "moveEffect" })
            assertTrue(node.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
                .any { it.owner.contains("BuildingPlanningSuppressCapturedMoveMixin") && it.name == "suppressCapturedMove" })
            assertTrue(node.interfaces.contains("io/github/rwpp/mixin/hud/NativeInterfaceAccess"))
            assertFalse(node.methods.any { it.name == "queueCommand" }, "This adapter must not hook the received-command queue")
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun directUnitHookRegistersLocalInputAndObservesNativeApplicationWithoutReplacingIt() {
        val name = "com/corrodinggames/rts/gameFramework/CommandControllerCommand"
        val loader = javaClass.classLoader
        val source = loader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(SingleUnitMoveCommandMixin::class.java)
            val node = ClassNode().also { ClassReader(AsmProcessor().transform(name, source, loader)).accept(it, 0) }
            fun calls(method: String, callback: String) = node.methods.filter { it.name == method }
                .flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
                .any { it.owner.endsWith("SingleUnitMoveCommandMixin") && it.name == callback }
            assertTrue(calls("addUnit", "afterUnitAdded"))
            assertTrue(calls("issueCommand", "afterNativeApplication"), "Only actual native execution acknowledges an input")
            val issue = node.methods.single { it.name == "issueCommand" }
            assertEquals(issue.instructions.toArray().count { it.opcode == org.objectweb.asm.Opcodes.RETURN },
                issue.instructions.toArray().filterIsInstance<MethodInsnNode>().count {
                    it.owner.endsWith("SingleUnitMoveCommandMixin") && it.name == "afterNativeApplication"
                }, "TAIL must observe MOVE/BUILD's native early return as well as the final return")
            assertFalse(calls("issueCommand", "afterUnitAdded"), "Received commands must never register a fresh local input")
            assertFalse(calls("resolvePendingUnitIds", "afterUnitAdded"))
        } finally { AsmRegistry.clear() }
    }

    @Test
    fun queuedMoveInputIsAcceptedWhileReplayAndSystemCommandsRemainNative() {
        val command = CommandControllerCommand(null).apply { actionId = ActionId.EMPTY; setMoveWaypoint(80f, 120f); queueOrder = true }
        assertTrue(isSupportedMoveInput(command))
        assertFalse(isSupportedMoveInput(command.apply { isReplayCommand = true }))
        assertFalse(isSupportedMoveInput(command.apply { isReplayCommand = false; hasSystemAction = true }))
    }

    @Test
    fun unsupportedOrdersKeepNativeBehavior() {
        fun move() = CommandControllerCommand(null).apply {
            actionId = ActionId.EMPTY
            setMoveWaypoint(80f, 120f)
        }
        assertTrue(isSupportedMoveInput(move()))
        assertTrue(isSupportedMoveInput(move().apply { queueOrder = true }))
        assertFalse(isSupportedMoveInput(move().apply { setClearWaypointsFirst() }))
        assertFalse(isSupportedMoveInput(move().apply { finishNonBuildWaypoints = true }))
        assertFalse(isSupportedMoveInput(move().apply { removeFromQueue = true }))
        assertFalse(isSupportedMoveInput(move().apply { setAttackMoveWaypoint(80f, 120f) }))
        assertFalse(isSupportedMoveInput(CommandControllerCommand(null)))
    }
}
