/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import org.koin.core.component.KoinComponent

interface IUserInterface : KoinComponent {
    /**
     * Show a warning dialog to the user.
     */
    fun showWarning(reason: String, isKicked: Boolean = false)

    /**
     * Show a question dialog to the user.
     * @param callback The callback function to be called when the user submits the answer, or dismisses the dialog when given null.
     */
    fun showQuestion(title: String, message: String, callback: (String?) -> Unit)

    /**
     * Show a dialog to the user. Note that there can only be one dialog at a time.
     *
     * @param content The dialog content. Passing null closes the current dialog. The lambda receives the
     * dismiss callback that runs the dialog transition before the content leaves the composition.
     */
    fun showDialog(content: (@Composable (dismiss: () -> Unit) -> Unit)?)
}
