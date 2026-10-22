/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.download
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.mapDir
import io.github.rwpp.modDir
import io.github.rwpp.api.net.Net
import io.github.rwpp.net.NetResourceInfo
import io.github.rwpp.net.ResourceType
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyVerticalGridScrollbar
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import java.io.File
import io.github.rwpp.net.bbsProtocols
import io.github.rwpp.net.searchBBS

@Single
class ResourceBrowserScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        var downloadingMod by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }

        var isLoading by remember { mutableStateOf(false) }
        val net = koinInject<Net>()
        var page by remember { mutableIntStateOf(1) }
        var selectedProtocolIndex by remember { mutableIntStateOf(1) }
        var selectedTypeIndex by remember { mutableIntStateOf(0) }
        var keyword by remember { mutableStateOf("") }
        var searchInput by remember { mutableStateOf("") }
        val allInfo = remember { SnapshotStateList<NetResourceInfo>() }

        LaunchedEffect(keyword, page, selectedTypeIndex) {
            isLoading = true
            net.searchBBS(
                net.bbsProtocols[selectedProtocolIndex],
                page,
                keyword,
                if (selectedTypeIndex == 0) ResourceType.Mod else ResourceType.Map,
            ) { result ->
                result.getOrNull()?.let { allInfo.addAll(it) }
                isLoading = false
            }
        }

        if (downloadingMod) {
            Dialog(onDismissRequest = {
                if (progress >= 1f) {
                    downloadingMod = false
                    progress = 0f
                }
            }) {
                AeroPanel(modifier = Modifier.size(320.dp, 180.dp).padding(12.dp)) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AeroHeader(
                            title = "DATA_TRANSFER",
                            tag = if (progress >= 1f) "COMPLETE" else if (progress < 0f) "ERROR" else "DOWNLOADING",
                            color = if (progress >= 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )

                        Text(
                            text = if (progress >= 1f) "RESOURCE_ACQUIRED" else if (progress < 0f) "TRANSFER_FAILED" else "DOWNLOADING... ${(progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )

                        if (progress !in 0f..<1f) {
                            AeroButton(
                                text = "CLOSE",
                                onClick = {
                                    downloadingMod = false
                                    progress = 0f
                                }
                            )
                        }
                    }
                }
            }
        }

        AeroPanel(
            modifier = Modifier.fillMaxSize().padding(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AeroHeader(
                        title = "RESOURCE MATRIX NETWORK",
                        tag = net.bbsProtocols.getOrNull(selectedProtocolIndex)?.name ?: "NET",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    AeroButton(
                        text = readI18n("rwpp.common.exit"),
                        isDanger = true,
                        onClick = { navigator.pop() },
                        clickSound = Sounds.closePopup,
                        modifier = Modifier.width(100.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val resourceTypes = remember {
                        listOf(readI18n("rwpp.common.mod"), readI18n("rwpp.common.map"))
                    }

                    AeroDropdownMenu(
                        label = readI18n("rwpp.browser.resourceType"),
                        items = resourceTypes,
                        selectedIndex = selectedTypeIndex,
                        onItemSelected = { index ->
                            allInfo.clear()
                            page = 1
                            selectedTypeIndex = index
                        },
                        modifier = Modifier.weight(0.3f)
                    )

                    AeroDropdownMenu(
                        label = "API",
                        items = net.bbsProtocols.map { it.name },
                        selectedIndex = selectedProtocolIndex,
                        onItemSelected = { index ->
                            selectedProtocolIndex = index
                        },
                        modifier = Modifier.weight(0.3f)
                    )

                    AeroTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it },
                        placeholder = readI18n("rwpp.browser.search"),
                        modifier = Modifier.weight(0.4f),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            AeroIconButton(
                                painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowForward),
                                size = 28.dp,
                                iconSize = 14.dp,
                                onClick = {
                                    keyword = searchInput
                                    selectedProtocolIndex = if (keyword.isNotBlank()) 0 else 1
                                    page = 1
                                    allInfo.clear()
                                }
                            )
                        }
                    )
                }

                AeroDivider(modifier = Modifier.padding(vertical = 8.dp))

                val state = rememberLazyGridState()
                LazyVerticalGridScrollbar(
                    state = state,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    LazyVerticalGrid(
                        state = state,
                        columns = GridCells.Adaptive(300.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = allInfo,
                            key = { it.id }
                        ) { info ->
                            AeroPanel(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    ) {
                                        AsyncImage(
                                            model = info.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = info.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        if (info.version != null) {
                                            Text(
                                                text = info.version!!,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }

                                        if (info.author != null) {
                                            Text(
                                                text = info.author!!,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }

                                        if (info.downloadNum != null) {
                                            Text(
                                                text = "DL: ${info.downloadNum}",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }

                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (info.bbsUrl != null) {
                                            AeroIconButton(
                                                painter = rememberVectorPainter(Icons.Default.Home),
                                                size = 32.dp,
                                                iconSize = 16.dp,
                                                onClick = { net.openUriInBrowser(info.bbsUrl!!) }
                                            )
                                        }

                                        if (info.downloadUrl != null) {
                                            AeroIconButton(
                                                painter = painterResource(Res.drawable.download),
                                                size = 32.dp,
                                                iconSize = 16.dp,
                                                onClick = {
                                                    downloadingMod = true
                                                    net.downloadFile(
                                                        info.downloadUrl!!,
                                                        File(
                                                            if (selectedTypeIndex == 0)
                                                                "$modDir/${info.title}.rwmod"
                                                            else "$mapDir/${info.title}.tmx"
                                                        )
                                                    ) { p -> progress = p }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    AeroButton(
                                        text = readI18n("rwpp.browser.loadMore"),
                                        modifier = Modifier.width(200.dp),
                                        onClick = { page += 1 }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
