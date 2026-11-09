/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroSettingGroup
import io.github.rwpp.widget.v2.AeroTextField
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

internal expect fun launchCommunityDlss5(controllerDirectory: String): String
internal expect fun resolveCommunityDlss5Directory(controllerDirectory: String): String
internal expect fun enableCommunityDlss5(controllerDirectory: String): String
internal data class CommunityDlss5NeuralStatus(
    val state: Int, val api: Int, val active: Boolean,
    val neuralFrames: Long, val processedFrames: Long, val gpuMs: Float, val failureStage: Int, val message: String
)

internal data class CommunityDlss5Status(
    val controllerPids: List<Long> = emptyList(),
    val executable: String = "",
    val gamePid: Long = 0,
    val runtimePresent: Boolean = false,
    val checkedAt: String = "",
    val errorKey: String? = null,
    val errorArguments: List<String> = emptyList(),
    val diagnostic: String? = null,
    val neural: CommunityDlss5NeuralStatus? = null,
)

internal expect fun queryCommunityDlss5Status(controllerDirectory: String): CommunityDlss5Status
internal expect fun closeCommunityDlss5(controllerDirectory: String): String

/** Visible only in settings/pause, so process checks never run on the game render thread. */
@Composable
internal fun CommunityDlss5StatusControls(controllerDirectory: String) {
    val scope = rememberCoroutineScope()
    var status by remember(controllerDirectory) { mutableStateOf<CommunityDlss5Status?>(null) }
    var closing by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controllerDirectory) {
        while (isActive) {
            status = withContext(Dispatchers.IO) { queryCommunityDlss5Status(controllerDirectory) }
            delay(3000.milliseconds)
        }
    }
    val observed = status
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            readI18n(
                when {
                    observed == null -> "rwpp.dlss5.monitor.checking"
                    observed.errorKey != null -> observed.errorKey
                    observed.controllerPids.isNotEmpty() -> "rwpp.dlss5.monitor.running"
                    else -> "rwpp.dlss5.monitor.stopped"
                }, *observed?.errorArguments.orEmpty().toTypedArray()
            ), color = when {
                observed?.errorKey != null -> MaterialTheme.colorScheme.error
                observed?.controllerPids?.isNotEmpty() == true -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (observed != null && observed.errorKey == null) {
            if (observed.controllerPids.isNotEmpty()) {
                Text(
                    readI18n("rwpp.dlss5.monitor.process", observed.controllerPids.joinToString(), observed.executable),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                readI18n("rwpp.dlss5.monitor.game", observed.gamePid.toString()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface
            )
            val neural = observed.neural
            Text(
                when {
                    neural?.active == true -> readI18n(
                        "rwpp.dlss5.neural.active",
                        neural.neuralFrames.toString(),
                        neural.gpuMs.toString()
                    )

                    neural != null && (neural.state == 5 || neural.failureStage >= 100) -> readI18n(
                        "rwpp.dlss5.neural.failed",
                        neural.failureStage.toString(),
                        neural.message
                    )

                    neural?.state == 4 -> readI18n("rwpp.dlss5.neural.paused", neural.message)
                    neural != null -> readI18n("rwpp.dlss5.neural.initializing", neural.message)
                    observed.runtimePresent -> readI18n("rwpp.dlss5.neural.notAttached")
                    else -> readI18n("rwpp.dlss5.monitor.missingRuntime")
                }, style = MaterialTheme.typography.bodySmall, color = when {
                    neural?.active == true -> MaterialTheme.colorScheme.primary
                    neural != null && (neural.state == 5 || neural.failureStage >= 100) -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                readI18n("rwpp.dlss5.monitor.checkedAt", observed.checkedAt),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (observed.runtimePresent && neural?.active != true) AeroButton(
                text = readI18n(if (enabling) "rwpp.dlss5.neural.enabling" else "rwpp.dlss5.neural.enable"),
                onClick = {
                    if (!enabling && !closing) {
                        enabling = true
                        scope.launch {
                            try {
                                result = withContext(Dispatchers.IO) { enableCommunityDlss5(controllerDirectory) }
                                status = withContext(Dispatchers.IO) { queryCommunityDlss5Status(controllerDirectory) }
                            } finally {
                                enabling = false
                            }
                        }
                    }
                },
            )
            if (observed.controllerPids.isNotEmpty()) AeroButton(
                text = readI18n(if (closing) "rwpp.dlss5.monitor.closing" else "rwpp.dlss5.monitor.close"),
                isDanger = true,
                onClick = {
                    if (!closing && !enabling) {
                        closing = true
                        scope.launch {
                            try {
                                result = withContext(Dispatchers.IO) { closeCommunityDlss5(controllerDirectory) }
                                status = withContext(Dispatchers.IO) { queryCommunityDlss5Status(controllerDirectory) }
                            } finally {
                                closing = false
                            }
                        }
                    }
                },
            )
        }
        observed?.diagnostic?.let {
            Text(
                readI18n("rwpp.dlss5.monitor.failureDetail", it),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface
            )
        }
        result?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun CommunityDlss5Settings(settings: Settings) {
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(settings.communityDlss5Directory) }
    var launching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val original = directory
        val resolved = withContext(Dispatchers.IO) { resolveCommunityDlss5Directory(original) }
        if (directory == original && resolved != original) {
            directory = resolved
            settings.communityDlss5Directory = resolved
        }
    }
    AeroSettingGroup(title = "COMMUNITY DLSS 5 (EXTERNAL CONTROLLER)", tag = "EXPERIMENTAL") {
        Text(
            readI18n("rwpp.dlss5.settings.description"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        AeroTextField(
            value = directory, onValueChange = { directory = it; settings.communityDlss5Directory = it; result = null },
            placeholder = readI18n("rwpp.dlss5.settings.directory"),
            modifier = Modifier.fillMaxWidth()
        )
        AeroButton(
            text = if (launching) readI18n("rwpp.dlss5.settings.opening")
            else readI18n("rwpp.dlss5.settings.open"), onClick = {
                if (!launching) {
                    launching = true
                    val selectedDirectory = directory
                    scope.launch {
                        try {
                            result = withContext(Dispatchers.IO) { launchCommunityDlss5(selectedDirectory) }
                        } finally {
                            launching = false
                        }
                    }
                }
            })
        CommunityDlss5StatusControls(directory)
        result?.let {
            Text(
                readI18n("rwpp.dlss5.monitor.lastAction", it),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
