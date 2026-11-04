/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game

/** A bounded catalog batch; flows emit one page at a time instead of rebuilding a growing list per entry. */
data class CatalogPage<T>(
    val pageIndex: Int,
    val items: List<T>,
)
