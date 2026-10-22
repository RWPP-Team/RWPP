/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.os.Environment
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.AppContext
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.impl.BaseAppContextImpl
import okhttp3.OkHttpClient
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

@Single([AppContext::class])
class AppContextImpl : BaseAppContextImpl() {
    private val exitActions = mutableListOf<() -> Unit>()

    init {
        Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
    }

    override fun onExit(action: () -> Unit) {
        exitActions.add(action)
    }

    override fun isAndroid(): Boolean = true

    override fun isDesktop(): Boolean = false
    override fun externalStoragePath(path: String): String {
        return Environment.getExternalStorageDirectory().absolutePath + "/rustedWarfare/$path"
    }

    override fun exit() {
        try {
            val engine = runCatching { GameEngine.getInstance() }.getOrNull()
            runCatching { get<ConfigIO>().saveAllConfig() }
            runCatching {
                engine?.settings?.apply {
                    numLoadsSinceRunningGameOrNormalExit = 0
                    numIncompleteLoadAttempts = 0
                    save()
                }
            }
            exitActions.toList().forEach { action -> runCatching(action) }
        } finally {
            exitProcess(0)
        }
    }
}
