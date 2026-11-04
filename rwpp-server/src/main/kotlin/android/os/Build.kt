/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package android.os

/** Desktop identity for the shared game engine; no Android platform is present. */
object Build {
    const val MODEL = "RWPP Server"
    const val MANUFACTURER = "RWPP"

    object VERSION {
        const val RELEASE = "desktop"
        const val SDK_INT = 0
        const val SDK = "0"
    }
}
