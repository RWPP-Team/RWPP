package io.github.rwpp.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CommunityDlss5LaunchSupportTest {
    @Test
    fun acceptsOnlyVerifiedUpstreamOpenGlControllerReleases() {
        assertTrue(CommunityDlss5LaunchSupport.supportsOpenGl("0.4.3.0"))
        assertTrue(CommunityDlss5LaunchSupport.supportsOpenGl("0.5.1.0"))
        assertFalse(CommunityDlss5LaunchSupport.supportsOpenGl("0.4.2.0"))
        assertFalse(CommunityDlss5LaunchSupport.supportsOpenGl("0.4.4.0"))
        assertFalse(CommunityDlss5LaunchSupport.supportsOpenGl("not a version"))
    }

    @Test
    fun requiresFixedControllerAndBridgeFilesInOneFolder() = withTempDirectory { root ->
        listOf(
            "UniversalDLSS5.exe",
            "UniversalDLSS5.Injector.exe",
            "UniversalDLSS5.Bridge.dll",
            "UniversalDLSS5.NRHost.exe",
        ).forEach { Files.createFile(root.resolve(it)) }

        val layout = CommunityDlss5LaunchSupport.inspectController(root.toString(), "0.5.1.0")
        assertEquals(root.toRealPath(), layout.directory)
        assertEquals(root.resolve("UniversalDLSS5.exe"), layout.executable)

        Files.delete(root.resolve("UniversalDLSS5.Bridge.dll"))
        assertFailsWith<IllegalArgumentException> {
            CommunityDlss5LaunchSupport.inspectController(root.toString(), "0.5.1.0")
        }
    }

    @Test
    fun recognizesOnlyJavaLauncherProcesses() {
        assertTrue(CommunityDlss5LaunchSupport.isJavaExecutable("C:\\Runtime\\bin\\javaw.exe"))
        assertTrue(CommunityDlss5LaunchSupport.isJavaExecutable("/runtime/bin/java.exe"))
        assertFalse(CommunityDlss5LaunchSupport.isJavaExecutable("C:\\Tools\\powershell.exe"))
    }

    @Test
    fun identifiesTheRunningJavaPidAndExistingGameRoot() = withTempDirectory { root ->
        val fakeJava = Files.createFile(root.resolve("javaw.exe"))
        val target = CommunityDlss5LaunchSupport.detectCurrentTarget(
            pid = 4242L,
            executable = fakeJava.toString(),
            gameRoot = root.toString(),
            dataModel = "64",
        )

        assertEquals(4242L, target.pid)
        assertEquals(fakeJava.toRealPath(), target.javaExecutable)
        assertEquals(root.toRealPath(), target.gameRoot)
    }

    @Test
    fun rejects32BitAndMissingGameRoots() = withTempDirectory { root ->
        val fakeJava = Files.createFile(root.resolve("java.exe"))
        assertFailsWith<IllegalArgumentException> {
            CommunityDlss5LaunchSupport.detectCurrentTarget(4242L, fakeJava.toString(), root.toString(), "32")
        }
        assertFailsWith<IllegalArgumentException> {
            CommunityDlss5LaunchSupport.detectCurrentTarget(
                4242L,
                fakeJava.toString(),
                root.resolve("missing").toString(),
                "64",
            )
        }
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("rwpp-community-dlss5-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
