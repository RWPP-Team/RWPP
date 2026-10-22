/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package android.os

/** Desktop identity for the shared game engine; no Android platform is present. */
object Build {
    const val MODEL = "RWML JVM Server"
    const val MANUFACTURER = "RWML"

    object VERSION {
        const val RELEASE = "desktop"
        const val SDK_INT = 0
        const val SDK = "0"
    }
}
