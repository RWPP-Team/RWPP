/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.mode

import io.github.rwpp.api.appKoin
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.map.XMLMap
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.koin.core.component.KoinComponent

/** A mutually exclusive root ruleset or a composable room modifier. */
enum class GameModeKind { BASE, MODIFIER }

/**
 * A mode definition. Requirements are activated before the mode; conflicts are checked against
 * both active modes and each other. Independent modes are ordered by [order], then by activation
 * order. Lower values run first when applying map transforms.
 */
interface GameMode : KoinComponent {
    override fun getKoin(): Koin = appKoin

    /** Shared game service resolved from the API's Koin container. */
    val game: Game get() = getKoin().get<Game>()

    /** Current room, available without repeating Koin lookup in each mode implementation. */
    val gameRoom: GameRoom get() = game.gameRoom

    /** Shared coroutine scope for work that must run on the game thread. */
    val gameScope: CoroutineScope get() = GameScope

    val id: String
    val displayName: String get() = id
    val kind: GameModeKind get() = GameModeKind.MODIFIER
    val order: Int get() = 0
    val requires: Set<String> get() = emptySet()
    val conflictsWith: Set<String> get() = emptySet()

    /** Whether changing or applying this mode requires authority over the room's native game state. */
    val requiresHostAuthority: Boolean get() = false

    /** Native `map_info.type` identified by a base mode, if it has one. */
    val nativeMapInfoType: String? get() = null

    /** True when this mode can change a map before the host starts it. */
    val transformsMap: Boolean get() = false

    /** Called once after all required modes have activated. Register cleanup with [context]. */
    fun onEnable(context: GameModeContext) = Unit

    /** Called before this activation's owned resources are released. */
    fun onDisable(context: GameModeContext) = Unit

    /** Validate the final map after all active map transforms have run. */
    fun validateMap(map: XMLMap, context: GameModeContext) = Unit

    /** Apply this mode's map change. The session calls this only on the authoritative host. */
    fun transformMap(map: XMLMap, context: GameModeContext) = Unit
}

/** Resources registered by a mode are closed in reverse order when it is disabled. */
class GameModeContext internal constructor(
    val gameRoom: GameRoom,
    val session: GameModeSession,
    val eventOwner: EventOwner,
) {
    fun <T : AutoCloseable> own(resource: T): T = eventOwner.own(resource)

    fun onClose(action: () -> Unit) {
        eventOwner.own(AutoCloseable(action))
    }
}

open class GameModeException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class DuplicateGameModeException(id: String) : GameModeException("Game mode '$id' is already active or registered.")

class MissingGameModeException(id: String) : GameModeException("Game mode '$id' is not registered.")

class GameModeConflictException(message: String) : GameModeException(message)

class GameModeDependencyException(message: String) : GameModeException(message)

class GameModeAuthorityException(message: String) : GameModeException(message)

