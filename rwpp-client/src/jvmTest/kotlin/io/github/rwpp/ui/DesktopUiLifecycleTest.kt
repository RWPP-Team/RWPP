/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwml.api.game.GameBoot
import io.github.rwml.api.game.GameContext
import io.github.rwpp.ui.UiSurface
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopUiLifecycleTest {
    @Test
    fun closeDeclineLeavesHostSurfaceOpen() {
        val surface = TestSurface()
        var confirmations = 0
        val close = SurfaceCloseController(surface) {
            confirmations++
            false
        }

        close.requestClose()
        close.requestClose()

        assertEquals(0, surface.closeCount)
        assertEquals(2, confirmations)
    }

    @Test
    fun confirmedCloseDelegatesExactlyOnceToHostSurface() {
        val surface = TestSurface()
        var confirmations = 0
        val close = SurfaceCloseController(surface) {
            confirmations++
            true
        }

        close.requestClose()
        close.requestClose()

        assertEquals(1, surface.closeCount)
        assertEquals(1, confirmations)
    }

    @Test
    fun engineStageReplacesVanillaAndGameBootIsOneShot() {
        val boot = GameBoot(TestGameContext)
        var vanillaStarts = 0
        var rwppStarts = 0
        boot.add(GameBoot.ENGINE) { vanillaStarts++ }
        installRwppEngineStage(boot) { rwppStarts++ }

        val firstReport = boot.start()
        val secondReport = boot.start()

        assertTrue(firstReport.isClean)
        assertSame(firstReport, secondReport)
        assertEquals(0, vanillaStarts)
        assertEquals(1, rwppStarts)
    }

    @Test
    fun engineStageFailureIsReportedAndDoesNotCountAsSuccess() {
        val boot = GameBoot(TestGameContext)
        boot.add(GameBoot.ENGINE) { error("vanilla engine stage must be replaced") }
        installRwppEngineStage(boot) { error("embedded engine failed") }

        val report = boot.start()

        assertFalse(report.isClean)
        assertFalse(report.contains(GameBoot.ENGINE))
        assertTrue(report.failed().containsKey(GameBoot.ENGINE))
    }

    private object TestGameContext : GameContext {
        override fun platform(): Any = this
        override fun arguments(): List<String> = emptyList()
        override fun directory(): Path = Path.of(".")
        override fun log(message: String) = Unit
    }

    private class TestSurface : UiSurface {
        var closeCount = 0
            private set

        override fun uiExecutor(): Executor = Executor(Runnable::run)
        override fun attach(nativeContent: Any?) = Unit
        override fun onClose(listener: Runnable): AutoCloseable = AutoCloseable {}
        override fun isClosed(): Boolean = closeCount > 0
        override fun close() {
            closeCount++
        }
    }
}