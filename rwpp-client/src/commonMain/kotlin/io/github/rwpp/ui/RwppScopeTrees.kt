package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.composescopetree.ImplicitScopeTreeHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Mounted RWPP UI surfaces, including the separate Android menu and game surfaces. */
object RwppScopeTrees {
    private val mounted = MutableStateFlow<List<ImplicitScopeTreeHost>>(emptyList())
    val hosts = mounted.asStateFlow()

    @Composable
    internal fun Content(content: @Composable () -> Unit) {
        val host = remember {
            ImplicitScopeTreeHost { _, function ->
                function.startsWith("io.github.rwpp.") ||
                    function in editableLibraryCalls
            }
        }
        DisposableEffect(host) {
            mounted.update { it + host }
            onDispose { mounted.update { it - host } }
        }
        host.Content(content)
    }

    private val editableLibraryCalls = setOf(
        "androidx.compose.foundation.layout.Box",
        "androidx.compose.foundation.layout.Column",
        "androidx.compose.foundation.layout.Row",
        "androidx.compose.foundation.lazy.LazyColumn",
        "androidx.compose.foundation.lazy.LazyRow",
        "androidx.compose.material3.Text",
    )
}
