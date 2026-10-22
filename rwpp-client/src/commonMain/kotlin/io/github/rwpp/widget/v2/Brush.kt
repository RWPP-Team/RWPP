/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.metal
import org.jetbrains.compose.resources.imageResource

@Composable
fun TitleBrush(): ShaderBrush {
    val image = imageResource(Res.drawable.metal)

    return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
}