/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.map.Map as NativeMap
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit as GameUnit
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.IndirectedAction
import com.corrodinggames.rts.game.units.actions.QueueUnitAction
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.buildings.FactoryInterface
import com.corrodinggames.rts.game.units.custom.price.CustomPrice
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.logging.logger
import io.github.rwpp.logic.GameVisualSettings

/** Local intent only. Each accepted order is sent through the ordinary native command controller. */
internal object OverflowProductionQueue {
    private const val LIMIT = 256

    private val pending = linkedMapOf<OrderableUnit, ArrayDeque<ActionId>>()
    private var map: NativeMap? = null
    private var team: Team? = null
    private data class Submitted(
        val command: CommandControllerCommand,
        val actionId: ActionId,
        val countBefore: Int,
        var settleTicks: Int = 0,
    )
    private val inFlight = HashMap<OrderableUnit, Submitted>()
    private var updating = false
    private var nextUpdateAt = 0L
    private val updateTask = Runnable {
        val engine = GameEngine.getInstance() ?: return@Runnable
        synchronized(engine.gameUpdateLock) { update(engine) }
    }

    private val enabled get() = GameVisualSettings.get()?.overflowProductionQueue == true

    fun supports(action: SpecialAction, units: List<GameUnit>): Boolean =
        enabled && isProductionAction(action) && owners(action, units).any { owner ->
            val owned = resolveQueueAction(owner, action.storedActionId) ?: return@any false
            admission(owner, owned) == Admission.Resources
        }

    fun pendingCount(action: SpecialAction, units: List<GameUnit>): Int {
        if (!enabled || !isProductionAction(action)) return 0
        return owners(action, units).sumOf { owner -> pendingCountForOwner(owner, action.storedActionId) }
    }

    private fun pendingCountForOwner(owner: OrderableUnit, actionId: ActionId): Int {
        val queued = pending[owner]?.count { it == actionId } ?: 0
        val inflight = inFlight[owner]?.takeIf { it.actionId == actionId }?.let { 1 } ?: 0
        return queued + inflight
    }

    /** A read-only snapshot of waiting orders for the current selection. Caller holds the game update lock. */
    fun selectedWaitingOrders(engine: GameEngine, selection: List<GameUnit>): Map<String, Int> {
        if (!enabled || map !== engine.map || team !== engine.playerTeam ||
            pending.isEmpty() || selection.isEmpty()
        ) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        for ((owner, requests) in pending) {
            if (owner !in selection || !canControl(engine, owner)) continue
            for ((id, count) in requests.groupingBy { it }.eachCount()) {
                val action = resolveQueueAction(owner, id) ?: continue
                val name = action.unitType?.displayName
                    ?: action.getDisplayNameForUnit(owner).takeIf { it.isNotBlank() } ?: continue
                counts[name] = (counts[name] ?: 0) + count
            }
        }
        return counts
    }

    fun pendingCountForFactory(unit: GameUnit): Int =
        if (!enabled || unit !is OrderableUnit) 0 else pending[unit]?.size ?: 0

