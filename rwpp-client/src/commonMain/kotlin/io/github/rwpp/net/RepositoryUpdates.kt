/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.net

import io.github.rwml.api.Rwml
import io.github.rwml.api.repository.ExtensionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ReleaseDownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?)

internal data class ReleaseUpdateResult(
    val importedIds: Set<String>,
    val skipped: List<String>,
    val failed: Map<String, String>,
    val restartRequired: Boolean,
) {
    val complete: Boolean
        get() = failed.isEmpty() && importedIds.containsAll(RWPP_EXTENSION_IDS)
}

/** 目录中代表 RWPP 客户端的条目；其归档同时包含 RWPP API 与客户端扩展。 */
internal const val RWPP_CLIENT_EXTENSION_ID = "io.github.rwpp"

internal val RWPP_EXTENSION_IDS = setOf("io.github.rwpp.api", "io.github.rwpp")

/**
 * @return 宿主提供的扩展仓库；宿主未安装仓库或尚未准备界面服务时为 null
 */
internal fun rwmlRepository(): ExtensionRepository? {
    val runtime = Rwml.get() ?: return null
    val services = runCatching { runtime.ui() }.getOrNull() ?: return null
    if (!services.hasRepository()) return null
    return runCatching { services.repository() }.getOrNull()
}

/**
 * 读取 RWPP 在目录中的最新发布。
 *
 * 说明文本取目录条目的发布说明，没有发布说明时退回摘要；预发布版本沿用 SemVer 的连字符标记。
 */
internal suspend fun ExtensionRepository.rwppProfile(isAndroid: Boolean): LatestVersionProfile? =
    withContext(Dispatchers.IO) {
        val entry = catalog().find(RWPP_CLIENT_EXTENSION_ID) ?: return@withContext null
        val sizes = mapOf(
            "jvm" to entry.artifactsFor("jvm").sumOf { it.size() },
            "android" to entry.artifactsFor("android").sumOf { it.size() },
        )
        LatestVersionProfile(
            version = entry.version(),
            body = entry.notes().ifBlank { entry.summary() },
            prerelease = entry.version().contains('-'),
            sizes = sizes,
        )
    }

/**
 * 从仓库安装 RWPP 的最新归档。
 *
 * 下载与校验由 RWML 完成，安装走宿主扩展库的写入路径，因此启用状态与其他扩展一致。进度回调来自工作线程，
 * 通过单槽通道转交给调用方的挂起回调，避免在非挂起回调中阻塞线程。
 */
internal suspend fun ExtensionRepository.installRwppUpdate(
    isAndroid: Boolean,
    onProgress: suspend (ReleaseDownloadProgress) -> Unit,
): ReleaseUpdateResult = coroutineScope {
    val progress = Channel<ReleaseDownloadProgress>(Channel.CONFLATED)
    val pump = launch {
        for (update in progress) onProgress(update)
    }
    try {
        val report = withContext(Dispatchers.IO) {
            install(
                RWPP_CLIENT_EXTENSION_ID,
                if (isAndroid) "android" else "jvm",
            ) { downloaded, total, _ ->
                progress.trySend(ReleaseDownloadProgress(downloaded, total.takeIf { it > 0 }))
            }
        }
        ReleaseUpdateResult(
            importedIds = report.imported().mapNotNull { it.id() }.toSet(),
            skipped = report.skipped().toList(),
            failed = report.failed().toMap(),
            restartRequired = report.requiresRestart(),
        )
    } finally {
        progress.close()
        pump.join()
    }
}
