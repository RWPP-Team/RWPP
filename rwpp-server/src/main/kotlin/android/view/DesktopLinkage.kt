package android.view

import android.os.Parcelable

/** Base type of the game's existing KeyEvent implementation. */
abstract class InputEvent : Parcelable

/** Type-only contracts: the dedicated engine never creates an Android view or window. */
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
