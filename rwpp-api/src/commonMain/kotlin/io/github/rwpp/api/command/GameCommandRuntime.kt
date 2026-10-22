/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.command

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwml.api.ExtensionContext
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.commands
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.ChatMessageEvent
import io.github.rwpp.api.event.events.ChatOrigin
import io.github.rwpp.api.event.events.GameLoadedEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.commandPrefix
import kotlinx.coroutines.launch

/** Context used by the default and host-provided chat-command permission policies. */
data class ChatCommandContext(
    val origin: ChatOrigin,
    val room: GameRoom,
    val player: Team,
) {
    /** A command entered locally by the authoritative host. */
    val isLocalAuthoritativeHost: Boolean
        get() = origin == ChatOrigin.LOCAL_SEND && room.isHost && player === room.localPlayer

    /** A command relayed by the server from another player's connection. */
    val isRemotePlayer: Boolean
        get() = origin == ChatOrigin.REMOTE_RELAY
}

/** Permission policy for commands received through the shared native chat path. */
fun interface ChatCommandPermissionPolicy {
    fun hasPermission(permission: String, context: ChatCommandContext): Boolean
}

/**
 * Decides whether a locally submitted command line belongs to RWML's local command router.
 * Returning false leaves the line to the platform's normal chat path. This is useful for clients
 * whose active session sends prefixed input to a remote command owner.
 */
fun interface LocalChatCommandPolicy {
    fun shouldRouteLocally(room: GameRoom, message: String): Boolean
}

/**
 * The API-owned native chat command service. Its router is shared by UI helpers and native chat
 * hooks, so consuming a command in either path prevents it from being sent as ordinary chat.
 * Install this once from the built-in API extension; it subscribes to the existing event channel.
 */
object GameCommandRuntime {
    private data class GameSession(
        val engine: GameEngine?,
        val game: Game,
    )

    private val lock = Any()
    @Volatile
    private var eventOwner: EventOwner? = null

    private val localPolicyLock = Any()
    private data class LocalPolicyRegistration(val token: Any, val policy: LocalChatCommandPolicy)

    @Volatile
    private var localPolicies: List<LocalPolicyRegistration> = emptyList()

    @Volatile
    private var activeSession: GameSession? = null

    /** Public router for hosts to add exact prefixes and their command registries. */
    val router: CommandRouter = CommandRouter(unprefixedCommands = commands)

    /**
     * By default only a local command entered by the authoritative host has permissions.
     * Remote relay senders can still run commands that declare no permission.
     */
    @Volatile
    var permissionPolicy: ChatCommandPermissionPolicy = ChatCommandPermissionPolicy { _, context ->
        context.isLocalAuthoritativeHost
    }

    /** Install the service's subscriptions once. No engine or Koin game service is read here. */
    fun install(context: ExtensionContext) {
        synchronized(lock) {
            if (eventOwner != null) return
            val owner = EventOwner(context)
            eventOwner = owner
            try {
                owner.own(AutoCloseable { clearInstallation(owner) })
                router.register(commandPrefix, commands, owner)
                GlobalEventChannel.filter(GameLoadedEvent::class).subscribeAlways(owner) { event ->
                    if (eventOwner !== owner) return@subscribeAlways
                    // The API publishes this only after the engine and host Koin modules are ready.
                    val engine = event.engine ?: return@subscribeAlways
                    val game = appKoin.get<Game>()
                    synchronized(lock) {
                        if (eventOwner === owner) activeSession = GameSession(engine, game)
                    }
                }
                GlobalEventChannel.filter(ChatMessageEvent::class).subscribeAlways(owner) { event ->
                    if (eventOwner === owner) onChatMessage(event)
                }
            } catch (failure: Throwable) {
                try {
                    owner.close()
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                } finally {
                    clearInstallation(owner)
                }
                throw failure
            }
        }
    }

