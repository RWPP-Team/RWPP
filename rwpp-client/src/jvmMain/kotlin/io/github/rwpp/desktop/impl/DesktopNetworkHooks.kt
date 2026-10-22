/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import com.corrodinggames.rts.gameFramework.network.Packet as NativePacket
import io.github.rwpp.appKoin
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.RoomOption
import io.github.rwpp.api.net.InternalPacketType
import io.github.rwpp.net.PreregisterPacketResult
import io.github.rwpp.net.applyPreregisterRoomOption
import io.github.rwpp.ui.UI
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect
import net.peanuuutz.tomlkt.Toml
import java.io.ByteArrayInputStream
import java.io.DataInputStream

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object DesktopRoomOptionHandshakeMixin {
    @Redirect(
        method = "sendPreregisterInfo(Lcom/corrodinggames/rts/gameFramework/network/Connection;)V",
        ordinal = 0,
        at =
            At(
                value = InjectionPoint.INVOKE,
                target = "com/corrodinggames/rts/gameFramework/network/OutputNetStream.writeUTF(Ljava/lang/String;)V",
            ),
        require = 1,
    )
    fun includeServerRoomOption(output: Any, gamePackage: String) {
        val value =
            if (gamePackage == "com.corrodinggames.rts") {
                val option = appKoin.get<Game>().gameRoom.option
                io.github.rwpp.packageName + Toml.encodeToString(RoomOption.serializer(), option)
            } else {
                gamePackage
            }
        (output as OutputNetStream).writeUTF(value)
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object DesktopNetworkPacketMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun receiveRwppPackets(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") self: Any,
        nativePacket: Any,
    ) {
        val packet = nativePacket as NativePacket
        if (packet.type == InternalPacketType.PREREGISTER_INFO.type) {
            val gameRoom = appKoin.get<Game>().gameRoom
            val option = if (gameRoom.isHost) null else decodeRoomOption(packet)
            val result =
                applyPreregisterRoomOption(
                    packetType = packet.type,
                    isHost = gameRoom.isHost,
                    option = option,
                    expectedProtocolVersion = io.github.rwpp.protocolVersion,
                ) { option ->
                    gameRoom.option = option
                }
            if (result == PreregisterPacketResult.CANCEL_FOR_PROTOCOL_MISMATCH) {
                val remoteProtocolVersion = checkNotNull(option).protocolVersion
                gameRoom.disconnect()
                UI.showWarning(
                    "Different protocol version. yours: ${io.github.rwpp.protocolVersion} server's: $remoteProtocolVersion",
                    true,
                )
                callbackInfo.cancel()
            }
            return
        }

    }

    private fun decodeRoomOption(packet: NativePacket): RoomOption? {
        val data = packet.data ?: return null
        val firstValue =
            runCatching { DataInputStream(ByteArrayInputStream(data)).use { it.readUTF() } }.getOrNull()
                ?: return null
        if (!firstValue.startsWith(io.github.rwpp.packageName)) return null
        return Toml.decodeFromString(RoomOption.serializer(), firstValue.removePrefix(io.github.rwpp.packageName))
    }
}
