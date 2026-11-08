/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.MapLoadEvent
import io.github.rwpp.api.event.events.MapLoadPhase
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.logging.logger
import org.koin.core.annotation.Single

/**
 * Tells the player when a server fails to deliver the map. The native client only logs a truncated
 * map stream and returns to the menu, which looks like nothing happened.
 */
@Single(binds = [Initialization::class])
class MapTransferNotice : Initialization {
    private var lastNoticeAt = 0L

    override fun init() {
        val owner = EventOwner(RwppExtensionRuntime.extensionContext)
        GlobalEventChannel.filter(MapLoadEvent::class).subscribeAlways(owner) { event ->
            if (event.phase == MapLoadPhase.FAILED) onMapLoadFailed(event)
        }
    }

    private fun onMapLoadFailed(event: MapLoadEvent) {
        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return
        val network = runCatching { engine.networkEngine }.getOrNull() ?: return
        // Only a client that receives the map from a host needs this notice. A local map problem
        // has its own native message.
        if (!network.networked || network.isServer || network.singlePlayerServer) return
        if (runCatching { engine.replayEngine.isPlayingReplay }.getOrDefault(false)) return
        val now = System.currentTimeMillis()
        if (now - lastNoticeAt < NOTICE_INTERVAL_MS) return
        lastNoticeAt = now
        val detail = event.error?.message?.trim()?.takeIf(String::isNotEmpty)?.take(240)
        val message = if (detail == null) readI18n("rwpp.map.loadFailedNetwork")
        else readI18n("rwpp.map.loadFailedNetworkDetail", detail)
        runCatching { engine.showMessageBox(readI18n("rwpp.map.loadFailedTitle"), message) }
            .onFailure { logger().warn("Could not show the map transfer failure notice", it) }
    }

    private companion object {
        /** The native loader can report the same failure twice, once per load attempt. */
        const val NOTICE_INTERVAL_MS = 5_000L
    }
}
