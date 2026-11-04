@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.rwpp.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Run the upstream alignment/reuse scenario against the actual, unmodified dependency. */
class DesktopComposeNativeAlignmentRegressionTest : DesktopComposeGraphicsTest() {
    @Test
    fun nativeComposeHandlesBaselineQueriesDuringLazyItemReuse() {
        val dispatcher = RenderThreadDispatcher()
        val fixture = DesktopComposeAlignmentLinesRegressionFixture
        fixture.reset()
        val recomposer = fixture.createRecomposer(dispatcher)
        val scene = fixture.createScene(recomposer, 180, 40)
        try {
            scene.setContent(content = fixture.content())
            var frame = 0L
            fun renderFrame() {
                dispatcher.drain()
                frame += 16_666_667L
                recomposer.performFrame(frame)
                dispatcher.drain()
                scene.measureAndLayout()
            }
            repeat(4) { renderFrame() }
            repeat(3) {
                fixture.scrollOneItem()
                repeat(2) { renderFrame() }
            }
            assertTrue(fixture.scrolledThroughItems)
        } finally {
            scene.close()
            recomposer.close()
        }
    }

    @Test
    fun projectDoesNotMixinComposeLibraries() {
        val forbiddenTarget = Regex("@AsmMixin\\s*\\(\\s*\"(?:androidx[/.]compose|org[/.]jetbrains[/.]compose)")
        val root = Path.of("src")
        val offenders = Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .filter { forbiddenTarget.containsMatchIn(Files.readString(it)) }
                .toList()
        }
        assertTrue(offenders.isEmpty(), "Compose library mixins are forbidden: $offenders")
    }
}
