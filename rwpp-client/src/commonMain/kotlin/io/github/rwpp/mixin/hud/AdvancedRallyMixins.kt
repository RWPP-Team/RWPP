/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.game.hud.AdvancedRally
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapOperation
import com.corrodinggames.rts.game.units.Unit as GameUnit

private const val HANDLE_ACTION = "handleActiveSpecialAction(FFFLandroid/graphics/Point;)V"

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object AdvancedRallyInputMixin {
    private var keepRallyTarget = false

    @WrapOperation(
        method = HANDLE_ACTION,
        at = At(InjectionPoint.INVOKE, target = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine.orderSetRallyPoint(FF)V"),
        require = 1,
    )
    fun recordRallyPoint(ui: Any, x: Float, y: Float, original: Operation<kotlin.Unit>) {
        keepRallyTarget = AdvancedRally.recordPoint(GameEngine.getInstance(), x, y)
        if (!keepRallyTarget) original.call(ui, x, y)
    }

    @WrapOperation(
        method = HANDLE_ACTION,
        at = At(InjectionPoint.INVOKE, target = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine.cancelActiveAction()Z"),
        ordinal = -1,
        require = 1,
    )
    fun continueRallyTargeting(ui: Any, original: Operation<Boolean>): Boolean {
        // Native closes rally targeting immediately after accepting a point. Keep it active
        // only for that call. Native Cancel and every other action keep their normal behavior.
        if (!keepRallyTarget) return original.call(ui)
        keepRallyTarget = false
        return false
    }

    @AsmInject(method = "cancelActiveAction()Z", target = InjectionPoint.RETURN, require = 1)
    fun discardRallyDraft() = AdvancedRally.nativeActionCancelled()

    @AsmInject(method = "orderSetRallyPoint(FF)V", target = InjectionPoint.HEAD, require = 1)
    fun ordinaryRally(target: Any) = AdvancedRally.replaceWithNativeRally(target as InterfaceEngine)
}

@AsmMixin("com/corrodinggames/rts/game/units/buildings/FactoryBuildQueue")
object AdvancedRallyFactoryMixin {
    @AsmInject(
        method = "queueExitWaypoints(Lcom/corrodinggames/rts/game/units/Unit;FZ)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun queueRoute(target: Any, produced: Any, exitOffset: Float) = AdvancedRally.unitProduced(
        GameEngine.getInstance(), target as FactoryBuildQueue, produced as GameUnit, exitOffset,
    )
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object AdvancedRallyWorldMixin {
    @AsmInject(method = "clearAllGameObjects()V", target = InjectionPoint.TAIL, require = 1)
    fun clearRallyRoutes() = AdvancedRally.resetWorld()
}
