/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import android.app.Activity
import android.content.Intent
import com.corrodinggames.rts.gameFramework.GameEngine
import java.io.File
import java.io.InputStream

internal actual object GamePlatformHost {
    private const val GAME_ACTIVITY = "io.github.rwml.android.builtin.InGameActivity"

    actual val generatedMapDirectory: String
        get() = File(requireNotNull(GameEngine.getInstance().context).filesDir, "rwml/maps").absolutePath
    actual val savedGameDirectory: String = "/SD/rustedWarfare/saves"

    actual fun listGameAssetDirectory(path: String): List<String> =
        GameEngine.getInstance().context.assets.list(path).orEmpty().toList()

    actual fun openGameAsset(path: String): InputStream = GameEngine.getInstance().context.assets.open(path)

    actual fun openGameScreen() {
        io.github.rwpp.api.game.AndroidGameScreens.open?.let { it(); return }
        val engine = GameEngine.getInstance()
        val context = engine.currentActivity ?: engine.context ?: return
        if (context.javaClass.name == GAME_ACTIVITY) return
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(Intent().setClassName(context, GAME_ACTIVITY).addFlags(flags))
    }

    actual fun closeGameScreen() {
        io.github.rwpp.api.game.AndroidGameScreens.close?.let { it(); return }
        val activity = GameEngine.getInstance().currentActivity as? Activity ?: return
        if (activity.javaClass.name != GAME_ACTIVITY) return
        activity.runOnUiThread { activity.finish() }
    }

    actual fun continueGame() = openGameScreen()
}
