package io.github.rwpp.ui

import io.github.rwpp.mixin.building.AeroTerrainEdgeBuildingCommandMixin
import io.github.rwpp.mixin.building.AeroTerrainEdgeBuildingPreviewMixin
import io.github.rwpp.mixin.hud.AeroPlacementPromptMixin
import io.github.rwpp.mixin.hud.NativeResourceHudMixin
import io.github.rwpp.mixin.nativeaccess.NativeCommandsAccessMixin
import io.github.rwpp.mixin.orders.AeroNativeOrderClickMixin
import io.github.rwpp.mixin.orders.AeroNativeOrderEffectCaptureMixin
import io.github.rwpp.mixin.replay.AeroObserverCommandAccessMixin
import io.github.rwpp.mixin.sandbox.NativeSandboxEditorCommandAccessMixin
import io.github.rwpp.mixin.sandbox.SandboxEditorForceBuildMixin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterfaceEngineMixinSignatureTest {
    @Test
    fun interfaceEngineMixinCallbacksDoNotResolveTheTargetThroughTheirJvmDescriptors() {
        val mixins = listOf(
            AeroTerrainEdgeBuildingCommandMixin::class.java,
            AeroTerrainEdgeBuildingPreviewMixin::class.java,
            AeroObserverCommandAccessMixin::class.java,
            AeroNativeOrderEffectCaptureMixin::class.java,
            AeroNativeOrderClickMixin::class.java,
            NativeCommandsAccessMixin::class.java,
            NativeResourceHudMixin::class.java,
            AeroPlacementPromptMixin::class.java,
            NativeSandboxEditorCommandAccessMixin::class.java,
            SandboxEditorForceBuildMixin::class.java,
        )
        val callbackAnnotations = setOf(
            "kim.der.asm.api.annotation.AsmInject",
            "kim.der.asm.api.annotation.ModifyReturnValue",
            "kim.der.asm.api.annotation.WrapMethod",
            "kim.der.asm.api.annotation.WrapOperation",
        )
        val targetName = "com.corrodinggames.rts.gameFramework.gui.InterfaceEngine"

        val declaredMethods = mixins.flatMap { mixin ->
            mixin.declaredMethods.map { method -> mixin to method }
        }
        val callbacks = declaredMethods.filter { (_, method) ->
            method.annotations.any { it.annotationClass.java.name in callbackAnnotations }
        }
        assertTrue(callbacks.isNotEmpty(), "InterfaceEngine mixins should expose annotated callbacks")
        val targetTypedMethod = declaredMethods.firstOrNull { (_, method) ->
            method.parameterTypes.any { it.name == targetName } || method.returnType.name == targetName
        }
        assertFalse(
            targetTypedMethod != null,
            "${targetTypedMethod?.first?.name}.${targetTypedMethod?.second?.name} must not mention the transforming target in its JVM descriptor",
        )
    }
}
