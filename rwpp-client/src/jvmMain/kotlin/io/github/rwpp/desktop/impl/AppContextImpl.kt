/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.librocket.scripts.ScriptEngine
import io.github.rwpp.AppContext
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.impl.BaseAppContextImpl
import org.koin.core.annotation.Single
import org.koin.core.component.get
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Single([AppContext::class])
class AppContextImpl : BaseAppContextImpl() {
    private val exitActions = mutableListOf<() -> Unit>()

    override fun onExit(action: () -> Unit) {
        exitActions.add(action)
    }

    override fun isAndroid(): Boolean = false

    override fun isDesktop(): Boolean = true
    override fun externalStoragePath(path: String): String {
        return (System.getProperty("user.dir") + "/$path").replace("\\", "/")
    }

    override fun openFileChooser(onChooseFile: (File) -> Unit) {
        thread(isDaemon = true) {
            val fileDialog = FileDialog(null as Frame?, "选择文件", FileDialog.LOAD)
            fileDialog.isVisible = true

            val directory = fileDialog.directory
            val fileName = fileDialog.file

            if (directory != null && fileName != null) {
                onChooseFile(File(directory, fileName))
            }
        }
    }

    override fun exit() {
        try {
            val engine = runCatching { GameEngine.getInstance() }.getOrNull()
            runCatching {
                engine?.settings?.apply {
                    numLoadsSinceRunningGameOrNormalExit = 0
                    numIncompleteLoadAttempts = 0
                }
            }
            runCatching { get<ConfigIO>().saveAllConfig() }
            exitActions.toList().forEach { action -> runCatching(action) }
            if (engine != null) runCatching { ScriptEngine.getInstance().root.exit() }
        } finally {
            // The host owns the process lifetime. Always terminate it even if a
            // partially initialized engine or an extension shutdown hook fails.
            exitProcess(0)
        }
    }
}
