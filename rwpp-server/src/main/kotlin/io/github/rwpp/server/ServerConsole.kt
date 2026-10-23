/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.server

import io.github.rwml.api.ProgressListener
import io.github.rwml.core.ExtensionManager
import io.github.rwml.core.ExtensionDescriptorReader
import io.github.rwml.host.HostPlatform
import io.github.rwpp.api.ApiExtensionDescriptor
import io.github.rwpp.api.command.CommandSender
import io.github.rwpp.api.command.CommandVisibility
import io.github.rwpp.api.command.GameCommandRuntime
import io.github.rwpp.api.command.SERVER
import io.github.rwpp.api.commands
import io.github.rwpp.api.net.ServerStatus
import org.jline.reader.Candidate
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM server launcher, frame loop and optional JLine console.
 *
 * The API is a built-in extension supplied by this application's class path, so the console starts
 * with an empty extension directory and no archive to install.
 */
object ServerConsole {

    internal fun launch(args: Array<String>, platform: () -> HostPlatform) {
        val config = parseArguments(args)
        if (config.showHelp) {
            println(USAGE)
            return
        }
        launch(config, platform())
    }

    /** Runs the native server loop on top of the built-in API extension. */
    @JvmStatic
    fun launch(args: Array<String>, platform: HostPlatform) {
        val config = parseArguments(args)
        if (config.showHelp) {
            println(USAGE)
            return
        }
        launch(config, platform)
    }

    private fun launch(config: LaunchConfig, platform: HostPlatform) {
        val manager = ExtensionManager(Paths.get(System.getProperty("user.dir", ".")), platform)
        val running = AtomicBoolean(true)
        val stopped = CountDownLatch(1)
        val customServer = AtomicReference<ServerExtension?>()
        val shutdown = Thread({
            running.set(false)
            customServer.get()?.requestStop()
            stopped.await(10, TimeUnit.SECONDS)
        }, "rwpp-server-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            platform.stageArchives(manager.extensionDirectory()).forEach { println("[rwml] $it") }
            // ExtensionManager.loadAll installs this same bridge before registering archive mixins.
            // Keep that established host lifecycle instead of coupling the API to a JVM transformer.
            val archives = manager.discover()
            archives.forEach { archive ->
                val descriptor = ExtensionDescriptorReader.read(archive)
                check(descriptor.uiFramework().isNullOrBlank() && descriptor.tags().none {
                    val tag = it.lowercase().replace('_', '-')
                    tag == "client" || tag.startsWith("client-") || tag == "ui" || tag.startsWith("ui-")
                }) { "Client/UI extension cannot be loaded by RWPP Server: ${descriptor.id()}" }
            }
            val report = manager.loadAll(archives) { percent, message ->
                println("[rwml] $percent% $message")
            }
            report.loaded().forEach { handle ->
                val descriptor = handle.descriptor()
                println("[rwml] + ${descriptor.name()} ${descriptor.version()} [${descriptor.id()}]")
            }
            report.disabled().forEach { println("[rwml] - $it (disabled)") }
            report.failed().forEach { (id, reason) -> println("[rwml] ! $id: $reason") }
            check(manager.handle(ApiExtensionDescriptor.ID) != null) {
                "The built-in API extension did not load. Check that rwpp-api is on the class path."
            }

            check(report.failed().isEmpty()) { "Server extensions failed to load: ${report.failed()}" }
            val modes = report.loaded().mapNotNull { it.instance() as? ServerExtension }
            check(modes.size <= 1) { "Only one extension may provide the server's simulation loop" }
            customServer.set(modes.singleOrNull())
            val mode = customServer.get()
            if (mode != null) {
                println("[rwpp-server] Running server extension ${mode.javaClass.name}")
                mode.runServer(ServerRuntimeContext(manager.rootDirectory(), config.arguments, running))
                return
            }
            check(config.extensionArguments.isEmpty()) { "Extension arguments were supplied, but no server extension is loaded" }

            val server = NativeHeadlessServer(config.options)
            server.use { server ->
                server.start()
                println("[rwml-server] Listening on port ${config.options.port}; map ${config.options.map}")
                runConsole(server, config, running)
            }
        } finally {
            try {
                manager.shutdown()
            } finally {
                stopped.countDown()
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        }
    }

    private fun runConsole(server: NativeHeadlessServer, config: LaunchConfig, running: AtomicBoolean) {
        val pendingCommands = LinkedBlockingQueue<String>()
        try {
            registerServerCommands(server) { running.set(false) }

            if (config.noConsole) {
                runFrames(server, pendingCommands, running, config.ticks, null)
                return
            }
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
                                // Preserve commands already read from redirected input before stopping.
                                pendingCommands.put("stop")
                                break
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

                try {
                    runFrames(server, pendingCommands, running, config.ticks, sender)
                } finally {
                    running.set(false)
                    input.interrupt()
                }
            }
        } finally {
            running.set(false)
        }
    }

