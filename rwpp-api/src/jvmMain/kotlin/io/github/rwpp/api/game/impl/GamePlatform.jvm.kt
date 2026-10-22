/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.librocket.scripts.ScriptEngine
import java.io.File
import java.io.InputStream

internal actual object GamePlatformHost {
    actual val generatedMapDirectory: String = "maps"
    actual val savedGameDirectory: String = "saves"

    actual fun listGameAssetDirectory(path: String): List<String> =
        File("assets/$path").list()?.toList().orEmpty()

    actual fun openGameAsset(path: String): InputStream = File("assets/$path").inputStream()

    actual fun openGameScreen() = continueGame()

    actual fun closeGameScreen() {
        ScriptEngine.getInstance().root.showMainMenu()
    }

    actual fun continueGame() {
        ScriptEngine.getInstance().root.resumeNonMenu()
    }
}
