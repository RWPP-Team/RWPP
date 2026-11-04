package io.github.rwpp.ui

import io.github.rwpp.mixin.graphics.*
import io.github.rwpp.mixin.nativeaccess.*
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAccessBridgeTest {
    @Test
    fun accessorsAndInvokersMatchActualNativeFieldsAndDescriptors() {
        val targets = listOf(
            "android/graphics/Paint" to NativePaintFlagsAccessMixin::class.java,
            "com/corrodinggames/rts/game/RWGameEngine" to NativeWorldAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/BattleInterface" to NativeBattleAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine" to NativeCommandsAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/MessageInterface" to NativeMessageListAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/MessageInterfaceMessageItem" to NativeMessageAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/WarLogInterface" to NativeWarLogListAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/WarLogInterfaceWarLogItem" to NativeWarLogAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/MinimapHandler" to NativeMinimapAnimationAccessMixin::class.java,
            "com/corrodinggames/rts/gameFramework/gui/MinimapHandlerDamageNotification" to NativeMinimapNotificationAccessMixin::class.java,
            "com/corrodinggames/rts/game/units/buildings/Factory" to NativeFactoryQueueAccessMixin::class.java,
            "com/corrodinggames/rts/game/units/custom/CustomUnit" to NativeCustomQueueAccessMixin::class.java,
            "com/corrodinggames/rts/game/map/MapLayerBuffer" to NativeMapBufferAccessMixin::class.java,
            "com/corrodinggames/rts/game/RWGameEngine" to NativeDesktopWorldAccessMixin::class.java,
            "com/corrodinggames/rts/game/InGameImage" to NativePostImageAccessMixin::class.java,
        )
        val nativeInterfaceOwners = mapOf(
            NativeBattleAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeBattleAccess",
            NativeCommandsAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeCommandsAccess",
            NativeMessageListAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeMessageListAccess",
            NativeMessageAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeMessageAccess",
            NativeWarLogListAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeWarLogListAccess",
            NativeWarLogAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeWarLogAccess",
            NativeMinimapAnimationAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeMinimapAnimationAccess",
            NativeMinimapNotificationAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeMinimapNotificationAccess",
            NativeFactoryQueueAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeFactoryQueueAccess",
            NativeCustomQueueAccessMixin::class.java to "io/github/rwpp/game/nativeaccess/NativeFactoryQueueAccess",
        )
        for ((name, mixin) in targets) {
            AsmRegistry.clear()
            try {
                val original = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
                AsmRegistry.register(mixin)
                val result = AsmProcessor().transform(name, original, javaClass.classLoader)
                val node = ClassNode(); ClassReader(result).accept(node, 0)
                val bridges = node.methods.filter { it.name.startsWith("rwpp") }
                assertTrue(bridges.isNotEmpty(), name)
                nativeInterfaceOwners[mixin]?.let { owner ->
                    assertTrue(node.interfaces.contains(owner), "$name should implement $owner")
                }
                for (method in bridges) assertFalse(
                    method.instructions.toArray().filterIsInstance<MethodInsnNode>()
                        .any { it.owner.startsWith("java/lang/reflect") }, "$name.${method.name}"
                )
            } finally {
                AsmRegistry.clear()
            }
        }
    }
}
