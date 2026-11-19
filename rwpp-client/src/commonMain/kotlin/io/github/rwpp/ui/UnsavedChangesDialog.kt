/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */
package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroPanel
import io.github.rwpp.widget.v2.AnimatedAlertDialog

/** Closing the prompt resumes editing; only an explicit choice may commit or discard. */
@Composable
internal fun UnsavedChangesDialog(
    visible: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onContinue: () -> Unit,
) {
    AnimatedAlertDialog(visible = visible, onDismissRequest = onContinue) { dismiss ->
        AeroPanel(Modifier.widthIn(max = 480.dp).fillMaxWidth(0.94f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(readI18n("rwpp.edit.unsavedTitle"), style = MaterialTheme.typography.titleLarge)
                Text(readI18n("rwpp.edit.unsavedMessage"))
                AeroButton(readI18n("rwpp.edit.saveAndReturn"), onSave, modifier = Modifier.fillMaxWidth())
                AeroButton(
                    readI18n("rwpp.edit.discardAndReturn"), onDiscard,
                    isDanger = true, modifier = Modifier.fillMaxWidth(),
                )
                AeroButton(readI18n("rwpp.edit.continueEditing"), dismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