    fun enqueue(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine)) return false
        val candidates = owners(action, units).filter { canControl(engine, it) }.mapNotNull { owner ->
            val owned = resolveQueueAction(owner, action.storedActionId) ?: return@mapNotNull null
            owner to admission(owner, owned)
        }.toMutableList()
        markBatchOverflowAsResources(action, candidates)
        val ready = candidates.any { it.second == Admission.Ready }
        val waiting = candidates.filter { it.second == Admission.Resources }.map { it.first }
        if (waiting.isEmpty()) return false
        val ui = engine.interfaceEngine
        val batch = hudQueueBatch(action.canQueueMultiple(), ui.isShiftKeyDown(engine), ui.isCtrlKeyDown(engine))
        var full = false
        repeat(batch) {
            val recipients = if (action.isUnitSpecificAction) {
                listOf(waiting.minWith(compareBy({ pending[it]?.size ?: 0 }, { (it as FactoryInterface).getTotalQueuedCount(true) }, { it.id })))
            } else waiting
            for (owner in recipients) {
                val queue = pending.getOrPut(owner) { ArrayDeque() }
                val id = action.storedActionId
                // A non-repeatable action can occupy at most one future slot on this unit.
                if (!action.canQueueMultiple() && id in queue) continue
                if (queue.size == LIMIT) full = true else queue.addLast(id)
            }
        }
        if (!updating && pending.isNotEmpty()) {
            updating = true
            GameMainDispatcher.addUpdateTask(updateTask)
        }
        if (full) ui.showImportantScreenMessage(readI18n("rwpp.production.pendingFull"))
        if (!ready) ui.activeSpecialAction = null
        return !ready
    }

    fun cancelLast(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine) || pending.isEmpty()) return false
        var removed = false
        for (owner in owners(action, units)) {
            val queue = pending[owner] ?: continue
            val index = queue.indexOfLast { it == action.storedActionId }
            if (index < 0) continue
            queue.removeAt(index)
            if (queue.isEmpty()) pending.remove(owner)
            removed = true
            if (action.isUnitSpecificAction) break
        }
        return removed
    }

    private fun update(engine: GameEngine) {
        if (!syncSession(engine)) return
        val now = System.nanoTime()
        if (now < nextUpdateAt) return
        nextUpdateAt = now + 100_000_000L
        val controller = engine.commandController
        val canVerifyAcceptance = !engine.networkEngine.networked || engine.networkEngine.isServer
        inFlight.entries.removeAll { (owner, submitted) ->
            val command = submitted.command
            val commandAlive = command in controller.localPendingCommands ||
                    command in controller.networkPendingCommands ||
                    command in controller.issuedCommands
            if (commandAlive) return@removeAll false
            if (canVerifyAcceptance && command.actionId == submitted.actionId) {
                val accepted = (owner as? FactoryInterface)?.getQueuedCountOf(submitted.actionId, true)?.let {
                    it > submitted.countBefore
                } ?: false
                if (!accepted) {
                    if (submitted.settleTicks++ < 5) return@removeAll false
                    // Command finished locally but factory rejected it; requeue at head so the click is not lost.
                    pending.getOrPut(owner) { ArrayDeque() }.addFirst(submitted.actionId)
                }
            }
            true
        }
        val readyCandidates = mutableListOf<ReadyCandidate>()
        val factories = pending.iterator()
        while (factories.hasNext()) {
            val (owner, requests) = factories.next()
            if (!canControl(engine, owner)) {
                factories.remove()
                continue
            }
            val queue = requests.iterator()
            var first = true
            while (queue.hasNext()) {
                val action = resolveQueueAction(owner, queue.next())
                val state = if (action == null) Admission.Blocked else admission(owner, action)
                if (state == Admission.Blocked) {
                    queue.remove()
                    continue
                }
                if (state == Admission.Resources || state == Admission.Waiting) {
                    // Resource shortage or a transient gate (cap, transport slot, build state):
                    // keep the order and let the head of this factory's queue retain priority.
                    first = false
                    continue
                }
                if (first && action != null && owner !in inFlight) {
                    val nativeQueued = (owner as? FactoryInterface)?.getTotalQueuedCount(true) ?: 0
                    readyCandidates += ReadyCandidate(owner, action, requests, nativeQueued, requests.size)
                }
                first = false
            }
            if (requests.isEmpty()) factories.remove()
        }

        val candidate = selectReadyCandidate(readyCandidates)
        if (candidate != null) {
            try {
                val ui = engine.interfaceEngine
                val countBefore = (candidate.owner as? FactoryInterface)
                    ?.getQueuedCountOf(candidate.action.actionId, true) ?: 0
                val command = ui.playerCommandQueued
                command.addUnit(candidate.owner)
                command.setActionId(candidate.action.actionId)
                ui.queueExtraActionOrder(candidate.action, null, null, command)
                inFlight[candidate.owner] = Submitted(command, candidate.action.actionId, countBefore)
                InterfaceEngine.markUiDirty()
            } catch (failure: Exception) {
                logger().warn("Could not submit a deferred production order", failure)
            }
            candidate.queue.removeFirst()
            if (candidate.queue.isEmpty()) pending.remove(candidate.owner)
            // Serialize submissions: issuing multiple commands in the same tick can over-commit
            // shared team credits before the engine deducts them, causing later orders to be
            // rejected and requeued one by one.
        }
        if (pending.isEmpty()) clear()
    }

    private fun clear() {
        if (updating) {
            GameMainDispatcher.removeUpdateTask(updateTask)
            updating = false
        }
        pending.clear()
        inFlight.clear()
        nextUpdateAt = 0L
        map = null
        team = null
    }

    private fun syncSession(engine: GameEngine): Boolean {
        val currentMap = engine.map
        val currentTeam = engine.playerTeam
        val active = enabled && currentMap != null && currentMap.mapLoaded && currentTeam != null &&
                !engine.replayEngine.isPlayingReplay && !isAeroLiveObserver(engine)
        if (!active || map !== currentMap || team !== currentTeam) {
            clear()
            map = if (active) currentMap else null
            team = if (active) currentTeam else null
        }
        return active
    }

    private fun isProductionAction(action: SpecialAction): Boolean =
        action.actionType == ActionType.none || action.actionType == ActionType.popupQueue

    private data class ReadyCandidate(
        val owner: OrderableUnit,
        val action: SpecialAction,
        val queue: ArrayDeque<ActionId>,
        val nativeQueued: Int,
        val waitingQueued: Int,
    )

    private fun selectReadyCandidate(candidates: List<ReadyCandidate>): ReadyCandidate? {
        if (candidates.isEmpty()) return null
        // Water-fill: drain factories with no/less native manufacturing first so they catch up.
        val minNative = candidates.minOf { it.nativeQueued }
        return candidates.firstOrNull { it.nativeQueued == minNative }
    }

    /**
     * When queuing one order across multiple selected factories, the first factories may be
     * affordable alone but the whole batch is not. Native would issue them; we defer the tail so
     * all selected factories end up with the same pending order.
     */
    private fun markBatchOverflowAsResources(
        action: SpecialAction,
        candidates: MutableList<Pair<OrderableUnit, Admission>>
    ) {
        if (action.isUnitSpecificAction) return
        val sample = candidates.firstNotNullOfOrNull { (owner, _) -> resolveQueueAction(owner, action.storedActionId) }
            ?: return
        val price = runCatching { sample.getCustomPrice() }.getOrNull() ?: return
        var affordable = 0
        for (i in candidates.indices) {
            val (owner, state) = candidates[i]
            if (state != Admission.Ready) continue
            val canAffordThis = runCatching { price.canAfford(owner, (affordable + 1).toDouble()) }.getOrDefault(false)
            if (canAffordThis) {
                affordable++
            } else {
                // Not enough team resources for this ready factory in this batch; defer it.
                candidates[i] = owner to Admission.Resources
            }
        }
    }

    internal enum class Admission { Ready, Resources, Waiting, Blocked }

    /** Pure classification so transient factory gates can be regression-tested without native objects. */
    internal data class AdmissionInput(
        val unlocked: Boolean,
        val builtAndNotTransported: Boolean,
        val factorySpeedPositive: Boolean,
        val overTransportCapacity: Boolean,
        val unitCapReached: Boolean,
        val affordable: Boolean,
        val onlyResourceShortage: Boolean,
    )

    internal fun classify(input: AdmissionInput): Admission = when {
        !input.unlocked -> Admission.Blocked
        // Only a genuine resource shortage is worth deferring: the native queue rejects such an
        // order outright, so there is no risk of duplicating an order the engine already accepted.
        !input.affordable && input.onlyResourceShortage -> Admission.Resources
        // Transient factory gates must keep already-queued orders alive, but native accepts new
        // orders while over transport capacity, so those must not be added to the deferred queue.
        !input.builtAndNotTransported || !input.factorySpeedPositive ||
                input.overTransportCapacity || input.unitCapReached -> Admission.Waiting
        input.affordable -> Admission.Ready
        else -> Admission.Blocked
    }

    private fun admission(owner: OrderableUnit, action: SpecialAction): Admission = runCatching {
        val team = owner.team
        val queueable = action.canQueueMultiple()
        val affordable = action.canAffordForUnit(owner, true)
        classify(
            AdmissionInput(
                unlocked = hudActionUnlocked(action, owner),
                builtAndNotTransported = owner.isBuiltAndNotTransported,
                factorySpeedPositive = owner.nanoFactorySpeed > 0f,
                overTransportCapacity = queueable && (owner as? FactoryInterface)?.isOverTransportCapacity == true,
                unitCapReached = queueable && team != null && team.unitCapCount >= team.unitsMax,
                affordable = affordable,
                onlyResourceShortage = !affordable && ProductionPriceProbe.hasOnlyResourceShortage(action, owner),
            )
        )
    }.getOrDefault(Admission.Blocked)

    private fun owners(action: SpecialAction, units: List<GameUnit>): List<OrderableUnit> {
        val selection = if (action is IndirectedAction) listOf(action.targetUnit) else units
        return selection.filterIsInstance<OrderableUnit>().filter {
            it is FactoryInterface && !it.dead && !it.removed && resolveQueueAction(it, action.storedActionId) != null
        }
    }

    private fun canControl(engine: GameEngine, owner: OrderableUnit): Boolean =
        !owner.dead && !owner.removed && owner.team != null && engine.interfaceEngine.canPlayerControlUnit(owner)

    private fun resolveQueueAction(owner: OrderableUnit, id: ActionId): SpecialAction? =
        runCatching {
            owner.getSpecialAction(id)?.takeIf { it is QueueUnitAction && isProductionAction(it) && it.getCountForUnit(owner, false) >= 0 }
        }.getOrNull()
}

/** Recheck native action gates with just the resource amounts waived. No resources are mutated. */
internal object ProductionPriceProbe {
    private val checkingOwner = ThreadLocal<GameUnit>()

    fun hasOnlyResourceShortage(action: SpecialAction, owner: OrderableUnit): Boolean {
        checkingOwner.set(owner)
        return try {
            action.canAffordForUnit(owner, true)
        } finally {
            checkingOwner.remove()
        }
    }

    fun allows(price: CustomPrice, owner: GameUnit): Boolean? =
        if (checkingOwner.get() === owner) price.checkFlagRequirements(owner) else null
}
