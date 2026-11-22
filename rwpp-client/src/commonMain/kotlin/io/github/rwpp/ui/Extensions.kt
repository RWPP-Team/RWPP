/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import io.github.rwml.api.Platform
import io.github.rwml.api.Rwml
import io.github.rwml.api.i18n.Messages
import io.github.rwml.core.ExtensionHandle
import io.github.rwpp.api.extension.ExtensionSetting
import io.github.rwpp.api.extension.ExtensionSettings
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.logging.logger
import io.github.rwpp.api.net.Net
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.extension_30
import io.github.rwpp.generated.logo
import io.github.rwpp.graphics.hudImageFromPixels
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroDropdownMenu
import io.github.rwpp.widget.v2.AeroHeaderWithCloseButton
import io.github.rwpp.widget.v2.AeroPanel
import io.github.rwpp.widget.v2.AeroTextField
import io.github.rwpp.widget.v2.AeroToggleTile
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.annotation.Single

@Single
class ExtensionsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val runtime = Rwml.get()
        val messages = runtime.ui().messages()
        val net = koinInject<Net>()
        val panels by ExtensionSettings.panels.collectAsState()
        val extensions = remember(runtime, messages.language()) {
            runCatching {
                runtime.manager()?.handles().orEmpty()
                    .filter(ExtensionHandle::isEnabled)
                    .sortedBy { it.localizedMetadata(messages, "name", it.descriptor().name()).lowercase() }
            }.onFailure { logger().warn("Could not read loaded RWML extensions", it) }
        }
        var openPanel by remember { mutableStateOf<String?>(null) }

        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            AeroPanel(Modifier.fillMaxSize().padding(12.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { AeroHeaderWithCloseButton("LOADED EXTENSIONS", onDismissRequest = { navigator.pop() }) }
                    val loaded = extensions.getOrNull()
                    when {
                        loaded == null -> item { Text(readI18n("rwpp.extensions.loadFailed"), color = MaterialTheme.colorScheme.error) }
                        loaded.isEmpty() -> item { Text(readI18n("rwpp.extensions.empty")) }
                        else -> items(loaded, key = { it.descriptor().id() }) { extension ->
                            val id = extension.descriptor().id()
                            LoadedExtensionCard(
                                extension,
                                messages,
                                panels[id].orEmpty(),
                                expanded = openPanel == id,
                                showJavaVersion = runtime.platform() == Platform.DESKTOP,
                                onToggle = { openPanel = if (openPanel == id) null else id },
                                openUrl = net::openUriInBrowser,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LoadedExtensionCard(
    extension: ExtensionHandle,
    messages: Messages,
    settings: List<ExtensionSetting<*>>,
    expanded: Boolean,
    showJavaVersion: Boolean,
    onToggle: () -> Unit,
    openUrl: (String) -> Unit,
) {
    val descriptor = extension.descriptor()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val homepage = remember(descriptor.homepage()) { extensionWebLink(descriptor.homepage()) }
    val licenseUrl = remember(descriptor.licenseUrl()) { extensionWebLink(descriptor.licenseUrl()) }
    AeroPanel(Modifier.fillMaxWidth(), borderColor = colors.outlineVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadedExtensionIcon(extension)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        extension.localizedMetadata(messages, "name", descriptor.name()),
                        style = typography.titleMedium, color = colors.onSurface,
                    )
                    Text(descriptor.id(), style = typography.bodySmall, color = colors.onSurfaceVariant)
                    Text(
                        readI18n("rwpp.extension.version", descriptor.version()),
                        style = typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
            }
            val description = extension.localizedMetadata(messages, "description", descriptor.description())
            if (description.isNotBlank()) Text(description, color = colors.onSurface)
            val metadata = listOfNotNull(
                descriptor.authors().takeIf { it.isNotEmpty() }?.let { readI18n("rwpp.extension.author", it.joinToString()) },
                descriptor.dependsOn().takeIf { it.isNotEmpty() }?.let { readI18n("rwpp.extensions.dependencies", it.joinToString()) },
                descriptor.license().takeIf(String::isNotBlank)?.let { readI18n("rwpp.extensions.license", it) },
                descriptor.tags().takeIf { it.isNotEmpty() }?.let { readI18n("rwpp.extensions.tags", it.joinToString()) },
            )
            for (line in metadata) Text(line, style = typography.bodySmall, color = colors.onSurfaceVariant)
            val requirements = listOfNotNull(
                readI18n("rwpp.extensions.apiVersion", descriptor.apiVersion()),
                descriptor.minRwmlVersion().takeIf(String::isNotBlank)?.let { readI18n("rwpp.extensions.minHost", it) },
                readI18n("rwpp.extensions.javaVersion", descriptor.requiredJavaVersion()).takeIf { showJavaVersion },
                readI18n("rwpp.extension.resource").takeIf { descriptor.resourcePack() },
            )
            Text(requirements.joinToString(" · "), style = typography.bodySmall, color = colors.onSurfaceVariant)
            if (homepage != null || licenseUrl != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (homepage != null) {
                        AeroButton(readI18n("rwpp.extensions.homepage"), onClick = { openUrl(homepage) })
                    }
                    if (licenseUrl != null) {
                        AeroButton(readI18n("rwpp.extensions.viewLicense"), onClick = { openUrl(licenseUrl) })
                    }
                }
            }
            if (settings.isNotEmpty()) {
                AeroButton(
                    text = readI18n(if (expanded) "rwpp.extensions.hideSettings" else "rwpp.extensions.settings"),
                    onClick = onToggle,
                )
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        settings.forEach { setting ->
                            key(setting) { ExtensionSettingRow(descriptor.id(), setting) }
                        }
                    }
                }
            }
        }
    }
}

