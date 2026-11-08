package io.github.rwpp.mixin.desktop

import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertTrue

class ForceDisableMouseCaptureMixinTest {
    @Test
    fun `the native grab decision starts from a disabled capture flag`() {
        val className = "com/corrodinggames/rts/java/SlickMainGame"
        val classLoader = javaClass.classLoader
        val original = requireNotNull(classLoader.getResourceAsStream("$className.class")) {
            "named game-lib target $className is missing from the JVM test classpath"
        }.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(ForceDisableMouseCaptureMixin::class.java)
            val transformed = AsmProcessor().transform(className, original, classLoader)
            assertTrue(!original.contentEquals(transformed), "updateContainerSettings must be transformed")
            val node = ClassNode()
            ClassReader(transformed).accept(node, 0)
            val method = node.methods.single { it.name == "updateContainerSettings" && it.desc == "()V" }
            assertTrue(
                method.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                    it.owner == "io/github/rwpp/mixin/desktop/ForceDisableMouseCaptureMixin" &&
                            it.name == "disableCapture"
                },
                "the capture flag must be cleared before the native grab branch reads it",
            )
        } finally {
            AsmRegistry.clear()
        }
    }
}
