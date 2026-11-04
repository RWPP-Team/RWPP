package io.github.rwpp.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunityDlss5MonitorTest {
    @Test
    fun processEvidenceRequiresBothPositivePidAndStartIdentity() {
        val rows = "PROCESS\t4312\t133000000001\r\nPROCESS\t0\t1\nPROCESS\t34\t0\n" +
                "PROCESS\tbad\t1\nPROCESS\t4312\t133000000001\nRUNTIME\nPROCESS\t99"
        assertEquals(listOf(DlssControllerProcess(4312, 133000000001)), parseDlssControllerProcesses(rows))
    }

    @Test
    fun staleLaunchMessagesAreNotProcessEvidence() {
        assertTrue(parseDlssControllerProcesses("Controller started. PID 123\n").isEmpty())
        assertTrue(parseDlssControllerProcesses("").isEmpty())
    }

    @Test
    fun managedAppDataPathMigratesToPortableFilesWithoutOverridingCustomDirectories() {
        val root = Files.createTempDirectory("rwpp-portable-controller-")
        try {
            val portable = root.resolve("tools/UniversalDLSS5")
            Files.createDirectories(portable)
            Files.createFile(portable.resolve("UniversalDLSS5.exe"))
            assertEquals(
                portable.toString(), resolvePortableDlssDirectory(
                    "C:/Users/player/AppData/Local/UniversalDLSS5/app-0.5.1", root
                )
            )
            assertEquals(
                portable.toString(), resolvePortableDlssDirectory(
                    "C:/Users/player/AppData/Local/Packages/OpenAI.Codex/LocalCache/Local/UniversalDLSS5/app-0.5.1",
                    root
                )
            )
            assertEquals(portable.toString(), resolvePortableDlssDirectory("", root))
            assertEquals("D:/Custom/Controller", resolvePortableDlssDirectory("D:/Custom/Controller", root))
            Files.delete(portable.resolve("UniversalDLSS5.exe"))
            assertEquals(
                "C:/Users/player/AppData/Local/UniversalDLSS5/app-0.5.1", resolvePortableDlssDirectory(
                    "C:/Users/player/AppData/Local/UniversalDLSS5/app-0.5.1", root
                )
            )
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
