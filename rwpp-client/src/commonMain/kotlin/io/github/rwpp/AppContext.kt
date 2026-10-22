/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp

import org.koin.core.component.KoinComponent
import java.io.File

interface AppContext : KoinComponent {
    fun init()

    fun onExit(action: () -> Unit)

    fun isAndroid(): Boolean

    fun isDesktop(): Boolean

    fun externalStoragePath(path: String): String

    /** Opens a platform file picker. Does nothing when the platform has no picker. */
    fun openFileChooser(onChooseFile: (File) -> Unit)

    fun exit()
}