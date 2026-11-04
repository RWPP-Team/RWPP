/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.AppContext
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.net.Net
import io.github.rwpp.generated.Res
import io.github.rwpp.logic.ThirdPartyLibrary
import io.github.rwpp.logic.bundledSourceAttributions
import io.github.rwpp.logic.parseThirdPartyLibraries
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.widget.v2.*
import org.koin.compose.koinInject
import org.koin.core.annotation.Single

@Single
class ContributorListScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val net = koinInject<Net>()
        val android = koinInject<AppContext>().isAndroid()
        var search by remember { mutableStateOf("") }
        val libraries by produceState<Result<List<ThirdPartyLibrary>>?>(null, android) {
            value = runCatching {
                val platform = if (android) "android" else "jvm"
                (parseThirdPartyLibraries(Res.readBytes("files/aboutlibraries-$platform.json").decodeToString()) +
                        bundledSourceAttributions).sortedBy { it.name.lowercase() }
            }
        }
        AeroPanel(Modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AeroHeaderWithCloseButton("CREDITS", onDismissRequest = { navigator.pop() })
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item("sponsors") {
                        CreditsSection(
                            "SPONSORS",
                            listOf("铁锈盒子", "亚托莉", "AABBRST", "Toledo", "猫猫", "cheems", "Eresta", "zhou")
                        )
                        AeroButton(
                            readI18n("rwpp.contributors.sponsorPrompt"),
                            onClick = { net.openUriInBrowser("https://afdian.com/a/minxyzgo") },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    item("contributors") {
                        CreditsSection(
                            "CONTRIBUTORS",
                            listOf("Minxyzgo", "Dr", "wty-hub", "eam2539")
                        )
                    }
                    item("thanks") {
                        CreditsSection(
                            "SPECIAL THANKS",
                            listOf("Corroding Games", "Luke Hoschke", "JDSherbert")
                        )
                    }
                    item("libraries-header") {
                        AeroHeader("THIRD-PARTY LIBRARIES", tag = "LICENSES")
                    }
                    item("libraries-search") {
                        AeroTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = readI18n("rwpp.contributors.libraries.search"),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val result = libraries
                    if (result == null) {
                        item("loading") { Text(readI18n("rwpp.contributors.libraries.loading")) }
                    } else if (result.isFailure) {
                        item("error") { Text(readI18n("rwpp.contributors.libraries.unavailable")) }
                    } else {
                        val libraryList = result.getOrThrow().filter {
                            search.isBlank() || it.name.contains(search, ignoreCase = true) ||
                                    it.id.contains(search, ignoreCase = true) ||
                                    it.licenses.any { license -> license.name.contains(search, ignoreCase = true) }
                        }
                        items(libraryList, key = { "library:${it.id}" }) { library ->
                            LibraryCard(library, net)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditsSection(title: String, names: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AeroHeader(title)
        Text(names.joinToString(" · "), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

@Composable
private fun LibraryCard(library: ThirdPartyLibrary, net: Net) {
    var expanded by remember(library.id) { mutableStateOf(false) }
    AeroPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AeroButton(
                library.name,
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                subText = if (expanded) "▲" else "▼",
            )
            val licenseNames = library.licenses.joinToString { it.name }
                .ifBlank { readI18n("rwpp.contributors.libraries.unknownLicense") }
            Text(
                listOf(library.version, licenseNames).filter(String::isNotBlank).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            if (expanded) {
                Text(library.id, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (library.description.isNotBlank()) Text(library.description, fontSize = 13.sp)
                if (library.website.startsWith("https://") || library.website.startsWith("http://")) {
                    AeroButton(
                        readI18n("rwpp.contributors.libraries.website"),
                        { net.openUriInBrowser(library.website) })
                }
                if (library.licenses.isEmpty()) Text(readI18n("rwpp.contributors.libraries.unknownLicense"))
                library.licenses.forEach { license ->
                    Text(license.name, color = MaterialTheme.colorScheme.primary)
                    if (license.text.isNotBlank()) Text(license.text, fontSize = 12.sp)
                    if (license.url.startsWith("https://") || license.url.startsWith("http://")) {
                        AeroButton(
                            readI18n("rwpp.contributors.libraries.openLicense"),
                            { net.openUriInBrowser(license.url) })
                    }
                }
            }
        }
    }
}
