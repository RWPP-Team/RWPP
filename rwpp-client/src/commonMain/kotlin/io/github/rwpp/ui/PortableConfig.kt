/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.widget.v2.AeroButton
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

private const val MAX_PORTABLE_CONFIG_BYTES = 1024 * 1024
@Serializable
private data class PortableConfigFile<T>(val version: Int, val kind: String, val data: T)

@Composable
internal fun <T> PortableConfigActions(
    name: String,
    serializer: KSerializer<T>,
    snapshot: () -> T,
    apply: (T) -> Unit,
    save: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val fileSerializer = remember(serializer) { PortableConfigFile.serializer(serializer) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = remember { appKoin.get<AppContext>() }
    fun report(result: Result<Unit>) {
        status = if (result.isSuccess) readI18n("rwpp.portable.success")
        else readI18n("rwpp.portable.failure", result.exceptionOrNull()?.message.orEmpty())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (save != null) AeroButton(readI18n("rwpp.common.save"), { report(runCatching(save)) }, modifier = Modifier.weight(1f))
        AeroButton(readI18n("rwpp.portable.export"), {
            runCatching {
                Toml.encodeToString(fileSerializer, PortableConfigFile(1, name, snapshot())).encodeToByteArray().also {
                    require(it.size <= MAX_PORTABLE_CONFIG_BYTES)
                }
            }.onSuccess { bytes ->
                context.shareFile(name, "", { ByteArrayInputStream(bytes) }) { result ->
                    scope.launch { report(result) }
                }
            }.onFailure { report(Result.failure(it)) }
        }, modifier = Modifier.weight(1f))
        AeroButton(readI18n("rwpp.portable.import"), {
            context.openFileChooser { file ->
                val imported = runCatching {
                    val bytes = file.inputStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_PORTABLE_CONFIG_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val decoded = Toml.decodeFromString(fileSerializer, bytes.decodeToString(throwOnInvalidSequence = true))
                    require(decoded.version == 1 && decoded.kind == name)
                    decoded.data
                }
                scope.launch { report(imported.mapCatching(apply)) }
            }
        }, modifier = Modifier.weight(1f))
    }
    status?.let { Text(it) }
}
