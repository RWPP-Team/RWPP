/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.logging

import li.songe.codeorigin.CallSite
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 取得以调用点为名的日志器。
 *
 * CodeOrigin 在编译期填入调用者的包名、类型名与文件名，因此顶层 Kotlin 文件也能得到有意义的名称，
 * 调用处无需再手写类名。
 */
fun logger(
    @CallSite("{package}") packageName: String = "",
    @CallSite("{type}") typeName: String = "",
    @CallSite("{file}") fileName: String = "",
): Logger {
    val owner = typeName.ifEmpty { fileName.removeSuffix(".kt") }
    val name = listOf(packageName, owner).filter(String::isNotEmpty).joinToString(".")
    return LoggerFactory.getLogger(name.ifEmpty { "rwml" })
}
