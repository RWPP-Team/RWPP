package io.github.rwpp.mixin.building

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.building.ConstructionAssistance
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
object ConstructionAssistCommandMixin {
    @AsmInject(method = "issueCommand()V", target = InjectionPoint.TAIL, require = 1)
    fun applied(target: Any) = ConstructionAssistance.onCommandApplied(target as CommandControllerCommand)
}

@AsmMixin("com/corrodinggames/rts/game/units/OrderableUnit")
object ConstructionAssistWaypointMixin {
    @AsmInject(method = "popWaypoint()V", target = InjectionPoint.HEAD, require = 1)
    fun observeCompletedConstruction(target: Any) = ConstructionAssistance.beforeWaypointRemoved(target as OrderableUnit)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object ConstructionAssistUpdateMixin {
    @AsmInject(method = "update(F)V", target = InjectionPoint.TAIL, require = 1)
    fun assignIdleHelpers(target: Any) {
        val engine = GameEngine.getInstance() ?: return
        if (engine.interfaceEngine === target) ConstructionAssistance.update(engine)
    }
}
