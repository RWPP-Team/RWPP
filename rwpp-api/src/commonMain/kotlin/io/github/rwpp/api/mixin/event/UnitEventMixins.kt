/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.event

import io.github.rwpp.api.event.hooks.FactoryUnitCreationEventCallbacks
import io.github.rwpp.api.event.hooks.UnitEventCallbacks
import io.github.rwpp.api.event.hooks.UnitSpawnRegistryCallbacks
import io.github.rwpp.api.event.hooks.UnitUpgradeEventCallbacks
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/units/Unit")
object UnitEventMixin {
    @AsmInject(method = "<init>(Z)V", target = InjectionPoint.TAIL, require = 1)
    fun stageSpawnCandidate(target: Any, isPlaceholder: Boolean) =
        UnitEventCallbacks.stageSpawnCandidate(target, isPlaceholder)

    @AsmInject(
        method = "damage(Lcom/corrodinggames/rts/game/units/Unit;FLcom/corrodinggames/rts/game/Projectile;)F",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun captureDamage(target: Any, attacker: Any?, damage: Float, projectile: Any?) =
        UnitEventCallbacks.captureDamage(target, attacker, damage, projectile)

    @AsmInject(
        method = "damage(Lcom/corrodinggames/rts/game/units/Unit;FLcom/corrodinggames/rts/game/Projectile;)F",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishDamage(callback: CallbackInfo, target: Any, attacker: Any?, requestedDamage: Float, projectile: Any?) =
        UnitEventCallbacks.publishDamage(
            target,
            attacker,
            requestedDamage,
            projectile,
            callback.getReturnValue<Float>() ?: 0.0F,
        )

    @AsmInject(method = "setBuildProgress(F)V", target = InjectionPoint.HEAD, require = 1)
    fun captureBuildProgress(target: Any, progress: Float) = UnitEventCallbacks.captureBuildProgress(target, progress)

    @AsmInject(method = "setBuildProgress(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishConstructionCompleted(target: Any, progress: Float) =
        UnitEventCallbacks.publishConstructionCompleted(target, progress)

    @AsmInject(method = "setTeam(Lcom/corrodinggames/rts/game/Team;)V", target = InjectionPoint.HEAD, require = 1)
    fun captureTeamChange(target: Any, team: Any?) = UnitEventCallbacks.captureTeamChange(target, team)

    @AsmInject(method = "setTeam(Lcom/corrodinggames/rts/game/Team;)V", target = InjectionPoint.TAIL, require = 1)
    fun publishTeamChange(target: Any, team: Any?) = UnitEventCallbacks.publishTeamChange(target, team)

    @AsmInject(method = "kill()V", target = InjectionPoint.HEAD, require = 1)
    fun captureKill(target: Any) = UnitEventCallbacks.captureKill(target)

    @AsmInject(method = "kill()V", target = InjectionPoint.TAIL, require = 1)
    fun publishDeath(target: Any) = UnitEventCallbacks.publishDeath(target)

    @AsmInject(method = "destroy()V", target = InjectionPoint.HEAD, require = 1)
    fun captureRemoval(target: Any) = UnitEventCallbacks.captureRemoval(target)

    @AsmInject(method = "destroy()V", target = InjectionPoint.TAIL, require = 1)
    fun publishRemoval(target: Any) = UnitEventCallbacks.publishRemoval(target)

    @AsmInject(
        method = "getNewUnits()Lcom/corrodinggames/rts/gameFramework/utility/DeferredArrayList;",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishCommittedUnits(callback: CallbackInfo) = UnitEventCallbacks.publishCommittedUnits()

    @AsmInject(method = "applyDeferredUnitChanges()V", target = InjectionPoint.TAIL, require = 1)
    fun publishAppliedDeferredUnits() = UnitEventCallbacks.publishAppliedDeferredUnits()
}

@AsmMixin("com/corrodinggames/rts/game/units/OrderableUnit")
object UnitUpgradeEventMixin {
    @AsmInject(method = "onUnitUpgraded()V", target = InjectionPoint.TAIL, require = 1)
    fun publishUpgrade(target: Any) = UnitUpgradeEventCallbacks.publishUpgrade(target)
}

@AsmMixin("com/corrodinggames/rts/game/units/buildings/FactoryBuildQueue")
object FactoryUnitCreationEventMixin {
    @AsmInject(
        method = "spawnUnit(Lcom/corrodinggames/rts/game/units/UnitTypeInterface;FZF)Lcom/corrodinggames/rts/game/units/Unit;",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishFactoryUnitCreated(
        callback: CallbackInfo,
        target: Any,
        unitType: Any?,
        exitOffset: Float,
        forceExit: Boolean,
        angle: Float,
    ) =
        FactoryUnitCreationEventCallbacks.publishFactoryUnitCreated(callback.getReturnValue<com.corrodinggames.rts.game.units.Unit>())
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object UnitSpawnRegistryMixin {
    @AsmInject(method = "clearAllGameObjects()V", target = InjectionPoint.TAIL, require = 1)
    fun clearCommittedUnits() = UnitSpawnRegistryCallbacks.clearCommittedUnits()
}
