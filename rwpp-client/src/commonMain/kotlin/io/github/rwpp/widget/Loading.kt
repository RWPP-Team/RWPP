/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.game.LoadingContext
import io.github.rwpp.widget.v2.AnimatedAlertDialog
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

var isLoading by mutableStateOf(true)
var loadingMessage by mutableStateOf("")

@Composable
fun LoadingView(
    visible: Boolean,
    onLoaded: () -> Unit,
    enableAnimation: Boolean = true,
    cancellable: Boolean = false,
    loadContent: suspend LoadingContext.() -> Boolean?
) {

    val scope = rememberCoroutineScope()

    AnimatedAlertDialog(
        visible,
        onDismissRequest = onLoaded,
        enableDismiss = cancellable
    ) { dismiss ->
        var cancel by remember { mutableStateOf(false) }

        BorderCard(
            modifier = Modifier.size(500.dp),
        ) {
            LaunchedEffect(Unit) {
                scope.launch(Dispatchers.IO) {
                    val result = loadContent(LoadingContext { loadingMessage = it })
                    if (result == true) {
                        loadingMessage = ""
                        dismiss()
                    } else if (result == false) {
                        cancel = true
                    }
                }
            }

            Box {
                if (cancellable || cancel) ExitButton(dismiss)

                Column(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (enableAnimation && !cancel) LineSpinFadeLoaderIndicator(MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        loadingMessage,
                        modifier = Modifier.padding(20.dp).offset(y = 50.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}