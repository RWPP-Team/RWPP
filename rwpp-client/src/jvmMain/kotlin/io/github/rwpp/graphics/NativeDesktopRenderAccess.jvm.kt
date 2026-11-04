/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import com.corrodinggames.rts.game.InGameImage
import com.corrodinggames.rts.game.map.MapLayerBufferBufferImage
import com.corrodinggames.rts.gameFramework.render.PaintGLShader

internal interface NativeMapBufferAccess {
    fun rwppBufferScale(): Float
    fun rwppBufferGrid(): Array<Array<MapLayerBufferBufferImage>>?
}

internal interface NativeDesktopWorldAccess {
    fun rwppPostImage(): InGameImage?
    fun rwppDisplacementImage(): InGameImage?
}

internal interface NativePostImageAccess {
    fun rwppPostShader(): PaintGLShader?
}
