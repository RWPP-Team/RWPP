/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.event.events.GameRecoveryEvent
import io.github.rwpp.api.event.events.GameRecoveryStage
import io.github.rwpp.api.event.hooks.broadcastNativeEvent
import io.github.rwpp.api.logging.logger

/** An ahead-of-server client must wait for an authoritative save, never rewrite its own tick. */
internal object ClientTimelineGuard {
    private data class Waiting(
        val engine: GameEngine, val network: NetworkEngine, val generation: Long,
        val resyncCount: Int, val since: Long, val id: Long
    )

    @Volatile
    private var waiting: Waiting? = null

    fun isPaused(engine: GameEngine): Boolean = NativeGameFaults.isPending(engine) ||
            waiting?.let { it.engine === engine && it.network.networked && !it.network.isServer } == true

    fun beforeStep(network: NetworkEngine): Boolean {
        val engine = GameEngine.getInstance() ?: return false
        if (engine.networkEngine !== network) return false
        poll(network)
        if (NativeGameFaults.isPending(engine)) {
            network.waitingForServerStep = true; return true
        }
        if (!network.networked || network.isServer) return false
        if (waiting == null && engine.simulationTick > network.nextBlockingFrame) {
            val detail = "game frame:${engine.simulationTick} is greater then nest step:${network.nextBlockingFrame}"
            waiting = Waiting(
                engine, network, ClientPacketSessionGuard.generation(network), network.resyncCount,
                System.nanoTime(), NativeGameFaults.nextId()
            )
            logger().warn("$detail; paused awaiting the server's authoritative resync save")
            broadcastNativeEvent(GameRecoveryEvent(waiting!!.id, GameRecoveryStage.WAITING_FOR_SERVER, detail))
        }
        if (waiting != null) {
            network.waitingForServerStep = true; return true
        }
        return false
    }

    fun poll(network: NetworkEngine) {
        val state = waiting ?: return
        val engine = state.engine
        if (GameEngine.getInstance() !== engine || engine.networkEngine !== state.network) {
            waiting = null
            broadcastNativeEvent(GameRecoveryEvent(state.id, GameRecoveryStage.RESUMED, "Engine replaced"))
            return
        }
        if (state.network !== network) return
        if (!network.networked ||
            network.isServer || ClientPacketSessionGuard.generation(network) != state.generation
        ) {
            waiting = null
            broadcastNativeEvent(GameRecoveryEvent(state.id, GameRecoveryStage.RESUMED, "Session replaced"))
        } else if (network.resyncCount > state.resyncCount && engine.simulationTick <= network.nextBlockingFrame) {
            waiting = null
            network.waitingForServerStep = engine.simulationTick >= network.nextBlockingFrame
            broadcastNativeEvent(GameRecoveryEvent(state.id, GameRecoveryStage.RESUMED, "Server resync applied"))
        } else if (System.nanoTime() - state.since >= 15_000_000_000L) {
            NativeGameFaults.report(
                engine, IllegalStateException(
                    "Timed out waiting for server resync: frame=${engine.simulationTick}, next=${network.nextBlockingFrame}"
                ), "network synchronization"
            )
        }
    }
}
