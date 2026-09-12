/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformTest {
    @BeforeTest
    fun initLogger() {
        runCatching { io.github.rwpp.logger }.onFailure {
            io.github.rwpp.logger = LoggerFactory.getLogger("rwpp-test")
        }
    }

    @Test
    fun resolveSkikoRenderApi_forcesSoftwareOnMac() {
        if (!Platform.isMacOS) return
        assertTrue(Platform.resolveSkikoRenderApi("OpenGL").equals("Software", ignoreCase = true))
        assertTrue(Platform.resolveSkikoRenderApi("Default").equals("Software", ignoreCase = true))
        assertTrue(Platform.resolveSkikoRenderApi("Metal").equals("Software", ignoreCase = true))
        assertTrue(Platform.resolveSkikoRenderApi("Software").equals("Software", ignoreCase = true))
    }

    @Test
    fun validateGameNatives_reportsMissingDylibsOnMac() {
        if (!Platform.isMacOS) {
            assertNull(Platform.validateGameNatives(File(".")))
            return
        }
        val dir = createTempDir("rwpp-natives-missing")
        try {
            val message = Platform.validateGameNatives(dir)
            assertNotNull(message)
            assertTrue(message.contains("liblwjgl"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun validateGameNatives_okWhenRequiredLibsPresentOnMac() {
        if (!Platform.isMacOS) return
        val dir = createTempDir("rwpp-natives-ok")
        try {
            File(dir, "liblwjgl.dylib").writeText("")
            File(dir, "openal.dylib").writeText("")
            File(dir, "libjinput-osx.jnilib").writeText("")
            assertNull(Platform.validateGameNatives(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolveRestartCommand_prefersPlatformBinary() {
        val dir = createTempDir("rwpp-restart")
        try {
            val binary = when {
                Platform.isWindows -> File(dir, "RWPP.exe")
                Platform.isMacOS -> File(dir, "RWPP.app/Contents/MacOS/RWPP").also { it.parentFile.mkdirs() }
                else -> File(dir, "bin/RWPP").also { it.parentFile.mkdirs() }
            }
            binary.writeText("#!/bin/sh\n")
            binary.setExecutable(true)
            val cmd = Platform.resolveRestartCommand(dir)
            assertTrue(cmd.first().contains("RWPP"), cmd.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").also { it.mkdirs() }
}
