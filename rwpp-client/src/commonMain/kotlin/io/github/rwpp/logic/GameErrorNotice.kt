/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.GameRecoveryEvent
import io.github.rwpp.api.event.events.GameRecoveryStage
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.ui.UI
import org.koin.core.annotation.Single

@Single(binds = [Initialization::class])
class GameErrorNotice : Initialization {
    private var waitingId: Long? = null
    private var waitingNotice: UI.Warning? = null
    override fun init() {
        GlobalEventChannel.filter(GameRecoveryEvent::class)
            .subscribeAlways(EventOwner(RwppExtensionRuntime.extensionContext)) { event ->
                when (event.stage) {
                    GameRecoveryStage.WAITING_FOR_SERVER -> {
                        UI.showNativeMessage(null, readI18n("rwpp.gameError.waiting", event.detail.take(600)))
                        waitingId = event.id; waitingNotice = UI.warning
                    }

                    GameRecoveryStage.RESUMED -> if (waitingId == event.id) {
                        if (UI.warning === waitingNotice) UI.warning = null
                        waitingId = null; waitingNotice = null
                    }

                    GameRecoveryStage.SESSION_ENDED -> {
                        waitingId = null; waitingNotice = null
                        UI.showNativeMessage(null, readI18n("rwpp.gameError.stopped", event.detail.take(600)))
                    }
                }
            }
    }
}
