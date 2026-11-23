package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.game.units.custom.price.CustomPrice
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import io.github.rwpp.game.hud.OverflowProductionQueue
import io.github.rwpp.game.hud.ProductionPriceProbe
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/game/units/actions/SpecialAction")
object ProductionPriceMixin {
    @WrapOperation(
        method = "canAffordForUnit(Lcom/corrodinggames/rts/game/units/Unit;Z)Z",
        at = At(InjectionPoint.INVOKE, target =
            "com/corrodinggames/rts/game/units/custom/price/CustomPrice.canAffordWithLagHiding(Lcom/corrodinggames/rts/game/units/Unit;Z)Z"),
        require = 1,
    )
    fun checkResourceAmounts(price: CustomPrice, owner: Unit, useLagHiding: Boolean, original: Operation<Boolean>): Boolean =
        ProductionPriceProbe.allows(price, owner) ?: original.call(price, owner, useLagHiding)

    @WrapOperation(
        method = "canAffordForUnit(Lcom/corrodinggames/rts/game/units/Unit;Z)Z",
        at = At(InjectionPoint.INVOKE, target =
            "com/corrodinggames/rts/game/units/custom/price/CustomPrice.canAfford(Lcom/corrodinggames/rts/game/units/Unit;)Z"),
        require = 1,
    )
    fun checkActualResources(price: CustomPrice, owner: Unit, original: Operation<Boolean>): Boolean =
        ProductionPriceProbe.allows(price, owner) ?: original.call(price, owner)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
object DeferredProductionCommandMixin {
    @AsmInject(method = "applyQueuedActionBookkeeping()V", target = InjectionPoint.TAIL, require = 1)
    fun sent(target: Any) = OverflowProductionQueue.recordNetworkSend(target as CommandControllerCommand)

    @AsmInject(method = "issueCommand()V", target = InjectionPoint.HEAD, require = 1)
    fun beforeIssue(target: Any) = OverflowProductionQueue.beforeApplication(target as CommandControllerCommand)

    @AsmInject(method = "issueCommand()V", target = InjectionPoint.RETURN, require = 1)
    fun afterIssue(target: Any) = OverflowProductionQueue.afterApplication(target as CommandControllerCommand)
}
