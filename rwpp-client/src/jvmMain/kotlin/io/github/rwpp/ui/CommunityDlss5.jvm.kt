/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwpp.api.i18n.readI18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Keep validation independent of the game's locale initialization; translate at the UI boundary. */
internal class Dlss5SetupException(val messageKey: String, vararg val arguments: Any?) :
    IllegalArgumentException(messageKey)

private fun dlss5Error(key: String, vararg arguments: Any?): Nothing = throw Dlss5SetupException(key, *arguments)
private fun dlss5Text(key: String, vararg arguments: Any?): String = readI18n("rwpp.dlss5.$key", *arguments)
private fun IllegalArgumentException.localizedDlss5Message(): String =
    if (this is Dlss5SetupException) dlss5Text(messageKey, *arguments) else dlss5Text("error.package")

/** Metadata needed before opening UniversalDLSS5 for the already-running Java game. */
internal data class CommunityDlss5LaunchTarget(
    val pid: Long,
    val javaExecutable: Path,
    val gameRoot: Path,
)

internal data class CommunityDlss5Controller(
    val directory: Path,
    val executable: Path,
    val version: String,
)

/** Pure path/process checks are kept separate so they can be tested without launching a DLL. */
internal object CommunityDlss5LaunchSupport {
    // OpenGL compatibility first shipped in v0.4.3. There is no upstream v0.4.4 release;
    // v0.5.1 is the latest release as of 2026-10-25. Only observed upstream releases are accepted.
    private val supportedOpenGlVersions = setOf("0.4.3", "0.5.0", "0.5.1")
    private val requiredFiles = listOf(
        "UniversalDLSS5.exe",
        "UniversalDLSS5.Injector.exe",
        "UniversalDLSS5.Bridge.dll",
        "UniversalDLSS5.NRHost.exe",
    )

    fun isJavaExecutable(command: String): Boolean {
        val name = command.substringAfterLast('\\').substringAfterLast('/')
        return name.equals("java.exe", ignoreCase = true) || name.equals("javaw.exe", ignoreCase = true)
    }

    fun supportsOpenGl(version: String): Boolean {
        val match = Regex("^\\s*(\\d+)\\.(\\d+)\\.(\\d+)").find(version) ?: return false
        val release = match.groupValues.drop(1).take(3).map { it.toIntOrNull() ?: return false }.joinToString(".")
        return release in supportedOpenGlVersions
    }

    fun inspectController(directory: String, version: String): CommunityDlss5Controller {
        if (directory.isBlank()) dlss5Error("error.emptyPath")
        val requested = try {
            Paths.get(directory)
        } catch (_: Exception) {
            dlss5Error("error.invalidPath")
        }
        if (!requested.isAbsolute) dlss5Error("error.absolutePath")
        val root = try {
            requested.toRealPath()
        } catch (_: IOException) {
            dlss5Error("error.unreadablePath")
        }
        if (!Files.isDirectory(root)) dlss5Error("error.notDirectory")

        val missing = requiredFiles.filterNot { Files.isRegularFile(root.resolve(it)) }
        if (missing.isNotEmpty()) {
            dlss5Error("error.missingFiles", missing.joinToString())
        }
        if (!supportsOpenGl(version)) {
            dlss5Error("error.version")
        }
        return CommunityDlss5Controller(root, root.resolve("UniversalDLSS5.exe"), version.trim())
    }

    fun detectCurrentTarget(
        pid: Long,
        executable: String?,
        gameRoot: String?,
        dataModel: String?,
    ): CommunityDlss5LaunchTarget {
        if (pid <= 0L) dlss5Error("error.process")
        if (dataModel != "64") {
            dlss5Error("error.architecture")
        }
        val executablePath = executable?.takeIf(String::isNotBlank)?.let {
            try {
                Paths.get(it).toRealPath()
            } catch (_: Exception) {
                null
            }
        } ?: dlss5Error("error.javaPath")
        if (!isJavaExecutable(executablePath.toString())) {
            dlss5Error("error.javaProcess")
        }
        val rootText = gameRoot?.takeIf(String::isNotBlank)
            ?: dlss5Error("error.gameRoot")
        val root = try {
            Paths.get(rootText).toRealPath()
        } catch (_: Exception) {
            null
        }
            ?: dlss5Error("error.gameRootUnreadable", rootText)
        if (!Files.isDirectory(root)) dlss5Error("error.gameRootDirectory", root)
        return CommunityDlss5LaunchTarget(pid, executablePath, root)
    }

}

internal actual fun launchCommunityDlss5(controllerDirectory: String): String {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
        return dlss5Text("error.windows")
    }

    val javaInfo = ProcessHandle.current().info()
    val target = try {
        CommunityDlss5LaunchSupport.detectCurrentTarget(
            pid = ProcessHandle.current().pid(),
            executable = javaInfo.command().orElse(null),
            gameRoot = System.getProperty("rwpp.gameRoot") ?: System.getProperty("user.dir"),
            dataModel = System.getProperty("sun.arch.data.model"),
        )
    } catch (e: IllegalArgumentException) {
        return e.localizedDlss5Message()
    }

    val requestedDirectory = try {
        Paths.get(resolveCommunityDlss5Directory(controllerDirectory))
    } catch (_: Exception) {
        return dlss5Text("error.invalidPath")
    }
    if (!requestedDirectory.isAbsolute) return dlss5Text("error.absolutePath")
    val controllerRoot = try {
        requestedDirectory.toRealPath().toString()
    } catch (_: IOException) {
        return dlss5Text("error.unreadablePath")
    }
    val controllerExe = Paths.get(controllerRoot, "UniversalDLSS5.exe")
    val version = queryWindowsFileVersion(controllerExe)
        ?: return dlss5Text("error.fileVersion")
    val controller = try {
        CommunityDlss5LaunchSupport.inspectController(controllerRoot, version)
    } catch (e: IllegalArgumentException) {
        return e.localizedDlss5Message()
    }

    val existing = try {
        findCommunityDlss5Processes(controller.directory.toString())
    } catch (_: Exception) {
        return dlss5Text("monitor.queryFailed")
    }
    if (existing.isNotEmpty()) {
        return launchStatus(controller, target, dlss5Text("status.alreadyRunning"))
    }

    return try {
        ProcessBuilder(controller.executable.toString())
            .directory(controller.directory.toFile())
            .start()
        launchStatus(controller, target, dlss5Text("status.started"))
    } catch (e: IOException) {
        dlss5Text("error.launch", e.message.orEmpty())
    }
}

private fun queryWindowsFileVersion(executable: Path): String? {
    val escaped = executable.toString().replace("'", "''")
    val command = "(Get-Item -LiteralPath '$escaped').VersionInfo.FileVersion"
    val process = try {
        ProcessBuilder(
            windowsPowerShellExecutable().toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            command
        )
            .redirectErrorStream(true)
            .start()
    } catch (_: Exception) {
        return null
    }
    return try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        output.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    } catch (_: Exception) {
        process.destroyForcibly()
        null
    }
}

private fun launchStatus(
    controller: CommunityDlss5Controller,
    target: CommunityDlss5LaunchTarget,
    prefix: String,
): String = buildString {
    append(prefix)
    append(' ')
    append(
        dlss5Text(
            "status.target",
            controller.version,
            target.pid.toString(),
            target.javaExecutable,
            target.gameRoot
        )
    )
    append(' ')
    append(dlss5Text("status.instructions"))
}
