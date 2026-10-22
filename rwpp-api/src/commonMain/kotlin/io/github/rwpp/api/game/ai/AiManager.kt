/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.ai

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.ai.AI
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.ApiRuntime
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.DisconnectEvent
import io.github.rwpp.api.event.events.GameOverEvent
import io.github.rwpp.api.event.events.MapChangedEvent
import io.github.rwpp.api.event.events.QuitGameEvent
import io.github.rwpp.api.event.events.ReturnMainMenuEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Chooses whether a custom controller runs alongside or in place of the native AI logic. */
enum class AiControllerMode {
    /** Keep native AI behavior enabled and run the controller after each configured AI update. */
    AUGMENT_NATIVE,

    /** Hold the native AI update flag and let the controller issue commands instead. */
    REPLACE_NATIVE,
}

/** Configuration for one custom AI controller. */
data class AiControllerOptions(
    val mode: AiControllerMode = AiControllerMode.AUGMENT_NATIVE,
    /** Number of native AI update ticks between callbacks. Must be at least one. */
    val tickInterval: Int = 1,
) {
    init {
        require(tickInterval > 0) { "AI controller tickInterval must be greater than zero." }
    }
}

/** Context passed to a custom controller on the game dispatcher. */
data class AiTickContext(
    /** The live native AI/team object. Use it only on GameMainDispatcher. */
    val ai: AI,
    /** Native simulation tick at which this callback started. */
    val tick: Int,
    /** Delta time passed by the native AI update. */
    val deltaTime: Float,
)

/** A custom driver for a native AI team. The callback is serialized on GameScope. */
fun interface AiController {
    suspend fun onTick(context: AiTickContext)
}

/** Initial options for creating a native AI team. Null difficulty keeps the game default. */
data class AiCreateOptions(
    /** Requested native team slot. Network hosts can only use the next free slot. */
    val teamId: Int? = null,
    /** Ally group for the AI; defaults to the native add-AI behavior. */
    val allyTeam: Int? = null,
    /** Native difficulty from -2 through 3. Null leaves the game-configured difficulty. */
    val difficulty: Int? = null,
    /** Optional explicit AI name. */
    val name: String? = null,
)

/**
 * Game-thread API for managing native AI teams and custom AI controllers.
 *
 * All operations which read or mutate live engine state are suspending so they can marshal
 * through GameMainDispatcher. Controller callbacks receive native objects on GameScope.
 */
object AiManager {
    /** Returns the native AI teams currently registered in the team table. */
    suspend fun getNativeAIs(): List<AI> = withContext(GameMainDispatcher) {
        val teams = ArrayList<AI>()
        for (teamId in 0 until Team.maxTeamId) {
            (Team.getTeam(teamId) as? AI)?.let(teams::add)
        }
        teams
    }

    /**
     * Creates an AI through the native team constructor or the host's native add-AI routine.
     * The returned AI is already registered with Team and may be used directly.
     */
    suspend fun createNativeAI(options: AiCreateOptions = AiCreateOptions()): AI =
        withContext(GameMainDispatcher) {
            val engine = requireAuthority()
            val network = engine.networkEngine
            val nextFreeId = Team.findFreeTeamId()
            check(nextFreeId >= 0) { "There is no free team slot for an AI." }

            val teamId = options.teamId ?: nextFreeId
            require(teamId in 0 until Team.maxTeamId) {
                "AI teamId must be in the native team slot range."
            }
            check(Team.getTeam(teamId) == null) { "Team slot $teamId is already occupied." }
            options.difficulty?.let(::requireDifficulty)
            options.allyTeam?.let {
                require(it in 0 until Team.maxTeamId) { "allyTeam must be in the native team slot range." }
            }
            if (network.networked) {
                require(teamId == nextFreeId) {
                    "A network host can add an AI only to the next free native team slot."
                }
            }

            val ai = if (network.networked) {
                network.addAIToGame()
                checkNotNull(Team.getTeam(teamId) as? AI) {
                    "The native host routine did not register an AI in slot $teamId."
                }
            } else {
                AI(teamId)
            }

            ai.allyTeam = options.allyTeam ?: ai.teamId % 2
            options.difficulty?.let { applyDifficulty(ai, it) }
            if (!network.networked) network.updateNamesOfAI()
            options.name?.let { ai.name = it }

            if (network.networked &&
                (options.allyTeam != null || options.difficulty != null || options.name != null)
            ) {
                network.sendUpdatePlayer(null)
            }
            ai
        }