private fun ExtensionHandle.localizedMetadata(messages: Messages, field: String, fallback: String): String {
    val key = "extensions.${descriptor().id()}.$field"
    return messages.text(key).takeUnless { it == key } ?: fallback
}

private fun extensionWebLink(text: String): String? = runCatching { URI(text) }.getOrNull()
    ?.takeIf { (it.scheme.equals("https", true) || it.scheme.equals("http", true)) && it.host != null }
    ?.toString()

@Composable
private fun LoadedExtensionIcon(extension: ExtensionHandle) {
    val descriptor = extension.descriptor()
    val archiveIcon by produceState<ImageBitmap?>(null, extension) {
        if (descriptor.icon().isNotBlank()) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    readExtensionIcon(descriptor, extension.classLoader())?.let { raster ->
                        hudImageFromPixels(raster.pixels, raster.width, raster.height)
                    }
                }.onFailure { logger().warn("Could not read the icon for extension ${descriptor.id()}", it) }
                    .getOrNull()
            }
        }
    }
    val size = Modifier.size(44.dp)
    val bitmap = archiveIcon
    when {
        bitmap != null -> Image(bitmap, contentDescription = null, modifier = size)
        descriptor.id() == "io.github.rwpp" || descriptor.id() == "io.github.rwpp.api" ->
            Image(painterResource(Res.drawable.logo), contentDescription = null, modifier = size)
        else -> Icon(
            painterResource(Res.drawable.extension_30),
            contentDescription = null, modifier = size, tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ExtensionSettingRow(extensionId: String, setting: ExtensionSetting<*>) {
    when (setting) {
        is ExtensionSetting.Toggle -> ExtensionSettingValue(extensionId, setting) { value, save ->
            AeroToggleTile(setting.label, checked = value, onCheckedChange = save, modifier = Modifier.fillMaxWidth())
        }
        is ExtensionSetting.Choice -> ExtensionSettingValue(extensionId, setting) { value, save ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(setting.label, color = MaterialTheme.colorScheme.onSurface)
                AeroDropdownMenu(
                    items = setting.options,
                    selectedIndex = value,
                    onItemSelected = save,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is ExtensionSetting.Text -> ExtensionSettingValue(extensionId, setting) { value, save ->
            var draft by remember(value) { mutableStateOf(value) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(setting.label, color = MaterialTheme.colorScheme.onSurface)
                AeroTextField(value = draft, placeholder = "", onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth())
                if (draft != value) {
                    AeroButton(text = readI18n("rwpp.extensions.applySetting"), onClick = { save(draft) })
                }
            }
        }
    }
}

@Composable
private fun <T : Any> ExtensionSettingValue(
    extensionId: String,
    setting: ExtensionSetting<T>,
    content: @Composable (T, (T) -> Unit) -> Unit,
) {
    var value by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(setting) {
        try {
            value = setting.read()
        } catch (failure: Exception) {
            logger().warn("Could not read setting ${setting.label} for ${extensionId}", failure)
            error = "rwpp.extensions.readSettingFailed"
        }
    }
    value?.let { current ->
        content(current) { requested ->
            try {
                setting.write(requested)
                value = setting.read()
                error = null
            } catch (failure: Exception) {
                logger().warn("Could not apply setting ${setting.label} for ${extensionId}", failure)
                error = "rwpp.extensions.applySettingFailed"
            }
        }
    }
    error?.let { Text(readI18n(it, setting.label), color = MaterialTheme.colorScheme.error) }
}
