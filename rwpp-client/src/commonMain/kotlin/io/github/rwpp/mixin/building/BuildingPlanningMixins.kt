/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.building

import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwpp.game.building.BuildingPlanningController
import kim.der.asm.api.annotation.*

internal interface NativeBuildingPlanningCommandAccess {
    fun rwppPlanningCommandUnits(): FastArrayList
    fun rwppPlanningPendingUnitIds(): FastArrayList
}

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
@AddInterface("io/github/rwpp/mixin/building/NativeBuildingPlanningCommandAccess")
abstract class NativeBuildingPlanningCommandAccessMixin {
    @Accessor("units")
    abstract fun rwppPlanningCommandUnits(): FastArrayList

    @Accessor("unitIdsPending")
    abstract fun rwppPlanningPendingUnitIds(): FastArrayList
}

/** BattleInterface.pinchTimer is package private, and the two-finger box must respect it. */
internal interface NativeBattlePinchAccess {
    fun rwppPinchTimer(): Float
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
@AddInterface("io/github/rwpp/mixin/building/NativeBattlePinchAccess")
abstract class NativeBattlePinchAccessMixin {
    @Accessor("pinchTimer")
    abstract fun rwppPinchTimer(): Float
}

/** Planning owns selection input before native drawing or selection can run. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object BuildingPlanningDragSelectionMixin {
    @AsmInject(
        method = "handleDragSelection(F)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun routePlanningDrag(callback: CallbackInfo) {
        val captured = BuildingPlanningController.handleNativeDragSelection()
        BuildingPlanningController.drawIndependentBlueprintPreview()
        if (BuildingPlanningController.ownsIndependentPlanningInput()) {
            (com.corrodinggames.rts.gameFramework.GameEngine.getInstance().interfaceEngine as? io.github.rwpp.mixin.hud.NativeInterfaceAccess)
                ?.rwppSetTapReleased(false)
        }
        if (captured) callback.cancel()
    }
}

/** Keeps a captured desktop right-drag from also issuing the native move order on release. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine", priority = 1200)
object BuildingPlanningSuppressCapturedMoveMixin {
    @AsmInject(
        method = "orderMove(FFLandroid/graphics/Point;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    fun suppressCapturedMove(callback: CallbackInfo) {
        if (BuildingPlanningController.shouldSuppressNativeMoveOrder()) callback.cancel()
    }

    @AsmInject(
        method = "orderAttack(Lcom/corrodinggames/rts/game/units/Unit;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    fun suppressCapturedAttack(callback: CallbackInfo) {
        if (BuildingPlanningController.shouldSuppressNativeMoveOrder()) callback.cancel()
    }

    @AsmInject(
        method = "orderAttackMove(FFLandroid/graphics/Point;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressCapturedAttackMove(callback: CallbackInfo) {
        if (BuildingPlanningController.shouldSuppressNativeMoveOrder()) callback.cancel()
    }

    @AsmInject(
        method = "orderPatrolWaypoint(FFLandroid/graphics/Point;Z)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressCapturedPatrol(callback: CallbackInfo) {
        if (BuildingPlanningController.shouldSuppressNativeMoveOrder()) callback.cancel()
    }
}

/** Leave native buttons and action queues out of the private preview interaction. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object BuildingPlanningIndependentInputMixin {
    @AsmInject(method = "handleUnitClick(Lcom/corrodinggames/rts/game/units/Unit;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun retainPlanningBuilders(callback: CallbackInfo) {
        if (BuildingPlanningController.ownsIndependentPlanningInput()) callback.cancel()
    }

    @ModifyExpressionValue(method = "update(F)V", require = 1,
        at = At(InjectionPoint.INVOKE, target = "com/corrodinggames/rts/gameFramework/GameEngine.hasTouchEvents()Z"))
    fun previewDragDoesNotPanCamera(value: Boolean): Boolean =
        value && !BuildingPlanningController.suppressNativeTouchPlacementPan()
}
