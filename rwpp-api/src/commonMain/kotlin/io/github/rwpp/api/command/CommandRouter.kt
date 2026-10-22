/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.command

import io.github.rwpp.api.event.EventOwner

/**
 * Routes prefixed input to its command registry. Longer prefixes win, and once a prefix matches,
 * that registry owns the input even when it reports an unknown command.
 *
 * [execute] returns `null` when no prefix matches and unprefixed input is not enabled. Callers
 * handling chat can use that result to leave ordinary messages untouched; a non-null result means
 * the input belongs to a command route, including when command execution returns `false`.
 */
class CommandRouter(
    private val unprefixedCommands: Commands? = null,
) {
    private data class Route(val prefix: String, val commands: Commands)

    private val lock = Any()

    @Volatile
    private var currentRoutes: List<Route> = emptyList()

    /** Register one exact prefix. More specific overlapping prefixes are selected first. */
    fun register(prefix: String, commands: Commands): CommandRouter {
        addRoute(prefix, commands)
        return this
    }

    /** Register a prefix owned by an extension; closing [owner] removes the route. */
    fun register(prefix: String, commands: Commands, owner: EventOwner): AutoCloseable {
        val route = addRoute(prefix, commands)
        val registration = RouteRegistration(this, route)
        owner.own(registration)
        return registration
    }

    private fun addRoute(prefix: String, commands: Commands): Route {
        require(prefix.isNotEmpty()) { "Command prefix cannot be empty" }
        return synchronized(lock) {
            val routes = currentRoutes
            require(routes.none { it.prefix == prefix }) { "Command prefix '$prefix' is already registered" }
            val route = Route(prefix, commands)
            currentRoutes = (routes + route).sortedByDescending { it.prefix.length }
            route
        }
    }

    private fun removeRoute(route: Route) {
        synchronized(lock) {
            if (currentRoutes.none { it === route }) return
            currentRoutes = currentRoutes.filterNot { it === route }
        }
    }

    private class RouteRegistration(
        private val router: CommandRouter,
        private val route: Route,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            router.removeRoute(route)
        }
    }

    /**
     * Execute a matching prefixed command, or an unprefixed command when [allowUnprefixed] is true.
     * Prefix matching is literal and does not trim or normalize input.
     */
    fun execute(
        line: String,
        sender: CommandSender,
        allowUnprefixed: Boolean = false,
    ): Boolean? {
        val route = currentRoutes.firstOrNull { line.startsWith(it.prefix) }
        if (route != null) {
            return route.commands.execute(line.drop(route.prefix.length), sender)
        }
        if (allowUnprefixed) return unprefixedCommands?.execute(line, sender)
        return null
    }

    /** Completes a partial command line using the same prefix, permission, and visibility rules. */
    fun complete(
        line: String,
        sender: CommandSender,
        allowUnprefixed: Boolean = false,
    ): List<String> {
        val route = currentRoutes.firstOrNull { line.startsWith(it.prefix) }
        if (route != null) {
            val routedLine = line.drop(route.prefix.length)
            val words = completionWords(routedLine)
            val completions = route.commands.complete(words, sender)
            return if (routedLine.isEmpty() || !routedLine.first().isWhitespace()) {
                completions.map { route.prefix + it }
            } else {
                completions
            }
        }

        // While the user is still typing a prefix, offer the registered prefixes themselves.
        val partialPrefixes = currentRoutes.map { it.prefix }.filter { it.startsWith(line) }
        if (line.isNotEmpty() && partialPrefixes.isNotEmpty()) return partialPrefixes

        return if (allowUnprefixed) {
            unprefixedCommands?.complete(completionWords(line), sender).orEmpty()
        } else {
            emptyList()
        }
    }

    private fun completionWords(line: String): List<String> {
        if (line.isEmpty()) return listOf("")
        val trimmed = line.trimStart()
        if (trimmed.isEmpty()) return listOf("")
        val words = trimmed.split(Regex("\\s+")).toMutableList()
        if (line.last().isWhitespace()) words.add("")
        return words
    }

    /** Whether this input begins with one of the registered prefixes. */
    fun hasPrefix(line: String): Boolean = currentRoutes.any { line.startsWith(it.prefix) }
}
