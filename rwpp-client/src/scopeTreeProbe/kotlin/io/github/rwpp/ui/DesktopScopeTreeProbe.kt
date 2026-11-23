package io.github.rwpp.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.composescopetree.ImplicitScopeNode
import dev.composescopetree.ImplicitScopeTreeHost
import dev.composescopetree.ScopeAddress
import dev.composescopetree.ui.RowComponent
import dev.composescopetree.ui.RowDefinition
import io.github.rwpp.api.logging.logger
import io.github.rwpp.widget.v2.AeroPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun DesktopScopeTreeProbe(content: @Composable () -> Unit) {
    val enabled = remember { java.lang.Boolean.getBoolean("rwpp.scopeTreeProbe") }
    val clientGeneralDemo = remember { java.lang.Boolean.getBoolean("rwpp.scopeTreeClientGeneralDemo") }
    content()
    if (!enabled && !clientGeneralDemo) return
    LaunchedEffect(Unit) {
        val host = RwppScopeTrees.hosts.first { it.isNotEmpty() }.first()
        if (clientGeneralDemo) {
            runClientGeneralScopeTreeExample(host)
            return@LaunchedEffect
        }
        DesktopScopeTreeMetrics.awaitCompletion()
        val target = withTimeoutOrNull(20_000) {
            snapshotFlow {
                val nodes = host.roots.asSequence().flatMap(::descendants).toList()
                val operationButtons = nodes.any { it.function == "io.github.rwpp.ui.MainMenuScreen.OperationButtons" }
                val extensionButtons = nodes.any { it.function == "io.github.rwpp.ui.MainMenuScreen.ExtensionButtons" }
                if (operationButtons && extensionButtons) {
                    nodes.firstOrNull { ".MainMenu.kt:" in it.site && it.parameters(RowComponent) != null }
                } else null
            }.filterNotNull().first()
        }
        if (target == null) {
            logger().warn("ScopeTree probe timed out waiting for the MainMenu Row and both button groups")
            return@LaunchedEffect
        }

        val count = host.roots.asSequence().flatMap(::descendants).count()
        verifyMenuControls(host)
        val layoutSize = CompletableDeferred<IntSize>()
        host.replaceDefinition(target.address, RowDefinition) { parameters, children ->
            Row(
                modifier = parameters.modifier.onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) layoutSize.complete(size)
                },
                horizontalArrangement = parameters.horizontalArrangement,
                verticalAlignment = parameters.verticalAlignment,
                content = children,
            )
        }
        try {
            val size = withTimeoutOrNull(10_000) { layoutSize.await() }
            if (size == null) {
                logger().warn("ScopeTree probe found ${target.site}, but the replacement did not lay out")
            } else {
                logger().info(
                    "ScopeTree probe passed: discovered $count scopes including both MainMenu button groups, " +
                        "and laid out replacement ${target.site} at ${size.width}x${size.height}"
                )
            }
        } finally {
            if (host.roots.asSequence().flatMap(::descendants).any { it.address == target.address }) {
                host.resetDefinition(target.address)
                logger().info("ScopeTree probe restored ${target.site}")
            }
        }
        verifySettingsControls(host)
        logger().info("ScopeTree controls complete")
    }
}

internal fun descendants(node: ImplicitScopeNode): Sequence<ImplicitScopeNode> =
    sequenceOf(node) + node.children.asSequence().flatMap(::descendants)

private fun ImplicitScopeTreeHost.nodes(): Sequence<ImplicitScopeNode> =
    roots.asSequence().flatMap(::descendants)

