/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp

import org.koin.core.component.KoinComponent
import java.io.File
import java.io.InputStream

interface AppContext : KoinComponent {
    fun init()

    fun onExit(action: () -> Unit)

    fun isAndroid(): Boolean

    fun isDesktop(): Boolean

    fun externalStoragePath(path: String): String

    /** Opens a platform file picker. Does nothing when the platform has no picker. */
    fun openFileChooser(onChooseFile: (File) -> Unit)

    /** Export a file through the platform's document picker and, where available, share it. */
    fun shareFile(name: String, sourcePath: String, openSource: () -> InputStream, onResult: (Result<Unit>) -> Unit)

    fun exit()
}
