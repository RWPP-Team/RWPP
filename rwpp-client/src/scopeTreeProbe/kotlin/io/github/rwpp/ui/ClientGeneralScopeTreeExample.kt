package io.github.rwpp.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.composescopetree.ImplicitScopeNode
import dev.composescopetree.ImplicitScopeTreeHost
import io.github.rwpp.api.logging.logger
import io.github.rwpp.widget.v2.AeroBadge
import io.github.rwpp.widget.v2.AeroSettingGroup
import io.github.rwpp.widget.v2.AeroSettingRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private const val SettingsWidgets = "io.github.rwpp.widget.v2."
private const val DemoLabel = "ScopeTree demo entry"

/** Launch with -Drwpp.scopeTreeClientGeneralDemo=true to open Settings → Client and add a row. */
internal suspend fun runClientGeneralScopeTreeExample(host: ImplicitScopeTreeHost) {
    val navigator = withTimeout(30_000) {
        snapshotFlow { UI.liveNavigator?.takeIf { it.current is MainMenuScreen } }.filterNotNull().first()
    }
    navigator.push<SettingsScreen>("RWPP")
    val settings = navigator.current as SettingsScreen
    settings.selectedItem = 7 // The rwpp-client tab in SettingsScreen.

    // A tab change can unmount the group. Resolve its address again when the tab returns.
    snapshotFlow { findClientGeneral(host.roots)?.address }.collectLatest { address ->
        if (address == null) return@collectLatest
        val originalControls = checkNotNull(findClientGeneral(host.roots)).originalControls()
        check(originalControls.isNotEmpty()) { "The client GENERAL group has no original controls" }
        val layoutSize = CompletableDeferred<IntSize>()

        host.replaceCallWithSlot(address, "content") { args, original: @Composable ColumnScope.() -> Unit ->
            AeroSettingGroup(
                title = args.getAs("title"),
                tag = if ("tag" in args.names) args.getAs("tag") else null,
            ) {
                AeroSettingRow(
                    label = DemoLabel,
                    modifier = Modifier.onSizeChanged { size ->
                        if (size.width > 0 && size.height > 0) layoutSize.complete(size)
                    },
                ) { AeroBadge("SCOPE TREE") }
                original() // Invoke the original slot on this AeroSettingGroup's ColumnScope.
            }
        }
        try {
            val size = withTimeout(10_000) {
                val measured = layoutSize.await()
                snapshotFlow {
                    val group = findClientGeneral(host.roots)
                    group?.address == address && group.originalControls() == originalControls &&
                        descendants(group).count { it.isDemoRow() } == 1
                }.first { it }
                measured
            }
            logger().info(
                "ScopeTree Client GENERAL demo passed: ${address.segments.last().site}, " +
                    "one new row at ${size.width}x${size.height}, ${originalControls.size} original controls retained"
            )
            awaitCancellation()
        } finally {
            if (findClientGeneral(host.roots)?.address == address) host.resetDefinition(address)
        }
    }
}

private fun findClientGeneral(roots: List<ImplicitScopeNode>): ImplicitScopeNode? {
    val matches = roots.asSequence().flatMap(::descendants)
        .filter { it.function == "io.github.rwpp.ui.ClientHudSettings" && ".Settings.kt:" in it.site }
        .flatMap { it.children.asSequence().flatMap(::descendants) }
        .filter {
            it.function == "${SettingsWidgets}AeroSettingGroup" && ".ClientHudSettings.kt:" in it.site &&
                it.arguments?.let { args -> "title" in args.names && args["title"] == "GENERAL" } == true
        }
        .toList()
    check(matches.size <= 1) { "Client GENERAL location is ambiguous: ${matches.map { it.address }}" }
    return matches.singleOrNull()
}

private fun ImplicitScopeNode.originalControls(): List<String> = descendants(this)
    .filter { node ->
        ".ClientHudSettings.kt:" in node.site && node.function in setOf(
            "${SettingsWidgets}AeroSettingToggle",
            "${SettingsWidgets}AeroSettingDropDown",
            "${SettingsWidgets}AeroSettingSlider",
        )
    }
    .map { it.site }
    .sorted()
    .toList()

private fun ImplicitScopeNode.isDemoRow(): Boolean = function == "${SettingsWidgets}AeroSettingRow" &&
    ".ClientGeneralScopeTreeExample.kt:" in site && arguments?.let { "label" in it.names && it["label"] == DemoLabel } == true
