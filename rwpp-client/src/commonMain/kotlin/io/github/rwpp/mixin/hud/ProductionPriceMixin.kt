package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.game.units.custom.price.CustomPrice
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
}
