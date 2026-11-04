/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

@Composable
internal actual fun PlatformBackgroundLayer(path: String) {
    if (path.isBlank()) return
    val painter by produceState<Painter?>(null, path) {
        value = null
        if (path.endsWith(".gif", ignoreCase = true)) {
            val frames =
                withContext(Dispatchers.IO) { runCatching { GifBackgroundFrames.open(File(path)) }.getOrNull() }
            if (frames != null) {
                try {
                    while (isActive) {
                        val frame = withContext(Dispatchers.IO) { runCatching { frames.next() }.getOrNull() } ?: break
                        value = frame.image.toPainter()
                        if (!frames.animated) break
                        delay(frame.delayMillis)
                    }
                } finally {
                    frames.close()
                }
                return@produceState
            }
        }
        value = withContext(Dispatchers.IO) {
            runCatching { ImageIO.read(File(path))?.toPainter() }.getOrNull()
        }
    }
    painter?.let { loaded ->
        Image(
            painter = loaded,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
