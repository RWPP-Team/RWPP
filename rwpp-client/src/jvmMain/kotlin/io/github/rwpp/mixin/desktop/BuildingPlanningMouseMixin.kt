/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.desktop

import io.github.rwpp.game.building.BuildingPlanningController
import kim.der.asm.api.annotation.*

internal interface NativePlannerMouseAccess {
    fun rwppPreparePlannerMouse(x: Int, y: Int, relative: Boolean)
    fun rwppPlannerMouseX(): Float
    fun rwppPlannerMouseY(): Float
}

@AsmMixin("com/corrodinggames/rts/java/SlickMainGame")
@AddInterface("io/github/rwpp/mixin/desktop/NativePlannerMouseAccess")
abstract class NativePlannerMouseAccessMixin {
    @Invoker("processMouseGrab")
    abstract fun rwppPreparePlannerMouse(x: Int, y: Int, relative: Boolean)
    @Accessor("mouseX")
    abstract fun rwppPlannerMouseX(): Float
    @Accessor("mouseY")
    abstract fun rwppPlannerMouseY(): Float
}

/** Compose hit testing runs first; claim remaining world gestures before native TouchEvent input. */
@AsmMixin("com/corrodinggames/rts/java/SlickMainGame")
object BuildingPlanningMouseMixin {
    private fun route(self: Any, phase: Int, button: Int, x: Int, y: Int): Boolean {
        if (!BuildingPlanningController.wantsDesktopMouse()) return false
        if (phase != 0 && !BuildingPlanningController.hasDesktopMouseCapture()) return false
        val access = self as NativePlannerMouseAccess
        access.rwppPreparePlannerMouse(x, y, phase == 1)
        return BuildingPlanningController.onDesktopMouse(
            phase, button,
            access.rwppPlannerMouseX(), access.rwppPlannerMouseY()
        )
    }

    @AsmInject(method = "mousePressed(III)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun pressed(callback: CallbackInfo, self: Any, button: Int, x: Int, y: Int) {
        if (route(self, 0, button, x, y)) callback.cancel()
    }

    @AsmInject(method = "mouseReleased(III)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun released(callback: CallbackInfo, self: Any, button: Int, x: Int, y: Int) {
        if (route(self, 2, button, x, y)) callback.cancel()
    }

    @AsmInject(method = "mouseDragged(IIII)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun dragged(callback: CallbackInfo, self: Any, oldX: Int, oldY: Int, x: Int, y: Int) {
        if (route(self, 1, -1, x, y)) callback.cancel()
    }
}
