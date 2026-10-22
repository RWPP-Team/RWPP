/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.api.event.hooks

import io.github.rwml.api.ClassFilePatch
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*

/** Direct calls keep the native overlay boundary free of reflective Operation objects. */
class UnitOverlayClassPatch : ClassFilePatch {
    override fun transform(internalName: String, classfileBuffer: ByteArray, loader: ClassLoader?): ByteArray {
        check(internalName == UNIT) { "Unexpected overlay patch target: $internalName" }
        val reader = ClassReader(classfileBuffer)
        val type = ClassNode()
        reader.accept(type, 0)
        check(type.name == UNIT) { "Overlay class name mismatch: ${type.name}" }
        check(type.methods.none { it.name == BODY || it.name == NATIVE }) { "Overlay scope already installed" }
        val body = type.methods.single { it.name == "drawOverlay" && it.desc == "(FZ)V" }
        val access = body.access
        val native = MethodNode(ASM9, (access and (ACC_PUBLIC or ACC_PROTECTED).inv()) or ACC_PRIVATE or ACC_SYNTHETIC,
            NATIVE, body.desc, body.signature, body.exceptions.toTypedArray())
        body.accept(native)
        // Preserve every other extension's injections. Only undo this API's own rectangle redirect.
        var redirects = 0
        for (instruction in native.instructions.toArray()) {
            if (instruction is MethodInsnNode && instruction.owner == BAR && instruction.name == "drawNativeOverlayBar") {
                check(instruction.opcode == INVOKESTATIC) { "Overlay redirect must be static" }
                native.instructions.set(instruction, MethodInsnNode(INVOKEINTERFACE, GRAPHICS, "drawRect",
                    "(Landroid/graphics/RectF;Landroid/graphics/Paint;)V", true))
                redirects++
            }
        }
        check(redirects > 0) { "Native overlay rectangle redirect missing" }
        native.name = NATIVE
        native.access = (access and (ACC_PUBLIC or ACC_PROTECTED).inv()) or ACC_PRIVATE or ACC_SYNTHETIC
        body.name = BODY
        body.access = native.access
        val wrapper = MethodNode(ASM9, access, "drawOverlay", body.desc, body.signature, body.exceptions.toTypedArray())
        val tracked = LabelNode()
        val start = LabelNode()
        val end = LabelNode()
        val failed = LabelNode()
        wrapper.instructions.apply {
            add(MethodInsnNode(INVOKESTATIC, CONTEXT, "shouldTrack", "()Z", false))
            add(JumpInsnNode(IFNE, tracked))
            add(VarInsnNode(ALOAD, 0)); add(VarInsnNode(FLOAD, 1)); add(VarInsnNode(ILOAD, 2))
            add(MethodInsnNode(INVOKESPECIAL, type.name, NATIVE, "(FZ)V", false))
            add(InsnNode(RETURN))
            add(tracked)
            add(FrameNode(F_SAME, 0, null, 0, null))
            add(VarInsnNode(ALOAD, 0))
            add(MethodInsnNode(INVOKESTATIC, CONTEXT, "enter", "(Ljava/lang/Object;)I", false))
            add(VarInsnNode(ISTORE, 3))
            add(start)
            add(VarInsnNode(ALOAD, 0)); add(VarInsnNode(FLOAD, 1)); add(VarInsnNode(ILOAD, 2))
            add(MethodInsnNode(INVOKESPECIAL, type.name, BODY, "(FZ)V", false))
            add(end)
            add(VarInsnNode(ILOAD, 3))
            add(MethodInsnNode(INVOKESTATIC, CONTEXT, "restoreDepth", "(I)V", false))
            add(InsnNode(RETURN))
            add(failed)
            add(FrameNode(F_FULL, 4, arrayOf<Any>(type.name, FLOAT, INTEGER, INTEGER), 1, arrayOf("java/lang/Throwable")))
            add(VarInsnNode(ASTORE, 4)); add(VarInsnNode(ILOAD, 3))
            add(MethodInsnNode(INVOKESTATIC, CONTEXT, "restoreDepth", "(I)V", false))
            add(VarInsnNode(ALOAD, 4)); add(InsnNode(ATHROW))
        }
        wrapper.tryCatchBlocks.add(TryCatchBlockNode(start, end, failed, null))
        type.methods.add(native)
        type.methods.add(wrapper)
        // Existing bodies retain their verified frames; the small wrapper has explicit frames.
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        type.accept(writer)
        return writer.toByteArray()
    }

    private companion object {
        const val UNIT = "com/corrodinggames/rts/game/units/Unit"
        const val BODY = "rwml\$overlayWithEvents"
        const val NATIVE = "rwml\$overlayWithoutEvents"
        const val CONTEXT = "io/github/rwml/api/event/hooks/UnitOverlayDrawContext"
        const val BAR = "io/github/rwml/api/event/hooks/UnitOverlayBarMixin"
        const val GRAPHICS = "com/corrodinggames/rts/gameFramework/render/GraphicsEngine"
    }
}
