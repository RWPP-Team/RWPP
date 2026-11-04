/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.i18n.readI18n

@Composable
fun BoxScope.ExitButton(onClick: () -> Unit) {
    Button(
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                0.8f
            )
        ),
        modifier = Modifier.size(30.dp).align(Alignment.TopEnd),
        contentPadding = PaddingValues(0.dp),
        onClick = { onClick() },
    ) {
        Icon(
            Icons.Default.Close,
            tint = MaterialTheme.colorScheme.surfaceTint,
            contentDescription = readI18n("rwpp.common.close")
        )
    }
}
