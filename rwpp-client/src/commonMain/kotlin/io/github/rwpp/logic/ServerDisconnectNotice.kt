/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.ui.GameScreen
import io.github.rwpp.ui.UI

/** Mirrors the native connection-status check, independently of optional HUD components. */
internal object ServerDisconnectNotice {
    private var notifiedNetwork: NetworkEngine? = null

    fun isDisconnected(network: NetworkEngine): Boolean {
        val engine = GameEngine.getInstance() ?: return false
        return !(engine.networkEngine !== network || !engine.gameLoopActive ||
                !network.networked || network.isServer || !network.gameStarted || network.serverEndedGame) && network.connections.none { candidate ->
            val connection = candidate as? Connection
            connection != null && connection.connected && connection.isOpen
        }
    }

    fun update(network: NetworkEngine) {
        if (!isDisconnected(network)) {
            notifiedNetwork = null
            return
        }
        if (notifiedNetwork === network) return
        notifiedNetwork = network
        appKoin.get<GameScreen>().showPauseMenu = true
        UI.showNativeMessage(null, readI18n("rwpp.ingame.serverDisconnected"))
    }
}
