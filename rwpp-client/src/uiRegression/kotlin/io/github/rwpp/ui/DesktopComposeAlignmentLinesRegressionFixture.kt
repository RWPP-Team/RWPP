@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/** Native baseline/slot-reuse scenario, executed against the unmodified Compose dependency. */
internal object DesktopComposeAlignmentLinesRegressionFixture {
    private var listState: LazyListState? = null

    @Volatile
    var scrolledThroughItems: Boolean = false
        private set

    fun reset() {
        listState = null
        scrolledThroughItems = false
    }

    fun createRecomposer(context: CoroutineContext): FrameRecomposer = FrameRecomposer(context)

    fun createScene(frameRecomposer: FrameRecomposer, width: Int, height: Int): ComposeScene =
        CanvasLayersComposeScene(
            frameRecomposer = frameRecomposer,
            density = Density(1f),
            size = IntSize(width, height),
        )

    fun content(): @Composable () -> Unit = { AlignmentLineScrollScenario() }

    internal fun attachListState(state: LazyListState) {
        listState = state
    }

    fun scrollOneItem() = runBlocking {
        val state = checkNotNull(listState)
        check(state.layoutInfo.visibleItemsInfo.isNotEmpty()) { "initial layout must finish before scrolling" }
        state.scrollBy(20f)
        scrolledThroughItems = state.firstVisibleItemIndex >= 3
    }
}

@Composable
private fun AlignmentLineScrollScenario() {
    val state = rememberLazyListState()
    DesktopComposeAlignmentLinesRegressionFixture.attachListState(state)

    LazyColumn(state = state, modifier = Modifier.height(40.dp)) {
        items(10) { index ->
            BaselineQueryingLayout(index)
        }
    }
}

@Composable
private fun BaselineQueryingLayout(index: Int) {
    Layout(
        content = {
            Box {
                Box(Modifier.fillMaxWidth()) {
                    Spacer(
                        Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(10 + index, 20, mapOf(FirstBaseline to index)) {
                                placeable.placeRelative(0, 0)
                            }
                        },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        val firstBaseline = placeable[FirstBaseline]
        val lastBaseline = placeable[LastBaseline]
        layout(
            width = placeable.width,
            height = placeable.height,
            alignmentLines = mapOf(FirstBaseline to firstBaseline, LastBaseline to lastBaseline),
        ) {
            placeable.placeRelative(0, 0)
        }
    }
}
