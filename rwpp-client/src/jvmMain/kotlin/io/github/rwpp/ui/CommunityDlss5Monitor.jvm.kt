/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwpp.api.i18n.readI18n
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class DlssControllerProcess(val pid: Long, val startedAt: Long)

/** Paths come from Windows process metadata, never window titles or a remembered launch request. */
internal fun parseDlssControllerProcesses(output: String): List<DlssControllerProcess> = output.lineSequence()
    .mapNotNull { line ->
        val fields = line.trim().split('\t')
        if (fields.size != 3 || fields[0] != "PROCESS") null
        else {
            val pid = fields[1].toLongOrNull()
            val startedAt = fields[2].toLongOrNull()
            if (pid != null && pid > 0 && startedAt != null && startedAt > 0) DlssControllerProcess(
                pid,
                startedAt
            ) else null
        }
    }.distinctBy { it.pid }.toList()

internal fun resolvePortableDlssDirectory(directory: String, gameRoot: Path): String {
    val normalized = directory.replace('\\', '/').lowercase().trimEnd('/')
    val managedLegacy = normalized.contains("/appdata/local/") && normalized.endsWith("/universaldlss5/app-0.5.1")
    val portable = gameRoot.resolve("tools/UniversalDLSS5")
    return if ((directory.isBlank() || managedLegacy) && Files.isRegularFile(portable.resolve("UniversalDLSS5.exe")))
        portable.toAbsolutePath().normalize().toString() else directory
}

internal actual fun resolveCommunityDlss5Directory(controllerDirectory: String): String = runCatching {
    resolvePortableDlssDirectory(
        controllerDirectory,
        Paths.get(System.getProperty("rwpp.gameRoot") ?: System.getProperty("user.dir"))
    )
}.getOrDefault(controllerDirectory)

private fun controllerExecutable(directory: String): Path {
    val resolved = resolveCommunityDlss5Directory(directory)
    if (resolved.isBlank()) throw Dlss5SetupException("error.emptyPath")
    val root = Paths.get(resolved)
    if (!root.isAbsolute) throw Dlss5SetupException("error.absolutePath")
    if (!Files.isDirectory(root)) throw Dlss5SetupException("monitor.folderMissing", root.toString())
    val executable = root.toRealPath().resolve("UniversalDLSS5.exe")
    if (!Files.isRegularFile(executable)) throw Dlss5SetupException("monitor.executableMissing", executable.toString())
    return executable
}

internal fun windowsPowerShellExecutable(): Path {
    val windowsRoot =
        System.getenv("SystemRoot") ?: System.getenv("WINDIR") ?: error("Windows system directory is unavailable")
    return Paths.get(windowsRoot, "System32/WindowsPowerShell/v1.0/powershell.exe").also {
        check(Files.isRegularFile(it)) { "PowerShell is missing: $it" }
    }
}

internal fun windowsControllerCommand(
    executable: Path,
    script: String,
    extra: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 8
): String {
    val command = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new(\$false); " +
            "\$ErrorActionPreference='Stop'; \$ProgressPreference='SilentlyContinue'; " + script
    // Windows PowerShell parses -Command quotes differently from the native argv convention.
    val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
    val powershell = windowsPowerShellExecutable()
    val builder =
        ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
    builder.environment()["RWPP_DLSS_CONTROLLER_EXE"] = executable.toString()
    builder.environment()["RWPP_DLSS_GAME_PID"] = ProcessHandle.current().pid().toString()
    builder.environment().putAll(extra)
    val process = builder.start()
    try {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) error("Controller process query timed out")
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.exitValue() == 0) { "PowerShell exit=${process.exitValue()}: ${output.trim().take(1200)}" }
        return output
    } finally {
        if (process.isAlive) process.destroyForcibly() // Only this short-lived metadata helper.
    }
}

private val queryScript = """
    foreach (${'$'}p in @(Get-Process -Name UniversalDLSS5 -ErrorAction SilentlyContinue)) {
        if ([string]::Equals(${'$'}p.Path, ${'$'}env:RWPP_DLSS_CONTROLLER_EXE, [StringComparison]::OrdinalIgnoreCase)) {
            'PROCESS' + "`t" + ${'$'}p.Id + "`t" + ${'$'}p.StartTime.ToFileTimeUtc()
        }
    }
    ${'$'}runtime = (Get-ItemProperty -LiteralPath 'HKCU:\Software\UniversalDLSS5' -Name RuntimePath -ErrorAction SilentlyContinue).RuntimePath
    if ([string]::IsNullOrWhiteSpace(${'$'}runtime)) {
        ${'$'}runtime = Join-Path ${'$'}env:LOCALAPPDATA 'UniversalDLSS5\runtime'
    }
    if (Test-Path -LiteralPath (Join-Path ${'$'}runtime 'nvngx_dlssnr.dll') -PathType Leaf) { 'RUNTIME' }
    if (Test-Path -LiteralPath (Join-Path (Split-Path ${'$'}env:RWPP_DLSS_CONTROLLER_EXE) 'runtime\nvngx_dlssnr.dll') -PathType Leaf) { 'RUNTIME' }
    if (${'$'}env:RWPP_DLSS_HELPER) {
        try {
            Add-Type -Path ${'$'}env:RWPP_DLSS_HELPER
            ${'$'}s = [RwppDlssControl]::Read([int]${'$'}env:RWPP_DLSS_GAME_PID)
            if (${'$'}s.Attached) {
                ${'$'}message = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}s.Message))
                'NEURAL' + "`t" + ${'$'}s.State + "`t" + ${'$'}s.Api + "`t" + ([int]${'$'}s.Active) + "`t" + ${'$'}s.NeuralFrames + "`t" + ${'$'}s.ProcessedFrames + "`t" + ${'$'}s.GpuMs.ToString([Globalization.CultureInfo]::InvariantCulture) + "`t" + ${'$'}s.FailureStage + "`t" + ${'$'}message
            }
        } catch { 'NEURAL_ERROR' }
    }
""".trimIndent()

