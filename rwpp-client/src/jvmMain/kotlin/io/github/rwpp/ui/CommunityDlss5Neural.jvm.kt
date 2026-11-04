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
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit

@Synchronized
internal fun ensureDlssControlHelper(): Path {
    val bytes =
        checkNotNull(CommunityDlss5Status::class.java.getResourceAsStream("/io/github/rwpp/ui/CommunityDlss5Control.cs")) {
            "CommunityDlss5Control.cs is missing from the client archive"
        }.use { it.readBytes() }
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
    val root = Paths.get(System.getProperty("rwpp.gameRoot") ?: System.getProperty("user.dir"), "data/io.github.rwpp")
    Files.createDirectories(root)
    val library = root.resolve("dlss-control-$hash.dll")
    if (Files.isRegularFile(library)) return library
    val source = root.resolve("dlss-control-$hash.cs")
    Files.write(source, bytes)
    val windows = System.getenv("SystemRoot") ?: error("Windows directory is unavailable")
    val compiler = Paths.get(windows, "Microsoft.NET/Framework64/v4.0.30319/csc.exe")
    check(Files.isRegularFile(compiler)) { "The Windows .NET compiler is unavailable: $compiler" }
    val process = ProcessBuilder(
        compiler.toString(), "/nologo", "/unsafe", "/target:library", "/reference:System.Core.dll",
        "/out:$library", source.toString()
    ).redirectErrorStream(true).start()
    try {
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Controller IPC adapter compilation timed out" }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0 && Files.isRegularFile(library)) { "Controller IPC adapter: ${output.take(1000)}" }
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
    return library
}

internal fun parseCommunityDlss5NeuralStatus(output: String): CommunityDlss5NeuralStatus? =
    output.lineSequence().mapNotNull { line ->
        val f = line.trimEnd('\r').split('\t')
        if (f.size != 9 || f[0] != "NEURAL") return@mapNotNull null
        val state = f[1].toIntOrNull()?.takeIf { it in 0..6 } ?: return@mapNotNull null
        val api = f[2].toIntOrNull() ?: return@mapNotNull null
        val frames = f[4].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val processed = f[5].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val gpu = f[6].toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return@mapNotNull null
        val stage = f[7].toIntOrNull() ?: return@mapNotNull null
        val message = runCatching { String(Base64.getDecoder().decode(f[8]), Charsets.UTF_8) }.getOrNull()
            ?: return@mapNotNull null
        CommunityDlss5NeuralStatus(
            state,
            api,
            f[3] == "1" && state == 3 && frames > 0 && stage < 100,
            frames,
            processed,
            gpu,
            stage,
            message
        )
    }.firstOrNull()

internal actual fun enableCommunityDlss5(controllerDirectory: String): String = try {
    var status = queryCommunityDlss5Status(controllerDirectory)
    if (status.errorKey != null) readI18n(status.errorKey, *status.errorArguments.toTypedArray())
    else if (!status.runtimePresent) readI18n("rwpp.dlss5.monitor.missingRuntime")
    else {
        if (status.controllerPids.isEmpty()) {
            launchCommunityDlss5(controllerDirectory)
            repeat(10) {
                if (status.controllerPids.isEmpty()) {
                    Thread.sleep(250)
                    status = queryCommunityDlss5Status(controllerDirectory)
                }
            }
        }
        check(status.controllerPids.isNotEmpty()) { "Controller did not start" }
        val executable = Paths.get(status.executable)
        val helper = ensureDlssControlHelper()
        val script = """
            Add-Type -Path ${'$'}env:RWPP_DLSS_HELPER
            ${'$'}target = [int]${'$'}env:RWPP_DLSS_GAME_PID
            ${'$'}root = Split-Path ${'$'}env:RWPP_DLSS_CONTROLLER_EXE
            ${'$'}runtime = Join-Path ${'$'}root 'runtime'
            ${'$'}controller = Get-Process -Name UniversalDLSS5 | Where-Object { ${'$'}_.Path -eq ${'$'}env:RWPP_DLSS_CONTROLLER_EXE } | Select-Object -First 1
            ${'$'}deadline = [DateTime]::UtcNow.AddSeconds(5)
            do { ${'$'}controller.Refresh(); if (${'$'}controller.MainWindowHandle -ne 0) { break }; Start-Sleep -Milliseconds 100 } while ([DateTime]::UtcNow -lt ${'$'}deadline)
            [RwppDlssControl]::Configure(${'$'}target, ${'$'}runtime)
            ${'$'}bridge = Join-Path ${'$'}root 'UniversalDLSS5.Bridge.dll'
            if (-not [RwppDlssControl]::IsBridgeLoaded(${'$'}target, ${'$'}bridge)) {
                & (Join-Path ${'$'}root 'UniversalDLSS5.Injector.exe') ${'$'}target ${'$'}bridge
                if (${'$'}LASTEXITCODE -ne 0) { throw "Injector failed: ${'$'}LASTEXITCODE" }
            }
        """.trimIndent()
        windowsControllerCommand(
            executable,
            script,
            mapOf("RWPP_DLSS_HELPER" to helper.toString()),
            timeoutSeconds = 25
        )
        readI18n("rwpp.dlss5.neural.requested")
    }
} catch (error: Exception) {
    readI18n("rwpp.dlss5.neural.enableFailed", error.message.orEmpty().take(1000))
}
