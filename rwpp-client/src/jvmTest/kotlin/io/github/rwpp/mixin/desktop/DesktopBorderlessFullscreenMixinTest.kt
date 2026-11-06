package io.github.rwpp.mixin.desktop

import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopBorderlessFullscreenMixinTest {
    @Test
    fun `applyResolution is forced onto the borderless fullscreen branch`() {
        val className = "com/corrodinggames/rts/java/SlickMainGame"
        val classLoader = javaClass.classLoader
        val original = requireNotNull(classLoader.getResourceAsStream("$className.class")) {
            "named game-lib target $className is missing from the JVM test classpath"
        }.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(DesktopBorderlessFullscreenMixin::class.java)
            val transformed = AsmProcessor().transform(className, original, classLoader)
            assertTrue(!original.contentEquals(transformed), "applyResolution must be transformed")
            val node = ClassNode()
            ClassReader(transformed).accept(node, 0)
            val method = node.methods.single { it.name == "applyResolution" && it.desc == "()V" }
            assertTrue(
                method.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                    it.owner == "io/github/rwpp/mixin/desktop/DesktopBorderlessFullscreenMixin" &&
                            it.name == "useBorderlessFullscreen"
                },
                "the borderless override must run before the native display-mode choice",
            )
        } finally {
            AsmRegistry.clear()
        }
    }
}
