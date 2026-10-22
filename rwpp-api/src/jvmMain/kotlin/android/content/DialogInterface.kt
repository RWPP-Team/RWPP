/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package android.content

/** Callback linkage only; the headless server never creates Android dialogs. */
interface DialogInterface {
    fun cancel()
    fun dismiss()

    interface OnCancelListener {
        fun onCancel(dialog: DialogInterface)
    }

    interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }
}
