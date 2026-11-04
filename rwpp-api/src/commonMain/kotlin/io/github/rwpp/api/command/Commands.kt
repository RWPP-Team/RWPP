/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.command

@DslMarker
annotation class CommandDsl

/**
 * 命令的可见范围。
 *
 * 与权限一样按字符串声明：命令声明它只应该在哪些环境出现，发送者说明自己覆盖哪些环境，
 * 两者全部吻合才可见。宿主可以定义自己的范围，这里只提供两个标准值；命令不声明范围时
 * 在任何环境都可见。
 */
object CommandVisibility

/** 游戏客户端进程。 */
val CommandVisibility.CLIENT: String
    get() = "client"

/** Visibility for commands available in a standalone server process. */
val CommandVisibility.SERVER: String
    get() = "server"

/** Command actor implemented by a player, the host application, or another caller. */
interface CommandSender {
    fun reply(message: String)

    fun hasPermission(permission: String): Boolean

    /** 发送者所在的环境是否覆盖该可见范围。 */
    fun hasVisibility(visibility: String): Boolean
}

class CommandContext(val sender: CommandSender, val args: List<String>) {
    fun reply(message: String) = sender.reply(message)

    fun argument(index: Int): String =
        args.getOrNull(index) ?: throw CommandException(CommandText.text("veil.commands.9b8d3c13", index + 1))
}

class CommandException(message: String) : IllegalArgumentException(message)

@CommandDsl
class CommandBuilder internal constructor(val name: String, private val describe: () -> String) {
    val description: String get() = describe()

    private var usageValue = ""
    private var usageSource: (() -> String)? = null

    var usage: String
        get() = usageSource?.invoke() ?: usageValue
        set(value) {
            usageValue = value
            usageSource = null
        }

    fun usage(source: () -> String) {
        usageSource = source
    }

    internal val aliases = mutableListOf<String>()
    internal var permission: String? = null
    internal val visibility = mutableListOf<String>()
    internal var execute: CommandContext.() -> Unit = {}
    internal var completion: (List<String>) -> List<String> = { emptyList() }
    internal val children = Commands.nested()

    fun aliases(vararg values: String) {
        aliases.addAll(values)
    }

    fun requirePermission(value: String) {
        permission = value
    }

    /**
     * 声明命令只在这些可见范围内出现，可多次调用。声明过的范围必须全部被发送者覆盖才可见，
     * 不声明则任何环境都可见。
     */
    fun requireVisibility(vararg values: String) {
        visibility.addAll(values)
    }

    fun body(block: CommandContext.() -> Unit) {
        execute = block
    }

    fun onComplete(block: (List<String>) -> List<String>) {
        completion = block
    }

    fun command(name: String, description: String, block: CommandBuilder.() -> Unit) =
        children.command(name, description, block)
}

/**
 * 命令注册表。公开创建的注册表默认包含 help；内部子命令注册表由父命令显示帮助。
 *
 * 命令应在接受输入之前注册；命令体本身由调用方决定在哪个线程执行，游戏内通常在游戏循环上执行。
 */
@CommandDsl
class Commands private constructor(registerHelp: Boolean) {
    constructor() : this(registerHelp = true)

    private val entries = linkedMapOf<String, CommandBuilder>()

    init {
        if (registerHelp) registerHelpCommand()
    }

    /** Adds the permission- and visibility-filtered built-in help command once. */
    fun registerHelpCommand(): Commands {
        if ("help" !in entries) {
            command("help", { CommandText.text("rwml.commands.help") }) {
                body { this@Commands.help(sender).forEach(sender::reply) }
            }
        }
        return this
    }

    fun command(name: String, description: String, block: CommandBuilder.() -> Unit = {}) {
        command(name, { description }, block)
    }

    fun command(name: String, description: () -> String, block: CommandBuilder.() -> Unit = {}) {
        val command = CommandBuilder(name, description).apply(block)
        val names = listOf(name) + command.aliases
        require(
            names.distinct().size == names.size &&
                    names.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*")) && it !in entries },
        ) {
            CommandText.text("veil.commands.e4872081")
        }
        names.forEach { name -> entries[name] = command }
    }

    /** 列出 [sender] 有权看到的命令及其用法。 */
    fun help(sender: CommandSender): List<String> = entries.values.distinct().filter { allowed(it, sender) }
        .map { "${it.name}${if (it.usage.isBlank()) "" else " ${it.usage}"} — ${it.description}" }

    private fun allowed(command: CommandBuilder, sender: CommandSender) =
        (command.permission?.let(sender::hasPermission) != false) &&
                command.visibility.all(sender::hasVisibility)

    fun execute(line: String, sender: CommandSender): Boolean = try {
        dispatch(tokenize(line), sender)
    } catch (error: CommandException) {
        sender.reply(error.message ?: CommandText.text("veil.commands.8fdf5eda"))
        false
    }

    private fun dispatch(tokens: List<String>, sender: CommandSender): Boolean {
        if (tokens.isEmpty()) return true
        val command =
            entries[tokens.first()] ?: throw CommandException(
                CommandText.text(
                    "veil.commands.176356ca",
                    tokens.first()
                )
            )
        if (!allowed(command, sender)) throw CommandException(CommandText.text("veil.commands.59b77dbd"))
        val args = tokens.drop(1)
        if (command.children.entries.isNotEmpty()) {
            if (args.isEmpty()) {
                command.children.help(sender).forEach(sender::reply)
            } else {
                command.children.dispatch(args, sender)
            }
        } else {
            CommandContext(sender, args).run(command.execute)
        }
        return true
    }

    fun complete(words: List<String>, sender: CommandSender): List<String> {
        if (words.size <= 1) {
            return entries.filter { (name, command) ->
                name.startsWith(words.firstOrNull().orEmpty()) && allowed(command, sender)
            }.keys.toList()
        }
        val command = entries[words.first()] ?: return emptyList()
        if (!allowed(command, sender)) return emptyList()
        return if (command.children.entries.isNotEmpty()) {
            command.children.complete(words.drop(1), sender)
        } else {
            command.completion(words.drop(1)).filter { it.startsWith(words.last()) }
        }
    }

    companion object {
        internal fun nested() = Commands(registerHelp = false)

        /** 拆分命令行：支持引号、转义空白与空引号参数，格式错误时不会执行任何命令。 */
        fun tokenize(line: String): List<String> {
            if (line.length > 4096) throw CommandException(CommandText.text("veil.commands.dcf19d33"))
            val result = mutableListOf<String>()
            val token = StringBuilder()
            var quote: Char? = null
            var escaped = false
            var started = false
            for (char in line) {
                when {
                    escaped -> {
                        token.append(char)
                        escaped = false
                        started = true
                    }

                    char == '\\' -> {
                        escaped = true
                        started = true
                    }

                    quote != null -> if (char == quote) quote = null else token.append(char)

                    char == '\'' || char == '"' -> {
                        quote = char
                        started = true
                    }

                    char.isWhitespace() -> if (started) {
                        result.add(token.toString())
                        token.setLength(0)
                        started = false
                    }

                    else -> {
                        token.append(char)
                        started = true
                    }
                }
            }
            if (quote != null || escaped) throw CommandException(CommandText.text("veil.commands.8151d66d"))
            if (started) result.add(token.toString())
            return result
        }
    }
}
