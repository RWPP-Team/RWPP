/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.impl

import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.ui.IUserInterface
import io.github.rwpp.ui.UI
import java.io.File

abstract class BaseAppContextImpl : AppContext {
    override fun openFileChooser(onChooseFile: (File) -> Unit) = Unit
    override fun shareFile(
        name: String,
        sourcePath: String,
        openSource: () -> java.io.InputStream,
        onResult: (Result<Unit>) -> Unit
    ) {
        onResult(Result.failure(UnsupportedOperationException("File sharing is unavailable")))
    }

    override fun init() {
        io.github.rwpp.logic.hud.initializeDefaultHud(appKoin.get())
        UI.init()
        appKoin.declare(UI, secondaryTypes = listOf(IUserInterface::class))
        // Android installs client modules after the host has emitted its engine-ready event.
        io.github.rwpp.api.ApiRuntime.initializeLateComponents()
    }
}
