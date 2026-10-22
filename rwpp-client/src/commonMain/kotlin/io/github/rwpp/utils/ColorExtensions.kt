/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

@file:JvmName("colorKt")

/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 PolyForm Shield License 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the PolyForm Shield License that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.utils

fun parseColorToArgb(color: String): Int {
    val hash = color.hashCode()
    val alpha = 0xFF shl 24
    val red = (hash shr 16) and 0xFF
    val green = (hash shr 8) and 0xFF
    val blue = hash and 0xFF
    return alpha or (red shl 16) or (green shl 8) or blue
}

fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

fun rgbaToArgb(rgba: Int): Int {
    val r = (rgba shr 24) and 0xFF
    val g = (rgba shr 16) and 0xFF
    val b = (rgba shr 8) and 0xFF
    val a = rgba and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}