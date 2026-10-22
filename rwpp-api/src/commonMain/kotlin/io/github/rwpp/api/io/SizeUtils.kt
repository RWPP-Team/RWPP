/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.io

object SizeUtils {
    fun byteToKB(b: Long) = b / 1024
    fun byteToMB(b: Long) = b / (1024 * 1024)
    fun kBToByte(kb: Long) = kb * 1024
    fun kBToMB(kb: Long) = kb / 1024
    fun mBToByte(mb: Long) = mb * 1024 * 1024
    fun mBToKB(mb: Long) = mb * 1024
}