internal fun findCommunityDlss5Processes(directory: String): List<DlssControllerProcess> =
    parseDlssControllerProcesses(windowsControllerCommand(controllerExecutable(directory), queryScript))

internal actual fun queryCommunityDlss5Status(controllerDirectory: String): CommunityDlss5Status {
    if (!System.getProperty("os.name", "").startsWith("Windows", true)) {
        return CommunityDlss5Status(errorKey = "rwpp.dlss5.error.windows")
    }
    return try {
        val executable = controllerExecutable(controllerDirectory)
        val helper = runCatching { ensureDlssControlHelper() }.getOrNull()
        val output =
            windowsControllerCommand(executable, queryScript, mapOf("RWPP_DLSS_HELPER" to helper?.toString().orEmpty()))
        CommunityDlss5Status(
            controllerPids = parseDlssControllerProcesses(output).map { it.pid },
            executable = executable.toString(),
            gamePid = ProcessHandle.current().pid(),
            runtimePresent = output.lineSequence().any { it.trim() == "RUNTIME" },
            checkedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            neural = parseCommunityDlss5NeuralStatus(output),
        )
    } catch (error: Dlss5SetupException) {
        recordDlssQueryFailure(controllerDirectory, error)
        CommunityDlss5Status(
            errorKey = "rwpp.dlss5.${error.messageKey}",
            errorArguments = error.arguments.map { it.toString() })
    } catch (error: Exception) {
        recordDlssQueryFailure(controllerDirectory, error)
        CommunityDlss5Status(
            errorKey = "rwpp.dlss5.monitor.queryFailed",
            diagnostic = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(1200)}"
        )
    }
}

private val lastQueryFailure = AtomicReference<String?>(null)
private fun recordDlssQueryFailure(directory: String, error: Exception) {
    val detail = "directory=$directory\n${error.javaClass.name}: ${error.message.orEmpty().take(1800)}"
    if (lastQueryFailure.getAndSet(detail) == detail) return
    runCatching {
        val root = Paths.get(System.getProperty("rwpp.gameRoot") ?: System.getProperty("user.dir"))
        val log = root.resolve("data/io.github.rwpp/community-dlss5-status.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "${Instant.now()} gamePid=${ProcessHandle.current().pid()}\n$detail\n")
    }
}

/** WM_CLOSE through the process lifecycle API lets the controller detach its bridges itself. */
internal actual fun closeCommunityDlss5(controllerDirectory: String): String = try {
    val executable = controllerExecutable(controllerDirectory)
    val helper = ensureDlssControlHelper()
    val targets = findCommunityDlss5Processes(controllerDirectory)
    if (targets.isEmpty()) readI18n("rwpp.dlss5.monitor.stopped")
    else {
        val script = """
            ${'$'}p = Get-Process -Id ([int]${'$'}env:RWPP_DLSS_PID) -ErrorAction SilentlyContinue
            if (${'$'}null -eq ${'$'}p) { exit 0 }
            if (-not [string]::Equals(${'$'}p.Path, ${'$'}env:RWPP_DLSS_CONTROLLER_EXE, [StringComparison]::OrdinalIgnoreCase) -or
                ${'$'}p.StartTime.ToFileTimeUtc().ToString() -ne ${'$'}env:RWPP_DLSS_STARTED) { throw 'Process identity changed' }
            ${'$'}deadline = [DateTime]::UtcNow.AddSeconds(4)
            do {
                ${'$'}p.Refresh()
                if (${'$'}p.HasExited) { exit 0 }
                if (${'$'}p.MainWindowHandle -ne 0) { break }
                Start-Sleep -Milliseconds 100
            } while ([DateTime]::UtcNow -lt ${'$'}deadline)
            Add-Type -Path ${'$'}env:RWPP_DLSS_HELPER
            [RwppDlssControl]::RequestControllerClose(${'$'}p.Id, [long]${'$'}env:RWPP_DLSS_STARTED, ${'$'}env:RWPP_DLSS_CONTROLLER_EXE)
            if (-not ${'$'}p.WaitForExit(4000)) { throw 'Controller is still detaching' }
        """.trimIndent()
        targets.forEach { process ->
            windowsControllerCommand(
                executable, script, mapOf(
                    "RWPP_DLSS_PID" to process.pid.toString(),
                    "RWPP_DLSS_STARTED" to process.startedAt.toString(), "RWPP_DLSS_HELPER" to helper.toString()
                ), timeoutSeconds = 12
            )
        }
        if (findCommunityDlss5Processes(controllerDirectory).isEmpty()) readI18n("rwpp.dlss5.monitor.closed")
        else readI18n("rwpp.dlss5.monitor.closeFailed")
    }
} catch (_: Exception) {
    readI18n("rwpp.dlss5.monitor.closeFailed")
}
