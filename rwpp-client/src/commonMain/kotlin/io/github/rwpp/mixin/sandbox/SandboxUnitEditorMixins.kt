/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.sandbox

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.EditorUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwpp.game.sandbox.dispatchSandboxUnitEdit
import io.github.rwpp.game.sandbox.sandboxNativeEditorActionsAllowed
import io.github.rwpp.game.sandbox.shouldForceEditorBuild
import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.*
import com.corrodinggames.rts.game.units.Unit as GameUnit


@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
@AddInterface("io/github/rwpp/game/sandbox/NativeSandboxEditorCommandAccess")
abstract class NativeSandboxEditorCommandAccessMixin {
    @Invoker("getPlayerCommand")
    abstract fun rwppCreatePlayerCommand(): CommandControllerCommand
}

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
@AddInterface("io/github/rwpp/game/sandbox/NativeSandboxEditorPayloadAccess")
abstract class NativeSandboxEditorPayloadAccessMixin {
    @Accessor("units")
    abstract fun rwppCommandUnits(): FastArrayList

    @Accessor("unitIdsPending")
    abstract fun rwppPendingUnitIds(): FastArrayList

    @Invoker("resolvePendingUnitIds")
    abstract fun rwppResolvePendingUnitIds()
}

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandController")
object NativeSandboxEditorCommandDispatchMixin {
    private const val ISSUE_COMMAND =
        "com/corrodinggames/rts/gameFramework/CommandControllerCommand.issueCommand()V"

    @WrapOperation(
        method = "issueLocalCommands()V",
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = ISSUE_COMMAND),
        require = 1,
    )
    fun dispatchLocal(command: CommandControllerCommand, original: Operation<kotlin.Unit>) {
        if (!dispatchSandboxUnitEdit(command)) original.call(command)
    }

    @WrapOperation(
        method = "issueScheduledCommands()V",
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = ISSUE_COMMAND),
        require = 1,
    )
    fun dispatchScheduled(command: CommandControllerCommand, original: Operation<kotlin.Unit>) {
        if (!dispatchSandboxUnitEdit(command)) original.call(command)
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/ReplayEngine")
object NativeSandboxReplayCommandDispatchMixin {
    private const val ISSUE_COMMAND =
        "com/corrodinggames/rts/gameFramework/CommandControllerCommand.issueCommand()V"

    @WrapOperation(
        method = "processReplayFrame(F)V",
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = ISSUE_COMMAND),
        require = 1,
    )
    fun dispatchReplay(command: CommandControllerCommand, original: Operation<kotlin.Unit>) {
        if (!dispatchSandboxUnitEdit(command)) original.call(command)
    }
}


private fun isSandboxEditorBuildPlacement(ui: InterfaceEngine): Boolean {
    val engine = GameEngine.getInstance()
    val action = ui.activeSpecialAction ?: return false
    return shouldForceEditorBuild(
        editorUnit = ui.specialActionSourceUnit is EditorUnit,
        placementAction = action.actionType == ActionType.placeBuilding && action.unitType != null,
        sandbox = sandboxNativeEditorActionsAllowed(engine),
        optionEnabled = GameVisualSettings.get()?.sandboxForceBuild == true,
    )
}


private const val HANDLE_BUILDING_PLACEMENT = "handleBuildingPlacement(FFLandroid/graphics/Point;)V"
private const val CAN_AFFORD_FOR_UNIT =
    "com/corrodinggames/rts/game/units/actions/SpecialAction.canAffordForUnit(" +
            "Lcom/corrodinggames/rts/game/units/Unit;Z)Z"
private const val PLACEMENT_ERROR =
    "com/corrodinggames/rts/game/units/OrderableUnit.getPlacementError(" +
            "ZLcom/corrodinggames/rts/game/Team;)Ljava/lang/String;"
private const val BLUEPRINT_OVERLAP =
    "com/corrodinggames/rts/gameFramework/effects/Blueprint.isFootprintOverBlueprint(" +
            "Lcom/corrodinggames/rts/game/Team;Lcom/corrodinggames/rts/game/units/OrderableUnit;I)Z"
private const val CAN_ACT_INDEPENDENTLY =
    "com/corrodinggames/rts/game/units/OrderableUnit.canActIndependently()Z"
private const val CHECK_PLACEMENT_METHOD =
    "checkPlacementPosition(Lcom/corrodinggames/rts/game/units/OrderableUnit;FFZZ" +
            "Lcom/corrodinggames/rts/game/units/Unit;)Z"
