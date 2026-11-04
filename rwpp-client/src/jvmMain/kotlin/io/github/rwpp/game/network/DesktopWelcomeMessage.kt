/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.network

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings

/** Sends the configured RWPP welcome packet from the desktop native join callback. */
internal object DesktopWelcomeMessage {
    fun sendIfEnabled(nativeConnection: Any) {
        if (appKoin.get<Settings>().showWelcomeMessage != true) return
        val connection = nativeConnection as Connection
        val output = OutputNetStream()
        output.writeUTF(io.github.rwpp.welcomeMessage)
        output.writeByte(3)
        output.writeNullableUTF("RWPP")
        output.writeConnectionId(null)
        output.writeInt(-1)
        val network = GameEngine.getInstance().networkEngine
        network.sendPacketOnConnection(connection, output.createPacket(141))
    }
}