private suspend fun verifyMenuControls(host: ImplicitScopeTreeHost) {
    val widgets = host.nodes()
        .filter { it.function?.startsWith("io.github.rwpp.widget.") == true }
        .mapNotNull { it.function }
        .groupingBy { it.substringAfterLast('.') }
        .eachCount()
    logger().info("ScopeTree controls mounted: ${widgets.toSortedMap()}")

    val menuButton = host.menuControl("AeroMenuButton") { "modifier" in (it.arguments?.names ?: emptyList()) }
    val buttonSize = CompletableDeferred<IntSize>()
    val newTitle = "SCOPE TREE BUTTON PROBE"
    host.overrideArguments(menuButton.address) { args ->
        args.with("title", newTitle).with("modifier", args.getAs<Modifier>("modifier").onSizeChanged {
            if (it.width > 0 && it.height > 0) buttonSize.complete(it)
        })
    }
    try {
        val size = requireNotNull(withTimeoutOrNull(10_000) {
            buttonSize.await().also { awaitText(host, menuButton.address, newTitle) }
        }) { "AeroMenuButton did not receive the edited title and modifier" }
        logger().info("ScopeTree control passed: AeroMenuButton title and modifier reached layout at $size")
    } finally {
        host.resetIfMounted(menuButton.address)
    }

    val icon = host.menuControl("AeroIconButton") { it.arguments?.names?.containsAll(listOf("size", "modifier")) == true }
    val iconSize = CompletableDeferred<IntSize>()
    host.overrideArguments(icon.address) { args ->
        args.with("size", 64.dp).with("modifier", args.getAs<Modifier>("modifier").onSizeChanged {
            if (it.width > 0 && it.height > 0) iconSize.complete(it)
        })
    }
    try {
        val size = requireNotNull(withTimeoutOrNull(10_000) { iconSize.await() }) {
            "AeroIconButton did not lay out after editing size"
        }
        logger().info("ScopeTree control passed: AeroIconButton accepted a Dp size and laid out at $size")
    } finally {
        host.resetIfMounted(icon.address)
    }

    val panel = host.menuControl("AeroPanel") { node ->
        descendants(node).any { it.function == "io.github.rwpp.ui.MainMenuScreen.OperationButtons" }
    }
    val panelSize = CompletableDeferred<IntSize>()
    host.replaceCallWithSlot(panel.address, "content") { arguments, content: @Composable BoxScope.() -> Unit ->
        AeroPanel(
            modifier = arguments.getAs<Modifier>("modifier").onSizeChanged {
                if (it.width > 0 && it.height > 0) panelSize.complete(it)
            },
            borderColor = arguments.getAs("borderColor"),
            content = content,
        )
    }
    try {
        val size = requireNotNull(withTimeoutOrNull(10_000) {
            panelSize.await().also {
                snapshotFlow {
                    host.nodes().firstOrNull { it.address == panel.address }?.let(::descendants)?.any { child ->
                        child.function == "io.github.rwpp.ui.MainMenuScreen.OperationButtons"
                    } == true
                }.first { it }
            }
        }) { "AeroPanel did not render its original BoxScope content after replacement" }
        logger().info("ScopeTree control passed: AeroPanel replacement kept its BoxScope content and laid out at $size")
    } finally {
        host.resetIfMounted(panel.address)
    }
}

private suspend fun verifySettingsControls(host: ImplicitScopeTreeHost) {
    val navigator = checkNotNull(UI.liveNavigator) { "RWPP navigator is unavailable" }
    navigator.push<SettingsScreen>("RWPP")
    val screen = navigator.current
    try {
        val (slider, toggle) = requireNotNull(withTimeoutOrNull(10_000) {
            snapshotFlow {
                val nodes = host.nodes().filter { ".Settings.kt:" in it.site }.toList()
                val slider = nodes.firstOrNull { it.function?.endsWith(".AeroSettingSlider") == true }
                val toggle = nodes.firstOrNull { it.function?.endsWith(".AeroSettingToggle") == true }
                if (slider != null && toggle != null) slider to toggle else null
            }.filterNotNull().first()
        }) { "Graphics settings did not mount the slider and toggle" }
        val sliderValue = if (slider.arguments!!.getAs<Float>("value") < 1.2f) 1.3f else 0.7f
        val toggleValue = !toggle.arguments!!.getAs<Boolean>("defaultValue")
        host.overrideArguments(slider.address) { it.with("value", sliderValue) }
        host.overrideArguments(toggle.address) { it.with("defaultValue", toggleValue) }
        val changed = withTimeoutOrNull(10_000) {
            snapshotFlow {
                host.hasControlArgument(slider.address, "AeroSlider", "value", sliderValue) &&
                    host.hasControlArgument(toggle.address, "AeroToggleTile", "checked", toggleValue)
            }.first { it }
        }
        check(changed == true) { "Settings controls did not receive the edited arguments" }
        logger().info("ScopeTree control passed: AeroSettingSlider and AeroSettingToggle propagated edited values")
        host.resetIfMounted(slider.address)
        host.resetIfMounted(toggle.address)
    } finally {
        if (navigator.current === screen) navigator.pop()
    }
}

private fun ImplicitScopeTreeHost.hasControlArgument(
    parent: ScopeAddress,
    function: String,
    parameter: String,
    value: Any,
): Boolean = nodes().firstOrNull { it.address == parent }?.let(::descendants)?.any {
    val arguments = it.arguments
    it.function?.endsWith(".$function") == true && arguments != null &&
        parameter in arguments.names && arguments[parameter] == value
} == true

private fun ImplicitScopeTreeHost.menuControl(name: String, predicate: (ImplicitScopeNode) -> Boolean): ImplicitScopeNode =
    nodes().first {
        ".MainMenu.kt:" in it.site && it.function?.endsWith(".$name") == true && predicate(it)
    }

private suspend fun awaitText(host: ImplicitScopeTreeHost, address: ScopeAddress, text: String) {
    snapshotFlow {
        host.nodes().firstOrNull { it.address == address }?.let(::descendants)?.any {
            it.function?.endsWith(".Text") == true && it.arguments?.let { args ->
                "text" in args.names && args["text"] == text
            } == true
        } == true
    }.first { it }
}

private fun ImplicitScopeTreeHost.resetIfMounted(address: ScopeAddress) {
    if (nodes().any { it.address == address }) resetNode(address)
}
