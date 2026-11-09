/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Paint
import java.lang.reflect.Field

internal interface NativePaintFlagsAccess {
    fun rwppPaintFlags(): Int
    fun rwppPaintFlags(value: Int)
}

/** Desktop's Android compatibility Paint has a no-op setFilterBitmap; bridge its real flag storage. */
internal actual fun enableNativePaintFiltering(paint: Paint) {
    val access = paint as? NativePaintFlagsAccess
    if (access != null) {
        access.rwppPaintFlags(access.rwppPaintFlags() or FILTER_BITMAP_FLAG)
        return
    }

    // Paint can be loaded by the native graphics stack before RWPP's archive is scanned, so
    // the late interface mixin cannot be assumed to exist on every instance.
    runCatching {
        val flags = paintFlagsField
        flags.setInt(paint, flags.getInt(paint) or FILTER_BITMAP_FLAG)
    }.onFailure {
        // Keep rendering alive on restricted runtimes; the desktop compatibility setter is a no-op.
        paint.setFilterBitmap(true)
    }
}

private const val FILTER_BITMAP_FLAG = 2
private val paintFlagsField: Field by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Paint::class.java.getDeclaredField("flags").apply { isAccessible = true }
}
