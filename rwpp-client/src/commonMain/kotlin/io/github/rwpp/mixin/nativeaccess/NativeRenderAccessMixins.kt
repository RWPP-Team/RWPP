/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.nativeaccess

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerNotificationType
import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Invoker

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeBattleAccess")
abstract class NativeBattleAccessMixin {
    @Accessor("hotSelectionGroups")
    abstract fun rwppGroups(): ArrayList<*>

    @Invoker("getSpecialActionBlockTime")
    abstract fun rwppActionBlockFraction(action: SpecialAction): Float

    @Invoker("isSpecialActionVisibleCached")
    abstract fun rwppActionVisible(action: SpecialAction, units: ArrayList<OrderableUnit>): Boolean
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeScreenMessageAccess")
abstract class NativeScreenMessageAccessMixin {
    @Accessor("screenMessage")
    abstract fun rwppScreenMessage(): String?

    @Accessor("screenMessageTimer")
    abstract fun rwppScreenMessageTimer(): Float

    @Accessor("screenMessageTimer")
    abstract fun rwppScreenMessageTimer(value: Float)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeCommandsAccess")
abstract class NativeCommandsAccessMixin {
    @Accessor("guardActionOffered")
    abstract fun rwppGuardOffered(value: Boolean)

    @Invoker("addSpecialActionUnitToCommand")
    abstract fun rwppAddActionUnit(command: CommandControllerCommand, action: SpecialAction, remove: Boolean)

    @Invoker("addUnitsWithActionToCommand")
    abstract fun rwppAddActionUnits(command: CommandControllerCommand, action: SpecialAction)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MessageInterface")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeMessageListAccess")
abstract class NativeMessageListAccessMixin {
    @Accessor("messages")
    abstract fun rwppMessages(): ArrayList<*>
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MessageInterfaceMessageItem")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeMessageAccess")
abstract class NativeMessageAccessMixin {
    @Accessor("playerName")
    abstract fun rwppSender(): String?

    @Accessor("message")
    abstract fun rwppMessage(): String?

    @Accessor("time")
    abstract fun rwppMessageTime(): Long

    @Accessor("timeText")
    abstract fun rwppTimeText(): String?
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/WarLogInterface")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeWarLogListAccess")
abstract class NativeWarLogListAccessMixin {
    @Accessor("items")
    abstract fun rwppItems(): ArrayList<*>
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/WarLogInterfaceWarLogItem")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeWarLogAccess")
abstract class NativeWarLogAccessMixin {
    @Accessor("time")
    abstract fun rwppLogTime(): Long

    @Accessor("duration")
    abstract fun rwppLogDuration(): Long

    @Accessor("alwaysShow")
    abstract fun rwppAlwaysShow(): Boolean

    @Invoker("getText")
    abstract fun rwppLogText(): String
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MinimapHandlerDamageNotification")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeMinimapNotificationAccess")
abstract class NativeMinimapNotificationAccessMixin {
    @Accessor("x")
    abstract fun rwppSignalX(): Int

    @Accessor("y")
    abstract fun rwppSignalY(): Int

    @Accessor("type")
    abstract fun rwppSignalType(): MinimapHandlerNotificationType

    @Accessor("scaleX")
    abstract fun rwppSignalScaleX(): Float

    @Accessor("scaleX")
    abstract fun rwppSignalScaleX(value: Float)

    @Accessor("scaleY")
    abstract fun rwppSignalScaleY(): Float

    @Accessor("scaleY")
    abstract fun rwppSignalScaleY(value: Float)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MinimapHandler")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeMinimapAnimationAccess")
abstract class NativeMinimapAnimationAccessMixin {
    @Accessor("borderFlashAngle")
    abstract fun rwppBorderFlashAngle(): Float

    @Accessor("borderFlashAngle")
    abstract fun rwppBorderFlashAngle(value: Float)

    @Accessor("notificationPulseAngle")
    abstract fun rwppNotificationPulseAngle(): Float

    @Accessor("notificationPulseAngle")
    abstract fun rwppNotificationPulseAngle(value: Float)
}

@AsmMixin("com/corrodinggames/rts/game/units/buildings/Factory")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeFactoryQueueAccess")
abstract class NativeFactoryQueueAccessMixin {
    @Accessor("buildQueue")
    abstract fun rwppBuildQueue(): FactoryBuildQueue
}

@AsmMixin("com/corrodinggames/rts/game/units/custom/CustomUnit")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeFactoryQueueAccess")
abstract class NativeCustomQueueAccessMixin {
    @Accessor("buildQueue")
    abstract fun rwppBuildQueue(): FactoryBuildQueue
}
