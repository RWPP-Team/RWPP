/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.api.net.InternalPacketType.PREREGISTER_INFO
import io.github.rwpp.api.game.RoomOption

internal enum class PreregisterPacketResult {
    NOT_PREREGISTER,
    NATIVE_HANDLED,
    CANCEL_FOR_PROTOCOL_MISMATCH,
}

internal fun applyPreregisterRoomOption(
    packetType: Int,
    isHost: Boolean,
    option: RoomOption?,
    expectedProtocolVersion: Int,
    applyOption: (RoomOption) -> Unit,
): PreregisterPacketResult {
    if (packetType != PREREGISTER_INFO.type) return PreregisterPacketResult.NOT_PREREGISTER
    if (isHost || option == null) return PreregisterPacketResult.NATIVE_HANDLED
    applyOption(option)
    return if (option.protocolVersion == expectedProtocolVersion) {
        PreregisterPacketResult.NATIVE_HANDLED
    } else {
        PreregisterPacketResult.CANCEL_FOR_PROTOCOL_MISMATCH
    }
}

internal fun isBannedUnitCommand(
    actionId: String,
    isBuildAction: Boolean,
    builtUnitName: String?,
    bannedUnits: Collection<String>,
): Boolean =
    actionId.removePrefix("u_") in bannedUnits ||
        (isBuildAction && builtUnitName != null && builtUnitName in bannedUnits)

internal fun shouldRouteRemoteCommand(
    isHost: Boolean,
    isLocalPlayer: Boolean,
    message: String?,
    commandPrefix: String,
): Boolean = isHost && !isLocalPlayer && message?.startsWith(commandPrefix) == true

internal fun shouldRouteLocalCommand(isHost: Boolean, message: String, commandPrefix: String): Boolean =
    isHost && message.startsWith(commandPrefix)

/** Atomically claim the first eligible defeat notification for a player. */
internal fun claimPlayerDefeatOnce(
    isPlaying: Boolean,
    isDefeated: Boolean,
    claim: () -> Boolean,
): Boolean = isPlaying && isDefeated && claim()