    /** Removes the AI team through the native host kick path when networked. */
    suspend fun removeNativeAI(ai: AI) = withContext(GameMainDispatcher) {
        val engine = requireAuthority()
        requireCurrentAI(ai)
        AiControllerRuntime.unregister(ai)
        if (engine.networkEngine.isServer) {
            engine.networkEngine.kickTeamAndAttachedPlayer(ai)
        } else {
            ai.removeFromTeamArray()
        }
    }

    /**
     * Sets a specific native difficulty (-2..3), or null to inherit the game setting.
     * A specific value locks the AI to that difficulty even in single-player.
     */
    suspend fun setDifficulty(ai: AI, difficulty: Int?) = withContext(GameMainDispatcher) {
        val engine = requireAuthority()
        requireCurrentAI(ai)
        difficulty?.let(::requireDifficulty)
        applyDifficulty(ai, difficulty)
        if (engine.networkEngine.networked) engine.networkEngine.sendUpdatePlayer(null)
    }

    /** Pauses native AI logic using its persistent aiUpdatePaused flag. */
    suspend fun pauseNativeAI(ai: AI) = withContext(GameMainDispatcher) {
        requireAuthority()
        requireCurrentAI(ai)
        check(!AiControllerRuntime.isReplacingNative(ai)) {
            "The native AI is already held by a REPLACE_NATIVE controller."
        }
        ai.aiUpdatePaused = true
    }

    /** Resumes native AI logic and clears the native sleeping optimization. */
    suspend fun resumeNativeAI(ai: AI) = withContext(GameMainDispatcher) {
        requireAuthority()
        requireCurrentAI(ai)
        check(!AiControllerRuntime.isReplacingNative(ai)) {
            "The native AI is already held by a REPLACE_NATIVE controller."
        }
        ai.aiUpdatePaused = false
        ai.aiSleeping = false
    }

    /** Registers at most one custom controller for this native AI. */
    suspend fun registerController(
        ai: AI,
        controller: AiController,
        options: AiControllerOptions = AiControllerOptions(),
        owner: EventOwner? = null,
    ): AiControllerRegistration = withContext(GameMainDispatcher) {
        val engine = requireAuthority()
        check(engine.isGameRunning) { "Register AI controllers after the game has started." }
        requireCurrentAI(ai)
        val closed = AtomicBoolean(false)
        val id = AiControllerRuntime.register(ai, controller, options, closed)
        val registration = AiControllerRegistration(ai, id, closed)
        try {
            owner?.own(registration)
        } catch (failure: Throwable) {
            registration.close()
            AiControllerRuntime.unregister(ai, id)
            throw failure
        }
        registration
    }

