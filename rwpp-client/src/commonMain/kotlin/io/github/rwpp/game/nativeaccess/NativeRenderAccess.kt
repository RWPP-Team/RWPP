/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.nativeaccess

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerNotificationType

/** Native game fields and methods exposed to client game and HUD code through mixins. */
internal interface NativeBattleAccess {
    fun rwppGroups(): ArrayList<*>
    fun rwppActionBlockFraction(action: SpecialAction): Float
    fun rwppActionVisible(action: SpecialAction, units: ArrayList<OrderableUnit>): Boolean
}

/**
 * The transient message BattleInterface paints in the middle of the play field, reached through
 * showScreenMessage / showImportantScreenMessage / showScreenMessageMedium / showTransientMessage.
 * Timer counts down in ticks and is decremented by the native draw.
 */
internal interface NativeScreenMessageAccess {
    fun rwppScreenMessage(): String?
    fun rwppScreenMessageTimer(): Float
    fun rwppScreenMessageTimer(value: Float)
}

internal interface NativeCommandsAccess {
    fun rwppGuardOffered(value: Boolean)
    fun rwppAddActionUnit(command: CommandControllerCommand, action: SpecialAction, remove: Boolean)
    fun rwppAddActionUnits(command: CommandControllerCommand, action: SpecialAction)
}

internal interface NativeMessageListAccess {
    fun rwppMessages(): ArrayList<*>
}

internal interface NativeMessageAccess {
    fun rwppSender(): String?
    fun rwppMessage(): String?
    fun rwppMessageTime(): Long
    fun rwppTimeText(): String?
}

internal interface NativeWarLogListAccess {
    fun rwppItems(): ArrayList<*>
}

internal interface NativeWarLogAccess {
    fun rwppLogTime(): Long
    fun rwppLogDuration(): Long
    fun rwppAlwaysShow(): Boolean
    fun rwppLogText(): String
}

internal interface NativeMinimapNotificationAccess {
    fun rwppSignalX(): Int
    fun rwppSignalY(): Int
    fun rwppSignalType(): MinimapHandlerNotificationType
    fun rwppSignalScaleX(): Float
    fun rwppSignalScaleX(value: Float)
    fun rwppSignalScaleY(): Float
    fun rwppSignalScaleY(value: Float)
}

internal interface NativeMinimapAnimationAccess {
    fun rwppBorderFlashAngle(): Float
    fun rwppBorderFlashAngle(value: Float)
    fun rwppNotificationPulseAngle(): Float
    fun rwppNotificationPulseAngle(value: Float)
}

internal interface NativeFactoryQueueAccess {
    fun rwppBuildQueue(): FactoryBuildQueue
}
