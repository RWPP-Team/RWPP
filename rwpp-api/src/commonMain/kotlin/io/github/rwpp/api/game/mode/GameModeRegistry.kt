/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.mode

import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.game.GameRoom
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe registry of definitions used to resolve mode IDs and dependencies. */
class GameModeRegistry {
    private val modes = ConcurrentHashMap<String, GameMode>()

    /** Registers one definition and returns a handle that removes that exact definition. */
    fun register(mode: GameMode, owner: EventOwner? = null): AutoCloseable {
        validateMode(mode)
        if (modes.putIfAbsent(mode.id, mode) != null) throw DuplicateGameModeException(mode.id)
        val registration = AutoCloseable {
            modes.remove(mode.id, mode)
        }
        return if (owner == null) registration else try {
            owner.own(registration)
        } catch (failure: Throwable) {
            registration.close()
            throw failure
        }
    }

    fun get(id: String): GameMode? = modes[id]

    fun all(): List<GameMode> = modes.values.sortedWith(compareBy<GameMode>({ it.order }, { it.id }))

    internal fun validateMode(mode: GameMode) {
        require(mode.id.isNotBlank() && mode.id.none { it.isWhitespace() }) {
            "Game mode IDs must be non-empty and contain no whitespace."
        }
        require(mode.id !in mode.requires) { "Game mode '${mode.id}' cannot require itself." }
        require(mode.id !in mode.conflictsWith) { "Game mode '${mode.id}' cannot conflict with itself." }
        require(mode.requires.none(String::isBlank)) { "Game mode '${mode.id}' has a blank dependency ID." }
        require(mode.conflictsWith.none(String::isBlank)) { "Game mode '${mode.id}' has a blank conflict ID." }
    }
}

/** Default registry with the native skirmish and survival base modes. */
object GameModes {
    val registry: GameModeRegistry = GameModeRegistry().apply {
        register(SkirmishGameMode())
        register(SurvivalGameMode())
    }

    /** Registers an extension or application mode in the shared default registry. */
    fun register(mode: GameMode, owner: EventOwner? = null): AutoCloseable = registry.register(mode, owner)

    /** Creates a room-owned session without involving the application Koin container. */
    fun newSession(room: GameRoom): GameModeSession = GameModeSession(room, registry)
}

