/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.logger
import java.io.File
import javax.swing.JOptionPane
import kotlin.system.exitProcess

object Platform {
    val osName: String = System.getProperty("os.name").orEmpty()
    val osArch: String = System.getProperty("os.arch").orEmpty()

    val isWindows: Boolean
        get() = osName.contains("win", ignoreCase = true)

    val isMacOS: Boolean
        get() = osName.contains("mac", ignoreCase = true)

    val isLinux: Boolean
        get() = !isWindows && !isMacOS &&
            (osName.contains("nux", ignoreCase = true) || osName.contains("nix", ignoreCase = true))

    val isAppleSilicon: Boolean
        get() {
            val arch = osArch.lowercase()
            return arch == "aarch64" || arch == "arm64"
        }

    /**
     * Resolve Skiko render API for the Compose UI layer.
     *
     * On macOS, force Software: Metal/OpenGL Skiko sharing an NSWindow with LWJGL2
     * OpenGL produces a permanent black Compose surface. Game GL stays LWJGL2.
     */
    fun resolveSkikoRenderApi(renderingBackend: String): String? {
        if (isMacOS) {
            if (!renderingBackend.equals("Software", ignoreCase = true)) {
                logger.info(
                    "macOS detected: using Skiko Software for Compose UI " +
                        "(Metal/OpenGL Skiko conflicts with LWJGL2 OpenGL in the same window). " +
                        "Game rendering still uses LWJGL2 OpenGL."
                )
            }
            return "Software"
        }
        return renderingBackend.takeUnless { it.equals("Default", ignoreCase = true) }
    }

    /**
     * Restart the current native / packaged process after lib injection.
     */
    fun restartCurrentProcess() {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val command = resolveRestartCommand(userDir)
        logger.info("Restarting RWPP via: ${command.joinToString(" ")}")
        ProcessBuilder(command)
            .directory(userDir)
            .inheritIO()
            .start()
        exitProcess(0)
    }

    fun resolveRestartCommand(userDir: File = File(System.getProperty("user.dir") ?: ".")): List<String> {
        val candidates = when {
            isWindows -> listOf(
                File(userDir, "RWPP.exe"),
                File(userDir, "bin/RWPP.exe"),
            )
            isMacOS -> listOf(
                File(userDir, "RWPP.app/Contents/MacOS/RWPP"),
                File(userDir, "bin/RWPP"),
                File(userDir, "RWPP"),
            )
            else -> listOf(
                File(userDir, "bin/RWPP"),
                File(userDir, "RWPP"),
            )
        }

        candidates.firstOrNull { it.exists() }?.let { return listOf(it.absolutePath) }

        val handle = ProcessHandle.current()
        val command = handle.info().command().orElse(null)
            ?: error(
                "Cannot find RWPP executable to restart. " +
                    "Looked for ${candidates.joinToString { it.name }} under ${userDir.absolutePath}"
            )
        val args = handle.info().arguments().orElse(emptyArray())
        return listOf(command) + args.toList()
    }

    /**
     * Validate LWJGL2 / OpenAL / JInput natives expected in the Rusted Warfare install directory.
     * Returns an error message if required libraries are missing on macOS; null otherwise.
     * Apple Silicon is logged separately via [warnAppleSiliconIfNeeded] (non-blocking).
     */
    fun validateGameNatives(workingDir: File = File(System.getProperty("user.dir") ?: ".")): String? {
        if (!isMacOS) return null

        val lwjgl = findAny(workingDir, "liblwjgl.dylib", "liblwjgl.jnilib")
        val openal = findAny(workingDir, "openal.dylib", "libopenal.dylib", "libopenal.jnilib")
        val jinput = findAny(
            workingDir,
            "libjinput-osx.dylib",
            "libjinput-osx.jnilib",
            "libjinput.dylib",
            "libjinput.jnilib",
        )

        val missing = buildList {
            if (lwjgl == null) add("liblwjgl.dylib (or .jnilib)")
            if (openal == null) add("openal.dylib / libopenal.dylib")
            if (jinput == null) add("libjinput-osx.dylib (or .jnilib)")
        }

        if (missing.isEmpty()) return null

        return "Missing macOS game natives in ${workingDir.absolutePath}: ${missing.joinToString(", ")}. " +
            "Install the Steam macOS build of Rusted Warfare and run RWPP from that game root " +
            "so java.library.path can load the official dylibs." +
            if (isAppleSilicon) {
                "\n\nAlso: Apple Silicon JVM detected. Official natives are x86_64 — " +
                    "prefer an x86_64 JDK 17 under Rosetta (e.g. `arch -x86_64 .../java`)."
            } else {
                ""
            }
    }

    fun warnAppleSiliconIfNeeded() {
        if (isMacOS && isAppleSilicon) {
            val dir = File(System.getProperty("user.dir") ?: ".")
            val lwjgl = File(dir, "liblwjgl.dylib")
            // Best-effort: if stock Intel-only natives were replaced with universal/arm64, skip the scary warning.
            val hasArmSlice = runCatching {
                val proc = ProcessBuilder("file", lwjgl.absolutePath).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                out.contains("arm64")
            }.getOrDefault(false)
            if (hasArmSlice) {
                logger.info(
                    "Apple Silicon JVM with arm64-capable liblwjgl.dylib detected. " +
                        "Community natives are experimental; restore natives-x86_64-backup/ if the official game breaks."
                )
            } else {
                logger.warn(
                    "Apple Silicon (arm64) JVM detected. Official Rusted Warfare / LWJGL2 natives are x86_64. " +
                        "Run RWPP with an x86_64 JDK 17 under Rosetta for best compatibility, " +
                        "or supply community arm64 LWJGL2/JInput/OpenAL dylibs."
                )
            }
        }
    }

    /**
     * Show a blocking dialog for fatal native issues; returns true if the user chose to continue anyway.
     * In headless environments, logs the error and returns false.
     */
    fun warnIfNativesMissing(workingDir: File = File(System.getProperty("user.dir") ?: ".")): Boolean {
        val message = validateGameNatives(workingDir) ?: return true
        logger.error(message)
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return false
        }
        val options = arrayOf("Continue anyway", "Exit")
        val choice = JOptionPane.showOptionDialog(
            null,
            message,
            "RWPP macOS natives",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[1],
        )
        return choice == 0
    }

    private fun findAny(dir: File, vararg names: String): File? =
        names.map { File(dir, it) }.firstOrNull { it.isFile }
}
