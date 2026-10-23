package io.github.rwpp.api.net.impl

import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeTcpMixinTest {
    @Test fun bothNativeEndpointsAndConnectionWorkersAreTransformed() {
        val targets = listOf(
            "NetworkEngine" to NativeTcpConnectMixin::class.java,
            "NewConnectionRunnable" to NativeTcpListenMixin::class.java,
            "Connection" to NativeTcpConnectionMixin::class.java,
        )
        for ((name, mixin) in targets) {
            val target = "com/corrodinggames/rts/gameFramework/network/$name"
            val bytes = javaClass.classLoader.getResourceAsStream("$target.class")!!.use { it.readBytes() }
            AsmRegistry.clear()
            try {
                AsmRegistry.register(mixin)
                if (name == "NetworkEngine") AsmRegistry.register(NativeAddressConnectMixin::class.java)
                val transformed = AsmProcessor().transform(target, bytes, javaClass.classLoader)
                val node = ClassNode()
                ClassReader(transformed).accept(node, 0)
                assertTrue(node.methods.flatMap { it.instructions.toArray().toList() }
                    .filterIsInstance<MethodInsnNode>().any { it.owner == mixin.name.replace('.', '/') }, name)
            } finally { AsmRegistry.clear() }
        }
    }
}