    /**
     * Creates and configures a native command for this AI on the game dispatcher. The command is
     * queued by CommandController for normal dispatch.
     */
    suspend fun createCommand(
        ai: AI,
        configure: CommandControllerCommand.() -> Unit,
    ): CommandControllerCommand = withContext(GameMainDispatcher) {
        val engine = requireAuthority()
        check(engine.isGameRunning) { "AI commands require an active game." }
        requireCurrentAI(ai)
        val commandController = engine.commandController
        val command = commandController.createCommand(ai)
        try {
            command.configure()
            check(command.team === ai) {
                "An AI command builder must keep the command assigned to the requested AI."
            }
            command
        } catch (failure: Throwable) {
            var cleanupFailure: Throwable? = null
            for (pending in arrayOf(
                commandController.localPendingCommands,
                commandController.networkPendingCommands,
            )) {
                try {
                    removeExactPendingCommand(pending, command)
                } catch (cleanup: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = cleanup else first.addSuppressed(cleanup)
                }
            }
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun requireAuthority(): GameEngine {
        val engine = GameEngine.getInstance()
        check(!engine.replayEngine.isPlayingReplay) {
            "AI mutations and custom controllers are disabled during replay playback."
        }
        check(!engine.networkEngine.networked || engine.networkEngine.isServer) {
            "AI mutations and custom controllers require the authoritative host."
        }
        check(engine.networkEngine.networked || engine.isGameRunning) {
            "AI operations require an active single-player game or an authoritative network room."
        }
        return engine
    }

    private fun requireCurrentAI(ai: AI) {
        require(ai.isAI) { "The supplied team is not marked as a native AI." }
        val teamId = ai.teamId
        check(teamId >= 0 && teamId < Team.maxTeamId && Team.getTeam(teamId) === ai) {
            "The supplied AI is no longer registered in the native team table."
        }
    }

    private fun requireDifficulty(difficulty: Int) {
        require(difficulty in -2..3) { "Native AI difficulty must be between -2 and 3." }
    }

    private fun removeExactPendingCommand(
        commands: ArrayList<*>,
        command: CommandControllerCommand,
    ) {
        val iterator = commands.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() === command) iterator.remove()
        }
    }

    private fun applyDifficulty(ai: AI, difficulty: Int?) {
        val engine = GameEngine.getInstance()
        if (difficulty == null) {
            ai.aiDifficultyLocked = false
            ai.aiDifficultyOverride = null
            val network = engine.networkEngine
            ai.aiDifficulty =
                if (network.networked || network.singlePlayerServer) {
                    network.gameSetup.aiDifficulty
                } else {
                    engine.settings.aiDifficulty
                }
        } else {
            ai.aiDifficulty = difficulty
            ai.aiDifficultyLocked = true
            ai.aiDifficultyOverride = difficulty
        }
    }
}

/** Handle for explicitly closing one custom controller registration. */
class AiControllerRegistration internal constructor(
    private val ai: AI,
    private val id: Long,
    private val closed: AtomicBoolean,
) : AutoCloseable {
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            GameScope.launch { AiControllerRuntime.unregister(ai, id) }
        }
    }
}

internal object AiControllerRuntime {
    private class Entry(
        val id: Long,
        val ai: AI,
        val controller: AiController,
        val options: AiControllerOptions,
        val previousAiUpdatePaused: Boolean,
        val closed: AtomicBoolean,
        var nextTick: Int,
        var active: Boolean = true,
        var inFlight: Boolean = false,
        var job: Job? = null,
    )

    private val entries = IdentityHashMap<AI, Entry>()
    private var nextId = 1L

    fun register(
        ai: AI,
        controller: AiController,
        options: AiControllerOptions,
        closed: AtomicBoolean,
    ): Long {
        check(entries[ai] == null) { "A custom AI controller is already registered for this AI." }
        val id = nextId++
        val entry = Entry(
            id = id,
            ai = ai,
            controller = controller,
            options = options,
            previousAiUpdatePaused = ai.aiUpdatePaused,
            closed = closed,
            nextTick = GameEngine.getInstance().simulationTick,
        )
        entries[ai] = entry
        if (options.mode == AiControllerMode.REPLACE_NATIVE) ai.aiUpdatePaused = true
        return id
    }