private const val CAN_PLACE_AT =
    "com/corrodinggames/rts/game/units/OrderableUnit.canPlaceAt(Lcom/corrodinggames/rts/game/Team;)Z"
private const val SET_BUILD_WAYPOINT =
    "com/corrodinggames/rts/gameFramework/CommandControllerCommand.setBuildWaypoint(" +
            "FFLcom/corrodinggames/rts/game/units/UnitTypeInterface;I)V"

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine", priority = 900)
object SandboxEditorForceBuildMixin {
    @WrapOperation(
        method = HANDLE_BUILDING_PLACEMENT,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = CAN_AFFORD_FOR_UNIT),
        require = 1,
    )
    fun bypassEditorBuildCost(
        action: SpecialAction,
        unit: GameUnit,
        lagHiding: Boolean,
        original: Operation<Boolean>,
    ): Boolean =
        isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine) || original.call(action, unit, lagHiding)

    @WrapOperation(
        method = HANDLE_BUILDING_PLACEMENT,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = PLACEMENT_ERROR),
        require = 1,
    )
    fun allowEditorBuildLocation(
        unit: OrderableUnit,
        ignore: Boolean,
        team: Team,
        original: Operation<String?>,
    ): String? = if (isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine)) null
    else original.call(unit, ignore, team)

    @WrapOperation(
        method = HANDLE_BUILDING_PLACEMENT,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = BLUEPRINT_OVERLAP),
        require = 1,
    )
    fun allowEditorBuildOverBlueprint(
        team: Team,
        unit: OrderableUnit,
        placementId: Int,
        original: Operation<Boolean>,
    ): Boolean = !isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine) && original.call(team, unit, placementId)

    @WrapOperation(
        method = HANDLE_BUILDING_PLACEMENT,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = CAN_ACT_INDEPENDENTLY),
        require = 1,
    )
    fun bypassEditorBuildRange(unit: OrderableUnit, original: Operation<Boolean>): Boolean =
        isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine) || original.call(unit)

    @WrapOperation(
        method = CHECK_PLACEMENT_METHOD,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = CAN_PLACE_AT),
        require = 1,
    )
    fun allowEditorBuildTerrain(
        unit: OrderableUnit,
        team: Team,
        original: Operation<Boolean>,
    ): Boolean = isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine) || original.call(unit, team)

    @WrapOperation(
        method = CHECK_PLACEMENT_METHOD,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = CAN_ACT_INDEPENDENTLY),
        require = 1,
    )
    fun allowEditorBuildRangeInPreview(unit: OrderableUnit, original: Operation<Boolean>): Boolean =
        isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine) || original.call(unit)

    @WrapOperation(
        method = CHECK_PLACEMENT_METHOD,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = BLUEPRINT_OVERLAP),
        require = 1,
    )
    fun allowEditorPlacementOverBlueprint(
        team: Team,
        unit: OrderableUnit,
        placementId: Int,
        original: Operation<Boolean>,
    ): Boolean = !isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine) && original.call(team, unit, placementId)

    @WrapOperation(
        method = HANDLE_BUILDING_PLACEMENT,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = SET_BUILD_WAYPOINT),
        require = 1,
    )
    fun dispatchEditorBuildAsNativeSpawn(
        command: CommandControllerCommand,
        x: Float,
        y: Float,
        unitType: UnitTypeInterface,
        techLevel: Int,
        original: Operation<kotlin.Unit>,
    ) {
        original.call(command, x, y, unitType, techLevel)
        if (isSandboxEditorBuildPlacement(GameEngine.getInstance().interfaceEngine)) {
            command.hasSystemAction = true
            command.systemActionId = 5
        }

    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
@AddInterface("io/github/rwpp/game/sandbox/NativeSandboxTerrainInterfaceAccess")
abstract class NativeSandboxTerrainInterfaceMixin {
    @Accessor("debugEditorUnit")
    abstract fun rwppTerrainEditor(): EditorUnit?

    @Accessor("debugEditorUnit")
    abstract fun rwppSetTerrainEditor(editor: EditorUnit)
}

@AsmMixin("com/corrodinggames/rts/game/units/EditorUnit")
@AddInterface("io/github/rwpp/game/sandbox/NativeSandboxTerrainTabAccess")
abstract class NativeSandboxTerrainTabMixin {
    @Accessor("tab")
    abstract fun rwppSetTerrainTab(tab: com.corrodinggames.rts.game.units.EditorUnitTab)
}
