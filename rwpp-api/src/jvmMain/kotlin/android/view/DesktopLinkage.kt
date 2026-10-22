/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package android.view

import android.os.Parcelable

/** Base type of the game's existing KeyEvent implementation. */
abstract class InputEvent : Parcelable

/** Type-only contracts; the dedicated engine never creates an Android view or window. */
abstract class View {
    interface OnCreateContextMenuListener
}

abstract class LayoutInflater {
    interface Factory
    interface Factory2 : Factory
}

abstract class Window {
    interface Callback
}

interface WindowManager {
    class BadTokenException @JvmOverloads constructor(message: String? = null) : RuntimeException(message)
}

interface Menu

interface MenuItem {
    interface OnMenuItemClickListener
    interface OnActionExpandListener
}

interface ContextMenu : Menu {
    interface ContextMenuInfo
}

interface SubMenu : Menu