    private fun runFrames(
        server: NativeHeadlessServer,
        pendingCommands: LinkedBlockingQueue<String>,
        running: AtomicBoolean,
        ticks: Int,
        sender: CommandSender?,
    ) {
        val loopStartedNanos = System.nanoTime()
        var previousFrameNanos = loopStartedNanos - FRAME_PERIOD_NANOS
        var nextFrameNanos = loopStartedNanos
        var frames = 0
        while (running.get()) {
            while (true) {
                val line = pendingCommands.poll() ?: break
                if (sender != null) GameCommandRuntime.router.execute(line, sender, allowUnprefixed = true)
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
                frames++
                if (ticks in 1..frames) {
                    println("[rwpp-server] Completed $frames frames")
                    break
                }

                nextFrameNanos += FRAME_PERIOD_NANOS
                val remainingNanos = nextFrameNanos - System.nanoTime()
                if (remainingNanos > 0L) {
                    LockSupport.parkNanos(remainingNanos)
                } else {
                    // A slow native frame uses its actual delta without catch-up frames.
                    nextFrameNanos = System.nanoTime()
                }
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
            // 终端可能在输入流结束时关闭，而帧循环与后台线程仍会报告状态。此时没有可写出的位置，
            // 丢弃一条控制台消息比让整个线程以未捕获异常结束更合理。
            try {
                val reader = lineReader
                if (reader != null) {
                    reader.printAbove(message)
                } else {
                    terminal.writer().println(message)
                    terminal.flush()
                }
            } catch (_: IllegalStateException) {
                // 终端已关闭。
            }
        }

        override fun hasPermission(permission: String): Boolean = true

        override fun hasVisibility(visibility: String): Boolean = visibility == CommandVisibility.SERVER
    }

    private data class LaunchConfig(
        val options: NativeHeadlessServer.Options,
        val showHelp: Boolean,
        val noConsole: Boolean = false,
        val ticks: Int = 0,
        val arguments: List<String> = emptyList(),
        val extensionArguments: List<String> = emptyList(),
    )

    private fun parseArguments(args: Array<String>): LaunchConfig {
        var port = 5123
        var map = NativeHeadlessServer.Options.DEFAULT_MAP
        var name = "RWPP Server"
        var visible = false
        var maxPlayers: Int? = null
        var transferMods = false
        var showHelp = false
        var noConsole = false
        var ticks = 0
        var extensionArguments = emptyList<String>()
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            fun value(): String {
                require(index + 1 < args.size) { "Missing value for $argument\n$USAGE" }
                index++
                return args[index]
            }
            when (argument) {
                "--" -> {
                    extensionArguments = args.drop(index + 1)
                    break
                }
                "--help", "-h" -> showHelp = true
                "--port" -> port = value().toInt()
                "--map" -> map = value()
                "--name" -> name = value()
                "--max-players" -> maxPlayers = value().toInt()
                "--visible" -> visible = true
                "--transfer-mods" -> transferMods = true
                "--no-console" -> noConsole = true
                "--ticks" -> ticks = value().toInt().also { require(it > 0) { "Ticks must be positive" } }
                else -> error("Unknown argument: $argument\n$USAGE")
            }
            index++
        }
        return LaunchConfig(
            options = NativeHeadlessServer.Options(port, map, name, visible, maxPlayers, transferMods),
            showHelp = showHelp,
            noConsole = noConsole,
            ticks = ticks,
            arguments = args.filter { it != "--" },
            extensionArguments = extensionArguments,
        )
    }

    private const val USAGE = """
        Usage: rwpp-server [options]
          --port <port>          Native game server port (default: 5123)
          --map <game-path>      Initial map path
          --name <player-name>   Server player name
          --visible              Advertise the server in the native server list
          --transfer-mods        Allow clients to download enabled server mods
          --max-players <count>  Maximum player slots
          --help                 Show this help
          --no-console           Run without reading standard input
          --ticks <count>        Stop cleanly after this many native frames
          -- <extension-args>    Arguments for the installed server extension

        Console commands: help, start, map <path>, status, say <message>, stop
    """

    private const val PROMPT = "> "
    private const val FRAME_PERIOD_NANOS = 16_666_667L
}
