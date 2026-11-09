/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.rwpp.widget.v2.AeroCyberOverlay
import io.github.rwpp.widget.v2.skiaGlitchEffect
import org.koin.compose.currentKoinScope
import org.koin.core.component.KoinComponent
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.Qualifier
import org.koin.core.scope.Scope

/**
 * Represents a navigable screen scene within the application.
 * Implementations declare constructor arguments directly for state injection.
 */
interface Screen : KoinComponent {
    /**
     * Composable entry point rendering the UI layout for this screen scene.
     */
    @Composable
    fun Content()
}

/**
 * CompositionLocal storing local screen overrides or mock instances within a Composition sub-tree.
 */
val LocalScreenOverrides = compositionLocalOf<Map<String, Any>> { emptyMap() }

/**
 * CompositionLocal exposes the Navigator of the current level and supports direct consumption by the UI layer.
 */
val LocalScreenNavigator = staticCompositionLocalOf<ScreenNavigator> {
    error("No ScreenNavigator provided! Make sure to render within ScreenNavigator.Content()")
}

/**
 * Remembers and resolves a [Screen] instance using current Koin scope or CompositionLocal overrides.
 *
 * @param S The target [Screen] class unitType.
 * @param params Constructor arguments forwarded to Koin or factory overrides.
 * @param qualifier Optional Koin qualifier for named DI registrations.
 */
@Composable
inline fun <reified S : Screen> rememberScreen(
    vararg params: Any?,
    qualifier: Qualifier? = null
): S {
    val scope = currentKoinScope()
    val localOverrides = LocalScreenOverrides.current
    val screenKey = qualifier?.value ?: S::class.qualifiedName.orEmpty()

    return remember(screenKey, *params) {
        when (val local = localOverrides[screenKey]) {
            is Screen -> local as? S ?: error("Invalid screen override instance for key: $screenKey")
            is Function1<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (local as (Array<out Any?>) -> S).invoke(params)
            }

            else -> {
                scope.get<S>(qualifier) {
                    if (params.isNotEmpty()) parametersOf(*params) else parametersOf()
                }
            }
        }
    }
}

/**
 * Backstack navigator managing and rendering [Screen] scenes resolved via Koin.
 *
 * @param initialScreen The root screen initialized in the backstack.
 * @param koinScope Active Koin scope used to instantiate screens dynamically.
 */
