/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.android

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwpp.android.cachePlayerSet
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.RoomOption
import io.github.rwpp.api.net.InternalPacketType
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.net.PreregisterPacketResult
import io.github.rwpp.net.applyPreregisterRoomOption
import io.github.rwpp.ui.UI
import io.github.rwpp.welcomeMessage
import kim.der.asm.api.annotation.*
import net.peanuuutz.tomlkt.Toml
import java.io.ByteArrayInputStream
import java.io.DataInputStream

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object AndroidRoomOptionHandshakeMixin {
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
                roomOptionHandshakeValue()
            } else {
                gamePackage
            }
        (output as OutputNetStream).writeUTF(value)
    }

    private fun roomOptionHandshakeValue(): String {
        val option = appKoin.get<Game>().gameRoom.option
        return io.github.rwpp.packageName + Toml.encodeToString(RoomOption.serializer(), option)
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object AndroidNetworkPacketMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun receiveRwppPackets(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        nativePacket: Any,
    ) {
        val packet = nativePacket as com.corrodinggames.rts.gameFramework.network.Packet
        val packetType = packet.type
        if (packetType == InternalPacketType.PREREGISTER_INFO.type) {
            val room = appKoin.get<Game>().gameRoom
            val option = if (room.isHost) null else readRoomOption(packet)
            val result =
                applyPreregisterRoomOption(
                    packetType = packetType,
                    isHost = room.isHost,
                    option = option,
                    expectedProtocolVersion = io.github.rwpp.protocolVersion,
                ) { serverOption ->
                    room.option = serverOption
                }
            if (result == PreregisterPacketResult.CANCEL_FOR_PROTOCOL_MISMATCH) {
                room.disconnect()
                UI.showWarning(
                    "Different protocol version. yours: ${io.github.rwpp.protocolVersion} server's: ${
                        checkNotNull(
                            option
                        ).protocolVersion
                    }",
                    true,
                )
                callbackInfo.cancel()
            }
            return
        }

    }

    private fun readRoomOption(packet: com.corrodinggames.rts.gameFramework.network.Packet): RoomOption? {
        val data = packet.data ?: return null
        val firstValue = runCatching { DataInputStream(ByteArrayInputStream(data)).use { it.readUTF() } }.getOrNull()
            ?: return null
        if (!firstValue.startsWith(io.github.rwpp.packageName)) return null
        return runCatching {
            Toml.decodeFromString(RoomOption.serializer(), firstValue.removePrefix(io.github.rwpp.packageName))
        }.getOrNull()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object AndroidNetworkEventMixin {
    @AsmInject(
        method = "sendServerInfo(Lcom/corrodinggames/rts/gameFramework/network/Connection;)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun sendWelcomeMessage(
        @Suppress("UNUSED_PARAMETER") target: Any,
        nativeConnection: Any,
    ) {
        val player = ((nativeConnection as Connection).player) ?: return
        if (!cachePlayerSet.add(player)) return
        if (appKoin.get<Settings>().showWelcomeMessage == true) {
            appKoin.get<Game>().gameRoom.sendMessageToPlayer(player as Team, "RWPP", welcomeMessage)
        }
    }

}