    fun onNativeTick(target: Any, deltaTime: Float) {
        val ai = target as? AI ?: return
        val entry = entries[ai] ?: return
        if (entry.closed.get()) {
            unregister(entry)
            return
        }
        val engine = GameEngine.getInstance()
        // TAIL injections run before every RETURN in the target method. The native AI returns
        // early on clients and during replay playback; those paths must not run custom controllers.
        if (engine.replayEngine.isPlayingReplay ||
            (engine.networkEngine.networked && !engine.networkEngine.isServer)
        ) return
        if (!hasAuthority(engine) || !isCurrentAI(ai)) {
            unregister(entry)
            return
        }

        val tick = engine.simulationTick
        if (!entry.active || entry.inFlight || tick < entry.nextTick) return
        entry.nextTick = tick + entry.options.tickInterval
        entry.inFlight = true
        val context = AiTickContext(ai, tick, deltaTime)
        entry.job = GameScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (!entry.closed.get() &&
                    entry.active &&
                    hasAuthority(GameEngine.getInstance()) &&
                    isCurrentAI(ai)
                ) {
                    entry.controller.onTick(context)
                }
            } catch (cancelled: CancellationException) {
                if (entry.active) unregister(entry)
                throw cancelled
            } catch (failure: Throwable) {
                logFailure(ai, failure)
                unregister(entry)
            } finally {
                entry.inFlight = false
                entry.job = null
            }
        }
    }

    fun isReplacingNative(ai: AI): Boolean =
        entries[ai]?.options?.mode == AiControllerMode.REPLACE_NATIVE

    fun onTeamRemoval(target: Any) {
        val ai = target as? AI ?: return
        GameScope.launch {
            if (!isCurrentAI(ai)) unregister(ai)
        }
    }

    fun unregister(ai: AI) {
        entries[ai]?.let(::unregister)
    }

    fun unregister(ai: AI, id: Long) {
        val entry = entries[ai] ?: return
        if (entry.id == id) unregister(entry)
    }

    fun clear() {
        entries.values.toList().forEach(::unregister)
    }

    private fun unregister(entry: Entry) {
        entry.closed.set(true)
        if (!entry.active) return
        entry.active = false
        entries.remove(entry.ai)
        entry.job?.cancel()
        if (entry.options.mode == AiControllerMode.REPLACE_NATIVE && isCurrentAI(entry.ai)) {
            entry.ai.aiUpdatePaused = entry.previousAiUpdatePaused
        }
    }

    private fun hasAuthority(engine: GameEngine): Boolean =
        !engine.replayEngine.isPlayingReplay &&
            (!engine.networkEngine.networked || engine.networkEngine.isServer) &&
            (engine.networkEngine.networked || engine.isGameRunning)

    private fun isCurrentAI(ai: AI): Boolean {
        val teamId = ai.teamId
        return ai.isAI &&
            teamId >= 0 &&
            teamId < Team.maxTeamId &&
            Team.getTeam(teamId) === ai
    }

    private fun logFailure(ai: AI, failure: Throwable) {
        try {
            GameEngine.log(
                "RWML custom AI controller failed for team " + ai.teamId +
                    "; it has been unregistered: " + failure,
            )
        } catch (_: Throwable) {
            // The controller failure must not escape into the native simulation loop.
        }
    }
}

@Single(binds = [Initialization::class])
class AiApiInitialization : Initialization {
    override fun init() {
        val owner = EventOwner(ApiRuntime.extensionContext)
        owner.own(AutoCloseable {
            GameScope.launch { AiControllerRuntime.clear() }
        })

        GlobalEventChannel.filter(GameOverEvent::class).subscribeAlways(owner) {
            clearControllersOnGameThread()
        }
        GlobalEventChannel.filter(QuitGameEvent::class).subscribeAlways(owner) {
            clearControllersOnGameThread()
        }
        GlobalEventChannel.filter(ReturnMainMenuEvent::class).subscribeAlways(owner) {
            clearControllersOnGameThread()
        }
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(owner) {
            clearControllersOnGameThread()
        }
        GlobalEventChannel.filter(MapChangedEvent::class).subscribeAlways(owner) {
            clearControllersOnGameThread()
        }
    }

    private suspend fun clearControllersOnGameThread() {
        withContext(GameMainDispatcher) { AiControllerRuntime.clear() }
    }
}