@Stable
class ScreenNavigator(
    initialScreen: Screen,
    val koinScope: Scope
) {
    /**
     * Current active backstack of screens.
     */
    val backStack = mutableStateListOf(initialScreen)

    /**
     * Active top screen in the backstack.
     */
    val current: Screen get() = backStack.last()

    /**
     * Indicates whether backstack navigation can pop to a previous screen.
     */
    val canPop: Boolean get() = backStack.size > 1

    /**
     * Pushes an already instantiated [screen] onto the backstack.
     */
    fun push(screen: Screen) {
        backStack.add(screen)
    }

    /**
     * Resolves [S] from Koin using [params] and pushes it onto the backstack.
     *
     * Example:
     * ```
     * navigator.push<DetailScreen>("item_99")
     * ```
     */
    inline fun <reified S : Screen> push(
        vararg params: Any?,
        qualifier: Qualifier? = null
    ) {
        val screen = koinScope.get<S>(qualifier) {
            if (params.isNotEmpty()) parametersOf(*params) else parametersOf()
        }
        push(screen)
    }

    /**
     * Pops the top screen off the backstack.
     *
     * @return `true` if popped successfully; `false` if only the root screen remains.
     */
    fun pop(): Boolean {
        if (canPop) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        return false
    }

    /**
     * Replaces the current top screen with an instantiated [screen].
     */
    fun replaceScreen(screen: Screen) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = screen
        } else {
            backStack.add(screen)
        }
    }

    /**
     * Resolves [S] from Koin using [params] and replaces the current top screen.
     */
    inline fun <reified S : Screen> replace(
        vararg params: Any?,
        qualifier: Qualifier? = null
    ) {
        val screen = koinScope.get<S>(qualifier) {
            if (params.isNotEmpty()) parametersOf(*params) else parametersOf()
        }
        replaceScreen(screen)
    }

    /**
     * Clears the entire backstack and replaces it with [screen].
     */
    fun replaceAll(screen: Screen) {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            backStack.clear()
            backStack.add(screen)
        }
    }

    /** A game-only Android window still needs a root when gameplay returns to the menu. */
    fun removeFrom(index: Int, fallback: Screen) {
        if (index !in backStack.indices) return
        if (index == 0) replaceAll(fallback)
        else backStack.removeRange(index, backStack.size)
    }

    /**
     * Renders the active top screen content, respecting CompositionLocal overrides if present.
     */
    @Composable
    fun Content() {
        CompositionLocalProvider(LocalScreenNavigator provides this) {
            val localOverrides = LocalScreenOverrides.current
            val currentScreen = current

            val glitchAnimatable = remember { Animatable(0f) }
            val effects = org.koin.compose.koinInject<io.github.rwpp.config.Settings>().enableAnimations
            // The counter advances when the user picks another color scheme, so the flash also
            // covers theme changes that keep the current screen.
            val themeGeneration = io.github.rwpp.ui.AeroThemeTransition.generation

            LaunchedEffect(currentScreen, themeGeneration, effects) {
                if (!effects) {
                    glitchAnimatable.snapTo(0f)
                    return@LaunchedEffect
                }
                glitchAnimatable.snapTo(0f)
                glitchAnimatable.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 140, easing = LinearEasing)
                )
                glitchAnimatable.animateTo(
                    targetValue = 0.0f,
                    animationSpec = tween(durationMillis = 140, easing = LinearEasing)
                )
            }

            val glitchProgress = glitchAnimatable.value

            // Keep route changes synchronous. Retaining singleton room screens for an exit
            // animation leaves their dialogs and listeners attached while the game tears down.
            key(currentScreen) {
                val glitchTime = glitchProgress * 0.28f
                Box(modifier = Modifier.fillMaxSize()) {
                    val screenKey = currentScreen::class.qualifiedName.orEmpty()
                    val override = localOverrides[screenKey] as? Screen
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .skiaGlitchEffect(
                                progress = glitchProgress,
                                time = glitchTime
                            )
                    ) {
                        (override ?: currentScreen).Content()
                    }

                    AeroCyberOverlay(
                        progress = glitchProgress,
                        time = glitchTime,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Remembers a stateful [ScreenNavigator] with a Koin-resolved initial [Screen].
 *
 * @param S The initial [Screen] class unitType.
 * @param params Constructor arguments for resolving the initial screen.
 * @param qualifier Optional Koin qualifier for named DI registrations.
 */
@Composable
inline fun <reified S : Screen> rememberScreenNavigator(
    vararg params: Any?,
    qualifier: Qualifier? = null
): ScreenNavigator {
    val scope = currentKoinScope()
    val initialScreen = rememberScreen<S>(*params, qualifier = qualifier)
    return remember(initialScreen) { ScreenNavigator(initialScreen, scope) }
}

/**
 * Overrides screen registrations locally within the provided [content] sub-tree.
 * Useful for UI testing and Compose previews.
 *
 * @param overrides Key-value pairs mapping qualifier strings or class names to [Screen] instances.
 * @param content Composable sub-tree receiving the local overrides.
 */
@Composable
fun OverrideScreenLocally(
    vararg overrides: Pair<String, Any>,
    content: @Composable () -> Unit
) {
    val currentMap = LocalScreenOverrides.current.toMutableMap()
    overrides.forEach { (key, screen) -> currentMap[key] = screen }
    CompositionLocalProvider(LocalScreenOverrides provides currentMap) {
        content()
    }
}