    /**
     * Adds an owner-scoped local routing policy. All registered policies must allow local handling;
     * closing [owner] removes this policy, so session-specific prefixes can be handed back safely.
     */
    fun registerLocalChatPolicy(owner: EventOwner, policy: LocalChatCommandPolicy): AutoCloseable {
        val token = Any()
        val registration = LocalPolicyRegistration(token, policy)
        synchronized(localPolicyLock) {
            localPolicies = localPolicies + registration
        }
        return owner.own(AutoCloseable {
            synchronized(localPolicyLock) {
                localPolicies = localPolicies.filterNot { it.token === token }
            }
        })
    }

    /**
     * Routes a locally submitted UI line through this service. Returns null for ordinary chat or
     * when the game service is not ready; any matched prefix is consumed, even for unknown names.
     */
    fun executeLocalInput(room: GameRoom, message: String, title: String = "RWML"): Boolean? {
        if (!router.hasPrefix(message) || !shouldRouteLocally(room, message)) return null
        val session = activeSession ?: return null
        if (session.game.gameRoom !== room) return null
        val player = room.localPlayer
        return scheduleExecution(session, room, message, title, ChatOrigin.LOCAL_SEND, player)
    }

    private fun onChatMessage(event: ChatMessageEvent) {
        if (event.origin == ChatOrigin.RECEIVE || !router.hasPrefix(event.message)) return
        val session = activeSession ?: return
        val room = session.game.gameRoom
        if (event.origin == ChatOrigin.LOCAL_SEND && !shouldRouteLocally(room, event.message)) return
        val player = event.player ?: when (event.origin) {
            ChatOrigin.LOCAL_SEND -> room.localPlayer
            else -> return
        }

        when (event.origin) {
            ChatOrigin.LOCAL_SEND -> if (player !== room.localPlayer) return
            ChatOrigin.REMOTE_RELAY -> {
                // Relay events are handled only by the host, and never for the host's own player.
                if (!room.isHost || player === room.localPlayer) return
            }
            ChatOrigin.RECEIVE -> return
        }
        if (!isCurrentTeam(room, player)) return

        val message = event.message
        event.cancel()
        scheduleExecution(session, room, message, "RWML", event.origin, player)
    }

    private fun scheduleExecution(
        session: GameSession,
        room: GameRoom,
        message: String,
        title: String,
        origin: ChatOrigin,
        player: Team,
    ): Boolean? {
        if (!router.hasPrefix(message)) return null
        GameScope.launch(GameMainDispatcher) {
            // A room or team slot may have changed while this command waited for the game thread.
            if (activeSession !== session || session.game.gameRoom !== room) return@launch
            if (GameEngine.getInstance() !== session.engine) return@launch
            if (!isCurrentTeam(room, player)) return@launch
            if (origin == ChatOrigin.LOCAL_SEND && player !== room.localPlayer) return@launch
            if (origin == ChatOrigin.REMOTE_RELAY && (!room.isHost || player === room.localPlayer)) return@launch

            val commandContext = ChatCommandContext(origin, room, player)
            val sender = object : CommandSender {
                override fun reply(message: String) {
                    room.sendMessageToPlayer(player, title, message)
                }

                override fun hasPermission(permission: String): Boolean =
                    permissionPolicy.hasPermission(permission, commandContext)

                override fun hasVisibility(visibility: String): Boolean = visibility == CommandVisibility.CLIENT
            }
            router.execute(message, sender)
        }
        // A matched prefix belongs to its selected registry even if execution reports an error.
        return true
    }

    private fun isCurrentTeam(room: GameRoom, player: Team): Boolean =
        (player === room.localPlayer && player.teamId < 0) || Team.getTeam(player.teamId) === player

    private fun shouldRouteLocally(room: GameRoom, message: String): Boolean =
        localPolicies.all { it.policy.shouldRouteLocally(room, message) }

    private fun clearInstallation(owner: EventOwner) {
        synchronized(lock) {
            if (eventOwner === owner) {
                eventOwner = null
                activeSession = null
            }
        }
    }
}
