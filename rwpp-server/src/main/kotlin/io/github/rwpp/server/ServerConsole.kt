/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.server

import io.github.rwml.api.ProgressListener
import io.github.rwpp.api.command.CommandSender
import io.github.rwpp.api.command.CommandVisibility
import io.github.rwpp.api.command.GameCommandRuntime
import io.github.rwpp.api.command.SERVER
import io.github.rwpp.api.commands
import io.github.rwpp.api.net.ServerStatus
import io.github.rwml.core.ExtensionManager
import io.github.rwml.host.HostPlatform
import org.jline.reader.Candidate
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM server launcher and JLine console. Console behavior belongs to this server application;
 * the API module only exposes the original game's native headless engine wrapper.
 */
object ServerConsole {
    private const val API_EXTENSION_ID = "io.github.rwml.api"
    private const val API_ARCHIVE_NAME = "rwpp-api.jar"
    private const val API_ARCHIVE_RESOURCE = "/META-INF/rwml/extensions/rwpp-api.jar"

    /** Loads the API archive shipped by this server application, then runs the native server loop. */
    @JvmStatic
    fun launch(args: Array<String>, platform: HostPlatform) {
        val config = parseArguments(args)
        if (config.showHelp) {
            println(USAGE)
            return
        }

        val manager = ExtensionManager(Paths.get(System.getProperty("user.dir", ".")), platform)
        try {
            stageBundledApi(manager.extensionDirectory())?.let { println("[rwml] $it") }
            platform.stageArchives(manager.extensionDirectory()).forEach { println("[rwml] $it") }
            // ExtensionManager.loadAll installs this same bridge before registering archive mixins.
            // Keep that established host lifecycle instead of coupling the API to a JVM transformer.
            val report = manager.loadAll(manager.discover(), ProgressListener { percent, message ->
                println("[rwml] $percent% $message")
            })
            report.loaded().forEach { handle ->
                val descriptor = handle.descriptor()
                println("[rwml] + ${descriptor.name()} ${descriptor.version()} [${descriptor.id()}]")
            }
            report.disabled().forEach { println("[rwml] - $it (disabled)") }
            report.failed().forEach { (id, reason) -> println("[rwml] ! $id: $reason") }
            check(manager.handle(API_EXTENSION_ID) != null) {
                "The built-in RWML API extension did not load. Check the host archive and extension state."
            }

            val server = NativeHeadlessServer(config.options)
            try {
                server.start()
                println("[rwml-server] Listening on port ${config.options.port}; map ${config.options.map}")
                runConsole(server)
            } finally {
                server.close()
            }
        } finally {
            manager.shutdown()
        }
    }

    private fun stageBundledApi(extensionDirectory: Path): String? {
        val content = ServerConsole::class.java.getResourceAsStream(API_ARCHIVE_RESOURCE)
            ?.use { it.readBytes() }
            ?: error("The server distribution is missing $API_ARCHIVE_RESOURCE")
        Files.createDirectories(extensionDirectory)
        val archive = extensionDirectory.resolve(API_ARCHIVE_NAME)
        if (Files.isRegularFile(archive) && content.contentEquals(Files.readAllBytes(archive))) {
            return "cached bundled API extension"
        }
        Files.write(archive, content)
        return "installed bundled API extension"
    }

