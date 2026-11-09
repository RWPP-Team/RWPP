/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.mode

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.DisconnectEvent
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.map.XMLMap
import java.util.concurrent.atomic.AtomicLong

/** One room's active modes and owned runtime resources. Keep this instance for one room session. */
class GameModeSession(
    val gameRoom: GameRoom,
    val registry: GameModeRegistry = GameModes.registry,
) : AutoCloseable {
    private data class ActiveMode(
        val mode: GameMode,
        val context: GameModeContext,
        val activationOrder: Long,
        val automatic: Boolean,
    )

    private data class RestoreResult(val states: List<ActiveMode>, val failure: Throwable?)

    private val lock = Any()
    private val lifecycleOwner = EventOwner()
    private val activationCounter = AtomicLong()
    private val active = LinkedHashMap<String, ActiveMode>()
    private var closed = false
    private var changing = false
    private var speedEngine: RWGameEngine? = null
    private var originalSpeedMultiplier: Float? = null

    /** Native base recognized from the currently loaded map; recognition does not activate it. */
    var nativeBaseMode: GameMode? = null
        private set

    /** Explicit base mode, or the currently observed native base when no explicit base is active. */
    val baseMode: GameMode?
        get() = synchronized(lock) {
            active.values.firstOrNull { it.mode.kind == GameModeKind.BASE }?.mode ?: nativeBaseMode
        }

    /** Effective native base plus active modifiers, without implicitly activating the base. */
    val effectiveModes: List<GameMode>
        get() = synchronized(lock) {
            val explicit = orderedActive().map(ActiveMode::mode)
            val effectiveBase = explicit.firstOrNull { it.kind == GameModeKind.BASE } ?: nativeBaseMode
            (listOfNotNull(effectiveBase) + explicit.filter { it.kind != GameModeKind.BASE })
                .distinctBy(GameMode::id)
        }

    /** True when this process owns the native simulation (including an offline mission). */
    internal fun hasNativeAuthority(engine: RWGameEngine? = null): Boolean {
        val nativeEngine = engine ?: runCatching { GameEngine.getInstance() as? RWGameEngine }.getOrNull()
        if (nativeEngine?.replayEngine?.isPlayingReplay == true) return false
        if (nativeEngine == null) return gameRoom.isHost
        return if (nativeEngine.networkEngine.networked) {
            gameRoom.isHost && nativeEngine.networkEngine.isServer
        } else {
            gameRoom.isHost || nativeEngine.isGameRunning
        }
    }

    init {
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(lifecycleOwner) {
            clear()
        }
    }

    /** Immutable snapshot in dependency and application order. */
    val activeModes: List<GameMode>
        get() = synchronized(lock) { orderedActive().map(ActiveMode::mode) }

    /** True if an explicit active mode will modify the map before host start. */
    val hasMapTransformers: Boolean
        get() = synchronized(lock) { active.values.any { it.mode.transformsMap } }

    /** The built-in room speed multiplier, or 1 when that mode is not active. */
    val gameSpeed: Float
        get() = synchronized(lock) {
            active.values.filter { it.mode is GameSpeedMode }
                .fold(1f) { speed, state -> speed * (state.mode as GameSpeedMode).multiplier }
        }

    /** Activate a parameterized mode instance and all registered requirements as one transaction. */
    fun activate(mode: GameMode, owner: EventOwner? = null): GameMode = synchronized(lock) {
        checkMutable()
        registry.validateMode(mode)
        check(mode.id !in active) { "Game mode '${mode.id}' is already active; use replace() to update it." }
        changing = true
        try {
            activateTransaction(mode, replacing = emptySet())
        } finally {
            changing = false
        }
        ownActivation(mode, owner)
        mode
    }

    /** Activate a registered mode and its dependencies. */
    fun activate(modeId: String, owner: EventOwner? = null): GameMode = activate(requireRegistered(modeId), owner)

    /**
     * Atomically replace the active mode with the same ID. A base mode also replaces the active
     * base mode. Validation happens before teardown; if enabling fails, prior modes are restored.
     */
    fun replace(mode: GameMode, owner: EventOwner? = null): GameMode = synchronized(lock) {
        checkMutable()
        registry.validateMode(mode)
        val replacedIds = replacementIds(mode)
        changing = true
        try {
            activateTransaction(mode, replacedIds)
        } finally {
            changing = false
        }
        ownActivation(mode, owner)
        mode
    }

    /** Replace using the registered definition. */
    fun replace(modeId: String, owner: EventOwner? = null): GameMode = replace(requireRegistered(modeId), owner)

    /** Deactivate a mode and prune automatically activated requirements that become unused. */
    fun deactivate(modeId: String): Boolean = synchronized(lock) {
        checkMutable()
        if (modeId !in active) return@synchronized false
        val dependents = dependentClosure(modeId)
        if (dependents.any { id -> active[id]?.automatic != true }) {
            throw GameModeDependencyException(
                "Cannot deactivate '$modeId'; active modes depend on it: ${dependents.joinToString()}.",
            )
        }
        changing = true
        try {
            val removeIds = linkedSetOf(modeId).apply { addAll(dependents) }
            addOrphanedAutomaticDependencies(removeIds)
            val closeOrder = orderedActive().filter { it.mode.id in removeIds }.asReversed()
            var failure: Throwable? = null
            closeOrder.forEach { state ->
                active.remove(state.mode.id)
                failure = failure.append(closeState(state))
            }
            try {
                applyGameSpeed()
            } catch (speedFailure: Throwable) {
                failure = failure.append(speedFailure)
            }
            failure?.let { throw GameModeException("Could not fully deactivate '$modeId'.", it) }
            true
        } finally {
            changing = false
        }
    }

    /** Deactivate every active mode in reverse dependency/order sequence. */
    fun clear() {
        synchronized(lock) {
            if (closed) return
            check(!changing) { "Game modes cannot be cleared recursively from an activation callback." }
            changing = true
            try {
                var failure: Throwable? = null
                orderedActive().asReversed().forEach { state ->
                    active.remove(state.mode.id)
                    failure = failure.append(closeState(state))
                }
                nativeBaseMode = null
                try {
                    applyGameSpeed()
                } catch (speedFailure: Throwable) {
                    failure = failure.append(speedFailure)
                }
                failure?.let {
                    throw GameModeException(
                        "One or more game modes failed to release their resources.",
                        it
                    )
                }
            } finally {
                changing = false
            }
        }
    }

    /**
     * Apply the explicit base and modifier transforms in dependency/priority/activation order.
     * The native host is the only writer; clients keep the map distributed by the game engine.
     */
    fun transform(map: XMLMap) = synchronized(lock) {
        if (!gameRoom.isHost) return@synchronized
        val activeModes = orderedActive()
        val ordered = activeModes.filter { it.mode.transformsMap }
        val explicitBase = activeModes.firstOrNull { it.mode.kind == GameModeKind.BASE }?.mode
        if (explicitBase == null) {
            validateObservedBaseConflicts(
                activeModes.map { it.mode },
                findBaseForNativeType(map.mapInfoObject?.getProperty("type"))
            )
        }
        ordered.forEach { it.mode.transformMap(map, it.context) }
        ordered.forEach { it.mode.validateMap(map, it.context) }
        if (explicitBase == null) {
            validateObservedBaseConflicts(
                activeModes.map { it.mode },
                findBaseForNativeType(map.mapInfoObject?.getProperty("type"))
            )
        }
    }

    /** Recognize a map's native `map_info.type` without activating or transforming any mode. */
    fun recognizeNativeBase(map: XMLMap): GameMode? = synchronized(lock) {
        val type = map.mapInfoObject?.getProperty("type")
        findBaseForNativeType(type)
    }

    /** Record the already loaded native TriggerEngine mode without changing session activations. */
    fun observeNativeBase(type: String?) = synchronized(lock) {
        nativeBaseMode = findBaseForNativeType(type)
    }

    /** Deactivate resources and remove lifecycle subscriptions when the owning room is destroyed. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            var failure: Throwable? = null
            try {
                clear()
            } catch (closeFailure: Throwable) {
                failure = closeFailure
            }
            closed = true
            try {
                lifecycleOwner.close()
            } catch (ownerFailure: Throwable) {
                failure = failure.append(ownerFailure)
            }
            failure?.let { throw GameModeException("Could not close the game mode session.", it) }
        }
    }

    private fun activateTransaction(mode: GameMode, replacing: Set<String>) {
        val affected = active.values.filter { it.mode.id in replacing }
        val unaffected = active.values.filterNot { it.mode.id in replacing }
        val plan = resolvePlan(mode, unaffected.map { it.mode.id }.toSet(), replacing)
        val combined = unaffected.map { it.mode } + plan
        validateCombined(combined)
        validateObservedBaseConflicts(combined, nativeBaseMode)
        val removedDependents = affected.flatMap { old ->
            active.values.filter { state ->
                state.mode.id !in replacing &&
                        plan.none { it.id == old.mode.id } && old.mode.id in state.mode.requires
            }.map { it.mode.id }
        }.distinct()
        if (removedDependents.isNotEmpty()) {
            throw GameModeDependencyException(
                "Cannot replace ${replacing.joinToString()}; active modes depend on it: ${removedDependents.joinToString()}.",
            )
        }
        requireAuthority(active.values.map { it.mode } + plan)

        if (affected.isEmpty()) {
            val created = enablePlan(plan, unaffected, mode.id)
            created.forEach { active[it.mode.id] = it }
            try {
                applyGameSpeed()
            } catch (failure: Throwable) {
                created.asReversed().forEach { state ->
                    active.remove(state.mode.id)
                    failure.addSuppressedSafely(closeState(state))
                }
                try {
                    applyGameSpeed()
                } catch (restoreFailure: Throwable) {
                    failure.addSuppressedSafely(restoreFailure)
                }
                throw GameModeException("Could not apply game mode '${mode.id}'.", failure)
            }
            return
        }

        val oldInCloseOrder = orderedActive().filter { it.mode.id in replacing }.asReversed()
        var closeFailure: Throwable? = null
        oldInCloseOrder.forEach { state ->
            active.remove(state.mode.id)
            closeFailure = closeFailure.append(closeState(state))
        }
        try {
            applyGameSpeed()
        } catch (failure: Throwable) {
            closeFailure = closeFailure.append(failure)
        }
        if (closeFailure != null) {
            val restored = restoreStates(oldInCloseOrder.asReversed())
            restored.states.forEach { active[it.mode.id] = it }
            closeFailure?.addSuppressedSafely(restored.failure)
            try {
                applyGameSpeed()
            } catch (restoreSpeedFailure: Throwable) {
                closeFailure = closeFailure.append(restoreSpeedFailure)
            }
            closeFailure?.let { throw GameModeException("Could not replace the active game mode.", it) }
        }

        var created = emptyList<ActiveMode>()
        try {
            created = enablePlan(plan, active.values.toList(), mode.id)
            created.forEach { active[it.mode.id] = it }
            applyGameSpeed()
        } catch (failure: Throwable) {
            created.asReversed().forEach { state ->
                active.remove(state.mode.id)
                failure.addSuppressedSafely(closeState(state))
            }
            val restored = restoreStates(oldInCloseOrder.asReversed())
            restored.states.forEach { active[it.mode.id] = it }
            failure.addSuppressedSafely(restored.failure)
            try {
                applyGameSpeed()
            } catch (restoreFailure: Throwable) {
                failure.addSuppressedSafely(restoreFailure)
            }
            throw GameModeException(
                "Could not activate replacement mode '${mode.id}'; prior modes were restored when possible.",
                failure
            )
        }
    }

    private fun replacementIds(mode: GameMode): Set<String> = buildSet {
        if (mode.id in active) add(mode.id)
        if (mode.kind == GameModeKind.BASE) {
            active.values.filter { it.mode.kind == GameModeKind.BASE }.forEach { add(it.mode.id) }
        }
        // Drop automatic requirements that will have no remaining dependent after the old
        // mode is removed. resolvePlan() can add them back if the replacement still requires
        // them; modes required by any retained active mode stay in place.
        addOrphanedAutomaticDependencies(this)
    }

    private fun resolvePlan(root: GameMode, alreadyActive: Set<String>): List<GameMode> =
        resolvePlan(root, alreadyActive, emptySet())

    private fun resolvePlan(root: GameMode, alreadyActive: Set<String>, replacing: Set<String>): List<GameMode> {
        val result = LinkedHashMap<String, GameMode>()
        val visiting = LinkedHashSet<String>()
        val replacedModes = active.values.filter { it.mode.id in replacing }.associate { it.mode.id to it.mode }

        fun visit(mode: GameMode) {
            if (mode.id in alreadyActive || mode.id in result) return
            if (!visiting.add(mode.id)) {
                throw GameModeDependencyException("Game mode dependency cycle: ${(visiting + mode.id).joinToString(" -> ")}.")
            }
            mode.requires.sorted().forEach { requiredId ->
                if (requiredId in alreadyActive) return@forEach
                val required =
                    if (requiredId == root.id) root else registry.get(requiredId) ?: replacedModes[requiredId]
                    ?: throw MissingGameModeException(requiredId)
                visit(required)
            }
            visiting.remove(mode.id)
            result[mode.id] = mode
        }

        visit(root)
        return result.values.toList()
    }

    private fun validateCombined(modes: List<GameMode>) {
        val byId = LinkedHashMap<String, GameMode>()
        modes.forEach { mode ->
            check(
                byId.put(
                    mode.id,
                    mode
                ) == null
            ) { "Game mode '${mode.id}' appears more than once in an activation transaction." }
        }
        val bases = modes.filter { it.kind == GameModeKind.BASE }
        if (bases.size > 1) {
            throw GameModeConflictException("Base modes are mutually exclusive: ${bases.joinToString { it.id }}.")
        }
        modes.forEach { mode ->
            mode.requires.forEach { required ->
                if (required !in byId) {
                    throw GameModeDependencyException("Game mode '${mode.id}' requires inactive mode '$required'.")
                }
            }
            mode.conflictsWith.forEach { conflict ->
                if (conflict in byId && conflict != mode.id) {
                    throw GameModeConflictException("Game mode '${mode.id}' conflicts with '$conflict'.")
                }
            }
        }
        modes.indices.forEach { firstIndex ->
            for (secondIndex in firstIndex + 1 until modes.size) {
                val first = modes[firstIndex]
                val second = modes[secondIndex]
                if (first.id in second.conflictsWith || second.id in first.conflictsWith) {
                    throw GameModeConflictException("Game modes '${first.id}' and '${second.id}' conflict.")
                }
            }
        }
    }

    private fun validateObservedBaseConflicts(modes: List<GameMode>, observedBase: GameMode?) {
        if (observedBase == null || modes.any { it.kind == GameModeKind.BASE }) return
        modes.forEach { mode ->
            if (observedBase.id in mode.conflictsWith || mode.id in observedBase.conflictsWith) {
                throw GameModeConflictException(
                    "Game mode '${mode.id}' conflicts with the observed native base '${observedBase.id}'.",
                )
            }
        }
    }

    private fun enablePlan(plan: List<GameMode>, existing: List<ActiveMode>, rootId: String): List<ActiveMode> {
        val created = mutableListOf<ActiveMode>()
        try {
            plan.forEach { mode ->
                if (mode.id in active || existing.any { it.mode.id == mode.id } || created.any { it.mode.id == mode.id }) return@forEach
                val owner = EventOwner()
                val context = GameModeContext(gameRoom, this, owner)
                val state =
                    ActiveMode(mode, context, activationCounter.incrementAndGet(), automatic = mode.id != rootId)
                try {
                    mode.onEnable(context)
                } catch (failure: Throwable) {
                    failure.addSuppressedSafely(closeState(state))
                    throw failure
                }
                created += state
            }
        } catch (failure: Throwable) {
            created.asReversed().forEach { failure.addSuppressedSafely(closeState(it)) }
            throw failure
        }
        return created
    }

    private fun restoreStates(states: List<ActiveMode>): RestoreResult {
        val restored = mutableListOf<ActiveMode>()
        var failure: Throwable? = null
        states.forEach { old ->
            val owner = EventOwner()
            val context = GameModeContext(gameRoom, this, owner)
            val renewed = ActiveMode(old.mode, context, old.activationOrder, old.automatic)
            try {
                old.mode.onEnable(context)
                restored += renewed
            } catch (restoreFailure: Throwable) {
                restoreFailure.addSuppressedSafely(closeState(renewed))
                failure = failure.append(
                    GameModeException(
                        "Failed to restore game mode '${old.mode.id}' after a transaction rollback.",
                        restoreFailure
                    ),
                )
            }
        }
        return RestoreResult(restored, failure)
    }

    private fun closeState(state: ActiveMode): Throwable? {
        var failure: Throwable? = null
        try {
            state.mode.onDisable(state.context)
        } catch (disableFailure: Throwable) {
            failure = failure.append(disableFailure)
        }
        try {
            state.context.eventOwner.close()
        } catch (resourceFailure: Throwable) {
            failure = failure.append(resourceFailure)
        }
        return failure
    }

    private fun orderedActive(): List<ActiveMode> {
        val states = active.values.toList()
        if (states.size < 2) return states
        val byId = states.associateBy { it.mode.id }
        val incoming = states.associate { it.mode.id to 0 }.toMutableMap()
        val outgoing = states.associate { it.mode.id to mutableListOf<String>() }
        states.forEach { state ->
            state.mode.requires.filter { it in byId }.forEach { required ->
                outgoing.getValue(required) += state.mode.id
                incoming[state.mode.id] = incoming.getValue(state.mode.id) + 1
            }
        }
        val compare = compareBy<ActiveMode>(
            { if (it.mode.kind == GameModeKind.BASE) 0 else 1 },
            { it.mode.order },
            { it.activationOrder },
            { it.mode.id },
        )
        val ready = states.filter { incoming.getValue(it.mode.id) == 0 }.toMutableList()
        val result = mutableListOf<ActiveMode>()
        while (ready.isNotEmpty()) {
            val next = ready.minWithOrNull(compare)!!
            ready.remove(next)
            result += next
            outgoing.getValue(next.mode.id).forEach { dependent ->
                val remaining = incoming.getValue(dependent) - 1
                incoming[dependent] = remaining
                if (remaining == 0) ready += byId.getValue(dependent)
            }
        }
        if (result.size != states.size) {
            throw GameModeDependencyException("Active game modes contain a dependency cycle.")
        }
        return result
    }

    private fun dependentClosure(modeId: String): Set<String> {
        val result = LinkedHashSet<String>()
        var changed = true
        while (changed) {
            changed = false
            active.values.forEach { state ->
                if (state.mode.id !in result &&
                    (modeId in state.mode.requires || state.mode.requires.any { it in result })
                ) {
                    if (result.add(state.mode.id)) changed = true
                }
            }
        }
        return result
    }

    private fun addOrphanedAutomaticDependencies(removeIds: MutableSet<String>) {
        var changed: Boolean
        do {
            changed = false
            active.values.forEach { candidate ->
                if (candidate.automatic && candidate.mode.id !in removeIds &&
                    active.values.none { dependent ->
                        dependent.mode.id !in removeIds && candidate.mode.id in dependent.mode.requires
                    }
                ) {
                    if (removeIds.add(candidate.mode.id)) changed = true
                }
            }
        } while (changed)
    }

    private fun findBaseForNativeType(type: String?): GameMode? {
        if (type.isNullOrBlank()) return null
        return (active.values.map(ActiveMode::mode) + registry.all())
            .distinctBy(GameMode::id)
            .firstOrNull {
                it.kind == GameModeKind.BASE && it.nativeMapInfoType?.equals(
                    type,
                    ignoreCase = true
                ) == true
            }
    }

    private fun requireRegistered(id: String): GameMode = registry.get(id) ?: throw MissingGameModeException(id)

    private fun ownActivation(mode: GameMode, owner: EventOwner?) {
        if (owner == null) return
        val activationOrder = active[mode.id]?.takeIf { it.mode === mode }?.activationOrder ?: return
        try {
            owner.own(AutoCloseable {
                synchronized(lock) {
                    val current = active[mode.id]
                    if (!closed && current?.activationOrder == activationOrder) deactivate(mode.id)
                }
            })
        } catch (failure: Throwable) {
            failure.addSuppressedSafely(runCatching { deactivate(mode.id) }.exceptionOrNull())
            throw failure
        }
    }

    private fun checkMutable() {
        check(!closed) { "Game mode session is closed." }
        check(!changing) { "Game modes cannot be changed recursively from an activation callback." }
    }

    private fun applyGameSpeed() {
        val multipliers = active.values.mapNotNull { (it.mode as? GameSpeedMode)?.multiplier }
        if (multipliers.isEmpty()) {
            val oldEngine = speedEngine
            val oldMultiplier = originalSpeedMultiplier
            if (oldEngine != null && oldMultiplier != null) oldEngine.gameSpeedMultiplier = oldMultiplier
            speedEngine = null
            originalSpeedMultiplier = null
            return
        }

        val engine = runCatching { GameEngine.getInstance() as? RWGameEngine }.getOrNull()
            ?: throw GameModeException("Game speed mode requires an initialized native RWGameEngine.")
        if (!hasNativeAuthority(engine)) {
            throw GameModeAuthorityException("Game speed mode requires authority over the active native simulation.")
        }
        if (speedEngine !== engine) {
            val previousEngine = speedEngine
            val previousMultiplier = originalSpeedMultiplier
            if (previousEngine != null && previousMultiplier != null) {
                previousEngine.gameSpeedMultiplier = previousMultiplier
            }
            speedEngine = engine
            originalSpeedMultiplier = engine.gameSpeedMultiplier
        }
        val product = multipliers.fold(1f) { value, next -> value * next }
        check(product.isFinite()) { "Combined game speed multipliers overflowed." }
        val appliedMultiplier = checkNotNull(originalSpeedMultiplier) * product
        check(appliedMultiplier.isFinite()) { "Applied game speed multiplier must be finite." }
        engine.gameSpeedMultiplier = appliedMultiplier
    }

    private fun requireAuthority(modes: List<GameMode>) {
        val protected = modes.firstOrNull { it.requiresHostAuthority } ?: return
        if (!hasNativeAuthority()) {
            throw GameModeAuthorityException(
                "Game mode '${protected.id}' requires authority over the native simulation; clients and replay playback cannot change it.",
            )
        }
    }

    private fun Throwable?.append(next: Throwable?): Throwable? {
        if (next == null) return this
        val current = this
        if (current == null) return next
        if (current !== next) current.addSuppressedSafely(next)
        return current
    }

    private fun Throwable.addSuppressedSafely(next: Throwable?) {
        if (next != null && next !== this) addSuppressed(next)
    }
}

