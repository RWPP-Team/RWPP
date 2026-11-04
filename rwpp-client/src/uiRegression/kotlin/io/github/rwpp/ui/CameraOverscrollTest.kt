package io.github.rwpp.ui

import io.github.rwpp.game.camera.overscrollCameraAxis
import io.github.rwpp.mixin.camera.CameraOverscrollMixin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraOverscrollTest {
    @Test
    fun nativeClampCameraReceivesTheOptionalOverride() {
        kim.der.asm.AsmRegistry.clear()
        try {
            kim.der.asm.AsmRegistry.register(CameraOverscrollMixin::class.java)
            val target = "com/corrodinggames/rts/gameFramework/GameEngine"
            val node = org.objectweb.asm.tree.ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use {
                org.objectweb.asm.ClassReader(it).accept(node, 0)
            }
            assertTrue(kim.der.asm.transformer.AsmProcessor().applyAsms(target, node))
            val clamp = node.methods.single { it.name == "clampCamera" && it.desc == "()V" }
            assertTrue(clamp.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().any {
                it.owner == "io/github/rwpp/mixin/camera/CameraOverscrollMixin" && it.name == "clamp"
            })
        } finally {
            kim.der.asm.AsmRegistry.clear()
        }
    }

    @Test
    fun marginsPermitTravelPastBothEdgesWithoutRemovingTheLimit() {
        assertEquals(-160f, overscrollCameraAxis(-500f, 2000f, 800f, 160f))
        assertEquals(1360f, overscrollCameraAxis(1800f, 2000f, 800f, 160f))
        assertEquals(-100f, overscrollCameraAxis(-100f, 2000f, 800f, 160f))
        assertEquals(1200f, overscrollCameraAxis(1800f, 2000f, 800f, 0f))
    }

    @Test
    fun mapsSmallerThanTheViewKeepNativeCenteringAndAFiniteMargin() {
        assertEquals(-200f, overscrollCameraAxis(0f, 400f, 800f, 0f))
        assertEquals(-40f, overscrollCameraAxis(0f, 400f, 800f, 160f))
        assertEquals(-360f, overscrollCameraAxis(-1000f, 400f, 800f, 160f))
        assertEquals(0f, overscrollCameraAxis(Float.NaN, 2000f, 800f, Float.NaN))
    }
}