    private fun runConsole(server: NativeHeadlessServer) {
        val pendingCommands = LinkedBlockingQueue<String>()
        val running = AtomicBoolean(true)
        registerServerCommands(server) { running.set(false) }

        TerminalBuilder.builder().system(true).dumb(true).build().use { terminal ->
            val sender = ConsoleSender(terminal)
            val lineReader = createLineReader(terminal, sender)
            sender.attach(lineReader)
            val input = Thread({
                try {
                    while (running.get()) {
                        try {
                            pendingCommands.put(lineReader.readLine(PROMPT))
                        } catch (_: UserInterruptException) {
                            // Ctrl+C cancels the current edit; the server keeps running.
                        } catch (_: EndOfFileException) {
                            running.set(false)
                        }
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (failure: Throwable) {
                    sender.reply("[rwml-server] Console input failed: ${failure.message}")
                    running.set(false)
                }
            }, "rwml-server-console-input").apply {
                isDaemon = true
                start()
            }

            val loopStartedNanos = System.nanoTime()
            var previousFrameNanos = loopStartedNanos - FRAME_PERIOD_NANOS
            var nextFrameNanos = loopStartedNanos
            try {
                while (running.get()) {
                    while (true) {
                        val line = pendingCommands.poll() ?: break
                        GameCommandRuntime.router.execute(line, sender, allowUnprefixed = true)
                        if (!running.get()) break
                    }
                    if (running.get()) {
                        val frameStarted = System.nanoTime()
                        val elapsedNanos = (frameStarted - previousFrameNanos).coerceAtLeast(1L)
                        previousFrameNanos = frameStarted
                        val deltaMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
                            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                        val deltaTime = elapsedNanos.toFloat() / FRAME_PERIOD_NANOS
                        server.tick(deltaTime = deltaTime, deltaMillis = deltaMillis)

                        val remainingNanos = nextFrameNanos - System.nanoTime()
                        if (remainingNanos > 0L) {
                            LockSupport.parkNanos(remainingNanos)
                            nextFrameNanos += FRAME_PERIOD_NANOS
                        } else {
                            // A slow native frame uses its actual delta without catch-up frames.
                            nextFrameNanos = System.nanoTime() + FRAME_PERIOD_NANOS
                        }
                    }
                }
            } finally {
                running.set(false)
                input.interrupt()
            }
        }
    }

    private fun createLineReader(terminal: Terminal, sender: CommandSender): LineReader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .completer { _, parsedLine, candidates ->
                val line = parsedLine.line().substring(0, parsedLine.cursor())
                GameCommandRuntime.router.complete(line, sender, allowUnprefixed = true)
                    .forEach { candidates.add(Candidate(it)) }
            }
            .build()

    /** Registers the native server's console actions in the API's shared command registry. */
    @JvmStatic
    fun registerServerCommands(server: NativeHeadlessServer, stop: () -> Unit) {
        commands.command("start", "Start the selected map") {
            requireVisibility(CommandVisibility.SERVER)
            body {
                runCatching { server.startMatch() }
                    .onSuccess { reply("Game start requested.") }
                    .onFailure { reply(it.message ?: "Could not start the game.") }
            }
        }
        commands.command("map", "Select a map") {
            usage = "<path>"
            requireVisibility(CommandVisibility.SERVER)
            body {
                val path = args.joinToString(" ").trim()
                if (path.isBlank()) {
                    reply("Usage: map <path>")
                } else {
                    runCatching { server.changeMap(path) }
                        .onSuccess { reply("Selected map: $path") }
                        .onFailure { reply(it.message ?: "Could not select map.") }
                }
            }
        }
        commands.command("status", "Show server status") {
            requireVisibility(CommandVisibility.SERVER)
            body {
                runCatching { server.describeSelf() }.onSuccess { data ->
                    val info = data.infoPacket
                    reply(
                        if (info == null) {
                            "lobby; port ${server.options.port}; map ${server.engine.networkEngine.currentGameMapPath ?: "(none)"}"
                        } else {
                            "${if (info.status == ServerStatus.InGame) "running" else "lobby"}; " +
                                "${info.name} (${info.version}); port ${server.options.port}; " +
                                "players ${info.currentPlayer}/${info.maxPlayerSize}; map ${info.mapName}; " +
                                "mods ${info.mods.ifBlank { "(none)" }}"
                        },
                    )
                }.onFailure { reply(it.message ?: "Could not read the server description.") }
            }
        }
        commands.command("say", "Send a chat message") {
            usage = "<message>"
            requireVisibility(CommandVisibility.SERVER)
            body {
                val message = args.joinToString(" ").trim()
                if (message.isBlank()) {
                    reply("Usage: say <message>")
                } else {
                    runCatching { server.engine.networkEngine.sendChatMessage(message) }
                        .onSuccess { reply("Message sent.") }
                        .onFailure { reply(it.message ?: "Could not send message.") }
                }
            }
        }
        commands.command("stop", "Stop the server") {
            requireVisibility(CommandVisibility.SERVER)
            body {
                reply("Stopping server.")
                stop()
            }
        }
    }

    private class ConsoleSender(private val terminal: Terminal) : CommandSender {
        @Volatile
        private var lineReader: LineReader? = null

        fun attach(reader: LineReader) {
            lineReader = reader
        }

        override fun reply(message: String) {
            val reader = lineReader
            if (reader != null) {
                reader.printAbove(message)
            } else {
                terminal.writer().println(message)
                terminal.flush()
            }
        }

        override fun hasPermission(permission: String): Boolean = true

        override fun hasVisibility(visibility: String): Boolean = visibility == CommandVisibility.SERVER
    }

    private data class LaunchConfig(
        val options: NativeHeadlessServer.Options,
        val showHelp: Boolean,
    )

    private fun parseArguments(args: Array<String>): LaunchConfig {
        var port = 5123
        var map = NativeHeadlessServer.Options.DEFAULT_MAP
        var name = "RWML Server"
        var visible = false
        var maxPlayers: Int? = null
        var showHelp = false
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            fun value(): String {
                require(index + 1 < args.size) { "Missing value for $argument\n$USAGE" }
                index++
                return args[index]
            }
            when (argument) {
                "--help", "-h" -> showHelp = true
                "--port" -> port = value().toInt()
                "--map" -> map = value()
                "--name" -> name = value()
                "--max-players" -> maxPlayers = value().toInt()
                "--visible" -> visible = true
                else -> error("Unknown argument: $argument\n$USAGE")
            }
            index++
        }
        return LaunchConfig(
            options = NativeHeadlessServer.Options(port, map, name, visible, maxPlayers),
            showHelp = showHelp,
        )
    }

    private const val USAGE = """
        Usage: rwml-server [options]
          --port <port>          Native game server port (default: 5123)
          --map <game-path>      Initial map path
          --name <player-name>   Server player name
          --visible              Advertise the server in the native server list
          --max-players <count>  Maximum player slots
          --help                 Show this help

        Console commands: help, start, map <path>, status, say <message>, stop
    """

    private const val PROMPT = "rwml> "
    private const val FRAME_PERIOD_NANOS = 16_666_667L
}
