/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net.impl

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.corrodinggames.rts.gameFramework.GameEngine

internal actual fun apiOpenUriInBrowser(uri: String) {
    val context = GameEngine.getInstance().context
    val flags = if (context !is Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(flags))
}
