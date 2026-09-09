/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:Suppress("unused", "UnusedReceiverParameter")

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.librocket.scripts.Root
import com.corrodinggames.librocket.scripts.ScriptContext
import com.corrodinggames.librocket.scripts.ScriptEngine
import io.github.rwpp.appKoin
import io.github.rwpp.desktop.*
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.QuitGameEvent
import io.github.rwpp.event.events.ReturnMainMenuEvent
import io.github.rwpp.game.Game
import io.github.rwpp.impl.*
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import javax.swing.SwingUtilities

@InjectClass(Root::class)
object RootInject {
    @Inject("showMainMenu", InjectMode.Override)
    fun onShowMainMenu() {
        if(isGaming) {
            if(singlePlayer) appKoin.get<Game>().gameRoom.disconnect()
            GameEngine.B().bS.u = false

            rwppVisibleSetter(true)
            isGaming = false
            gameOver = false
            com.corrodinggames.librocket.a.a().b()
            val libRocket = ScriptContext::class.java.getDeclaredField("libRocket").run {
                isAccessible = true
                get(ScriptEngine.getInstance().root)
            } as com.corrodinggames.librocket.b
            libRocket.closeActiveDocument()
            libRocket.clearHistory()

            QuitGameEvent().broadcastIn()
            ReturnMainMenuEvent().broadcastIn()
        }
    }

    @Inject("showBattleroom", InjectMode.Override)
    fun onShowBattleroom() {
        if(isGaming) {
            GameEngine.B().bS.u = false

            rwppVisibleSetter(true)
            isGaming = false
            gameOver = false
            val libRocket = ScriptContext::class.java.getDeclaredField("libRocket").run {
                isAccessible = true
                get(ScriptEngine.getInstance().root)
            } as com.corrodinggames.librocket.b
            libRocket.closeActiveDocument()
            libRocket.clearHistory()

            QuitGameEvent().broadcastIn()
        }
    }
    @Inject("makeSendMessagePopup", InjectMode.Override)
    fun onMakeSendMessagePopup() {
        SwingUtilities.invokeLater {
            showSendMessageDialog()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    @Inject("makeSendTeamMessagePopupWithDefaultText", InjectMode.Override)
    fun onMakeSendTeamMessagePopupWithDefaultText(str: String) {
        SwingUtilities.invokeLater {
            isSendingTeamChat = true
            showSendMessageDialog()
        }
    }
}