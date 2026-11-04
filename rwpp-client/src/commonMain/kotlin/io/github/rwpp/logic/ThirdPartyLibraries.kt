/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject

internal data class ThirdPartyLicense(val name: String, val url: String, val text: String)

internal data class ThirdPartyLibrary(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val website: String,
    val licenses: List<ThirdPartyLicense>,
)

/** Source-code attributions that are not Gradle modules and therefore cannot be auto-detected. */
internal val bundledSourceAttributions = listOf(
    ThirdPartyLibrary(
        id = "bundled:anuken-arc", name = "Arc", version = "", description = "",
        website = "https://github.com/Anuken/Arc",
        licenses = listOf(
            ThirdPartyLicense(
                "Apache License 2.0",
                "https://github.com/Anuken/Arc/blob/master/LICENSE",
                ""
            )
        ),
    ),
    ThirdPartyLibrary(
        id = "bundled:lazy-column-scrollbar", name = "LazyColumnScrollbar", version = "", description = "",
        website = "https://github.com/nanihadesuka/LazyColumnScrollbar",
        licenses = listOf(
            ThirdPartyLicense(
                "MIT License",
                "https://github.com/nanihadesuka/LazyColumnScrollbar/blob/master/LICENSE",
                ""
            )
        ),
    ),
)

private fun JsonObject.stringValue(key: String): String =
    get(key)?.takeIf { it.isString }?.asString().orEmpty()

/** Parses AboutLibraries' generated metadata without requiring its Material UI. */
internal fun parseThirdPartyLibraries(json: String): List<ThirdPartyLibrary> {
    val root = Json.parse(json).asObject()
    val licenseIndex = root.get("licenses")?.takeIf { it.isObject }?.asObject()
    val entries = root.get("libraries")?.takeIf { it.isArray }?.asArray() ?: return emptyList()
    return entries.mapNotNull { entry ->
        if (!entry.isObject) return@mapNotNull null
        val item = entry.asObject()
        val id = item.stringValue("uniqueId")
        if (id.isBlank()) return@mapNotNull null
        val licenses = item.get("licenses")?.takeIf { it.isArray }?.asArray()
            ?.mapNotNull licenseLoop@{ license ->
                if (!license.isString) return@licenseLoop null
                val key = license.asString()
                val info = licenseIndex?.get(key)?.takeIf { it.isObject }?.asObject()
                ThirdPartyLicense(
                    name = info?.stringValue("name")?.ifBlank { key } ?: key,
                    url = info?.stringValue("url").orEmpty(),
                    text = info?.stringValue("content").orEmpty(),
                )
            }.orEmpty()
        ThirdPartyLibrary(
            id = id,
            name = item.stringValue("name").ifBlank { id },
            version = item.stringValue("artifactVersion"),
            description = item.stringValue("description"),
            website = item.stringValue("website"),
            licenses = licenses,
        )
    }.sortedBy { it.name.lowercase() }
}
