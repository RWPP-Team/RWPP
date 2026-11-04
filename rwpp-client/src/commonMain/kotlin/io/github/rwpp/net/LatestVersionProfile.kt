/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.net

/**
 * RWPP 更新对话框使用的版本信息。
 *
 * 版本、说明与各平台的归档大小都来自 RWML 扩展仓库的目录条目。RWPP 不再自行请求发布接口，也不再解析发布资源，
 * 因此此处只保留界面需要的字段。目录中同一扩展可以携带多个归档，[sizes] 因而以平台为键保存各平台的总字节数。
 */
internal data class LatestVersionProfile(
    val version: String,
    val body: String,
    val prerelease: Boolean,
    internal val sizes: Map<String, Long>,
) {
    /** @return 该平台的归档总字节数；目录缺少此平台的归档时为 null */
    internal fun archiveSize(isAndroid: Boolean): Long? =
        sizes[if (isAndroid) "android" else "jvm"]?.takeIf { it > 0 }
}

/** 去掉发布标签的 v 前缀，并拒绝不能安全用于展示的版本文本。 */
internal fun normalizeReleaseVersion(value: String): String {
    val withoutPrefix = if (value.startsWith("v", ignoreCase = true)) value.drop(1) else value
    require(withoutPrefix.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) {
        "Unsafe release version tag"
    }
    return withoutPrefix
}

/** Returns null when either value is not a valid SemVer version. */
internal fun compareReleaseVersions(left: String, right: String): Int? {
    val leftVersion = parseSemVer(left) ?: return null
    val rightVersion = parseSemVer(right) ?: return null
    for (index in 0..2) {
        val result = compareNumericIdentifiers(leftVersion.core[index], rightVersion.core[index])
        if (result != 0) return result
    }
    if (leftVersion.prerelease == null) return if (rightVersion.prerelease == null) 0 else 1
    if (rightVersion.prerelease == null) return -1
    val commonLength = minOf(leftVersion.prerelease.size, rightVersion.prerelease.size)
    for (index in 0 until commonLength) {
        val leftPart = leftVersion.prerelease[index]
        val rightPart = rightVersion.prerelease[index]
        val result = when {
            leftPart.numeric && rightPart.numeric -> compareNumericIdentifiers(leftPart.text, rightPart.text)
            leftPart.numeric -> -1
            rightPart.numeric -> 1
            else -> leftPart.text.compareTo(rightPart.text)
        }
        if (result != 0) return result
    }
    return leftVersion.prerelease.size.compareTo(rightVersion.prerelease.size)
}

private data class SemVerPart(val text: String) {
    val numeric: Boolean = text.all { it in '0'..'9' }
}

private data class SemVer(val core: List<String>, val prerelease: List<SemVerPart>?)

private fun compareNumericIdentifiers(left: String, right: String): Int =
    left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)

private fun parseSemVer(value: String): SemVer? {
    val normalized = runCatching { normalizeReleaseVersion(value) }.getOrNull() ?: return null
    val match = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
        .matchEntire(normalized) ?: return null
    val prereleaseText = match.groups[4]?.value
    val prerelease = if (prereleaseText == null) null else {
        val identifiers = mutableListOf<SemVerPart>()
        for (identifier in prereleaseText.split('.')) {
            if (identifier.isEmpty()) return null
            val numeric = identifier.all { it in '0'..'9' }
            if (numeric && identifier.length > 1 && identifier.startsWith('0')) return null
            identifiers += SemVerPart(identifier)
        }
        identifiers
    }
    return SemVer(
        core = (1..3).map { match.groupValues[it] },
        prerelease = prerelease,
    )
}
