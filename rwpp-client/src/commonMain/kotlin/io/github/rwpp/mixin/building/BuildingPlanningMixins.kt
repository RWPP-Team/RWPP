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

/** Replaces only native drag-box handling while a planner gesture owns the pointer. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object BuildingPlanningDragSelectionMixin {
    @AsmInject(
        method = "handleDragSelection(F)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun routePlanningDrag(callback: CallbackInfo) {
        if (BuildingPlanningController.handleNativeDragSelection()) callback.cancel()
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
