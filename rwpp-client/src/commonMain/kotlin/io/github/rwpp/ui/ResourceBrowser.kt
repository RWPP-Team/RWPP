/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
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
import coil3.compose.AsyncImage
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.net.Net
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.download
import io.github.rwpp.net.NetResourceInfo
import io.github.rwpp.net.RTSBoxProtocol
import io.github.rwpp.net.ResourceType
import io.github.rwpp.net.bbsProtocols
import io.github.rwpp.net.downloadBbsResource
import io.github.rwpp.net.searchBBS
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyVerticalGridScrollbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import kotlin.coroutines.resume

@Single
class ResourceBrowserScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val scope = rememberCoroutineScope()
        var showTransfer by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Float?>(null) }
        var downloadError by remember { mutableStateOf<String?>(null) }
        var installed by remember { mutableStateOf(false) }
        var transferJob by remember { mutableStateOf<Job?>(null) }

        var isLoading by remember { mutableStateOf(false) }
        val net = koinInject<Net>()
        var page by remember { mutableIntStateOf(1) }
        var selectedProtocolIndex by remember { mutableIntStateOf(1) }
        var selectedTypeIndex by remember { mutableIntStateOf(0) }
        var retry by remember { mutableIntStateOf(0) }
        var canLoadMore by remember { mutableStateOf(false) }
        var searchError by remember { mutableStateOf(false) }
        var keyword by remember { mutableStateOf("") }
        var searchInput by remember { mutableStateOf("") }
        val allInfo = remember { SnapshotStateList<NetResourceInfo>() }

        val resourceType = if (selectedTypeIndex == 0) ResourceType.Mod else ResourceType.Map
        val protocol = net.bbsProtocols.getOrNull(selectedProtocolIndex)
        val needsKeyword = protocol == RTSBoxProtocol && keyword.isBlank()

        LaunchedEffect(keyword, page, resourceType, protocol, retry) {
            if (page == 1) allInfo.clear()
            searchError = false
            canLoadMore = false
            if (protocol == null || needsKeyword) return@LaunchedEffect
            isLoading = true
            try {
                val results = suspendCancellableCoroutine<Result<Array<NetResourceInfo>>> { continuation ->
                    val call = net.searchBBS(protocol, page, keyword, resourceType) { continuation.resume(it) }
                    continuation.invokeOnCancellation { call.cancel() }
                }
                results.onSuccess { resources ->
                    val known = allInfo.mapTo(hashSetOf(), NetResourceInfo::id)
                    val fresh = resources.filter { known.add(it.id) }
                    allInfo.addAll(fresh)
                    canLoadMore = fresh.isNotEmpty()
                }.onFailure { searchError = true }
            } finally {
                isLoading = false
            }
        }

        if (showTransfer) {
            val percentage = progress
            val done = installed || downloadError != null
            AnimatedAlertDialog(
                visible = true,
                onDismissRequest = {
                    showTransfer = false
                    transferJob = null
                },
                onDismissStarted = { transferJob?.cancel() },
            ) { dismiss ->
                LaunchedEffect(installed) {
                    if (installed) dismiss()
                }
                AeroPanel(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth(0.92f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AeroHeader(
                            title = "DATA TRANSFER",
                            tag = if (downloadError != null) "ERROR" else if (done) "COMPLETE" else "DOWNLOADING",
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )

                        Text(
                            text = downloadError ?: when {
                                done -> readI18n("rwpp.browser.resourceAcquired")
                                percentage == null -> readI18n("rwpp.browser.downloading")
                                else -> readI18n("rwpp.browser.downloadProgress", (percentage * 100).toInt())
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (downloadError == null) {
                            val progressBar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            if (percentage == null) LinearProgressIndicator(modifier = progressBar)
                            else LinearProgressIndicator(progress = { percentage }, modifier = progressBar)
                        }

                        AeroButton(
                            text = readI18n(if (done) "rwpp.common.close" else "rwpp.common.cancel").uppercase(),
                            onClick = dismiss
                        )
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
                        label = readI18n("rwpp.browser.api"),
                        items = net.bbsProtocols.map { it.name },
                        selectedIndex = selectedProtocolIndex,
                        onItemSelected = { index ->
                            allInfo.clear()
                            page = 1
                            selectedProtocolIndex = index
                            retry++
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
                                    keyword = searchInput.trim()
                                    selectedProtocolIndex = if (keyword.isNotBlank()) 0 else 1
                                    page = 1
                                    allInfo.clear()
                                    retry++
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
                                                text = info.version,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }

                                        if (info.author != null) {
                                            Text(
                                                text = info.author,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }

                                        if (info.downloadNum != null) {
                                            Text(
                                                text = readI18n("rwpp.browser.downloadCount", info.downloadNum),
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
                                                contentDescription = readI18n("rwpp.browser.openResourcePage"),
                                                onClick = { net.openUriInBrowser(info.bbsUrl) }
                                            )
                                        }

                                        if (info.downloadUrl != null) {
                                            AeroIconButton(
                                                painter = painterResource(Res.drawable.download),
                                                size = 32.dp,
                                                iconSize = 16.dp,
                                                contentDescription = readI18n("rwpp.browser.downloadResource"),
                                                onClick = {
                                                    if (!showTransfer) {
                                                        progress = null
                                                        downloadError = null
                                                        installed = false
                                                        showTransfer = true
                                                        transferJob = scope.launch {
                                                            try {
                                                                net.downloadBbsResource(info, resourceType) { progress = it }
                                                                installed = true
                                                            } catch (cancelled: CancellationException) {
                                                                throw cancelled
                                                            } catch (failure: Exception) {
                                                                downloadError = failure.message?.takeIf(String::isNotBlank)
                                                                    ?: readI18n("rwpp.browser.transferFailed")
                                                            }
                                                        }
                                                    }
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
                                } else if (searchError || canLoadMore) {
                                    AeroButton(
                                        text = readI18n(if (searchError) "rwpp.browser.retrySearch" else "rwpp.browser.loadMore"),
                                        modifier = Modifier.width(200.dp),
                                        onClick = { if (searchError) retry++ else page++ }
                                    )
                                } else if (allInfo.isEmpty()) {
                                    Text(
                                        readI18n(if (needsKeyword) "rwpp.browser.enterKeyword" else "rwpp.browser.noResults"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
