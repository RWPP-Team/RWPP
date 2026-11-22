package io.github.rwpp.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.rwpp.widget.v2.AeroCyberOverlay
import io.github.rwpp.widget.v2.skiaGlitchEffect

/** Changes on navigation and when a different Aero color scheme has been selected. */
data class SceneTransitionContext(val screen: Screen, val themeGeneration: Int)

fun interface SceneTransitionEffect {
    /** Render the current screen's content once, with optional decoration or a drawing modifier. */
    @Composable
    fun Render(context: SceneTransitionContext, content: @Composable () -> Unit)
}

/** Client-wide default, shared by desktop and Android. Assign the Aero effect to restore it. */
object SceneTransitions {
    var effect: SceneTransitionEffect by mutableStateOf(
        AeroSceneTransition, referentialEqualityPolicy()
    )
}

/** An editable ComposeScopeTree call site. The navigator supplies every argument explicitly. */
@Composable
fun SceneTransition(
    context: SceneTransitionContext,
    enabled: Boolean,
    effect: SceneTransitionEffect,
    content: @Composable () -> Unit,
) {
    val currentContent = rememberUpdatedState(content)
    val screenContent = remember { movableContentOf { currentContent.value() } }
    if (enabled) effect.Render(context, screenContent) else screenContent()
}

object AeroSceneTransition : SceneTransitionEffect {
    @Composable
    override fun Render(context: SceneTransitionContext, content: @Composable () -> Unit) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(context) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(140, easing = LinearEasing))
            progress.animateTo(0f, tween(140, easing = LinearEasing))
        }
        val amount = progress.value
        val time = amount * 0.28f
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().skiaGlitchEffect(amount, time)) { content() }
            AeroCyberOverlay(amount, time, Modifier.fillMaxSize())
        }
    }
}
