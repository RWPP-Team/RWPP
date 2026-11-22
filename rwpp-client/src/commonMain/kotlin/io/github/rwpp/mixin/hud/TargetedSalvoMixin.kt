package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.game.hud.TargetedSalvo
import kim.der.asm.api.annotation.*

private const val GUI = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine"
private const val ACTION = "Lcom/corrodinggames/rts/game/units/actions/SpecialAction;"
private const val COMMAND = "Lcom/corrodinggames/rts/gameFramework/CommandControllerCommand;"
private const val ORDER = "orderSpecialActionTarget(${ACTION}FF)V"

// Install the operation inside the original method before WrapMethod moves its body.
@AsmMixin(GUI, priority = 1100)
object TargetedSalvoRecipientsMixin {
    internal var recipients: List<OrderableUnit>? = null

    @WrapOperation(
        method = ORDER,
        at = At(InjectionPoint.INVOKE, target = "$GUI.addSpecialActionUnitToCommand($COMMAND${ACTION}Z)V"),
        require = 1,
    )
    fun addRecipients(ui: Any, command: CommandControllerCommand, action: SpecialAction, remove: Boolean, original: Operation<Unit>) {
        val salvo = recipients
        if (salvo == null) original.call(ui, command, action, remove) else salvo.forEach(command::addUnit)
    }
}

@AsmMixin(GUI)
object TargetedSalvoMixin {
    @WrapOperation(
        method = "handleActiveSpecialAction(FFFLandroid/graphics/Point;)V",
        at = At(InjectionPoint.INVOKE, target = "$GUI.anyUnitCanQueueTargetedOrder(${ACTION}FF)Z"),
        require = 1,
    )
    fun invalidTarget(target: Any, action: SpecialAction, x: Float, y: Float, original: Operation<Boolean>): Boolean {
        val ui = target as InterfaceEngine
        return if (TargetedSalvo.isActive(ui, action)) TargetedSalvo.recipients(ui, action, x, y).isEmpty()
        else original.call(target, action, x, y)
    }

    @WrapMethod(method = ORDER, require = 1)
    fun order(action: SpecialAction, x: Float, y: Float, original: Operation<Unit>) {
        val ui = GameEngine.getInstance().interfaceEngine
        if (!TargetedSalvo.isActive(ui, action)) {
            original.call(action, x, y)
            return
        }
        val recipients = TargetedSalvo.recipients(ui, action, x, y)
        if (recipients.isEmpty()) return
        TargetedSalvoRecipientsMixin.recipients = recipients
        try {
            original.call(action, x, y)
        } finally {
            TargetedSalvoRecipientsMixin.recipients = null
        }
    }

    @AsmInject(method = "cancelActiveAction()Z", target = InjectionPoint.RETURN, require = 1)
    fun cancel() = TargetedSalvo.arm(null)
}
