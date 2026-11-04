/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.android.impl

import android.os.Environment
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.AppContext
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.impl.BaseAppContextImpl
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

@Single([AppContext::class])
class AppContextImpl : BaseAppContextImpl() {
    private val exitActions = mutableListOf<() -> Unit>()
    private val fileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
        onExit { fileScope.cancel() }
    }

    override fun onExit(action: () -> Unit) {
        exitActions.add(action)
    }

    override fun isAndroid(): Boolean = true

    override fun isDesktop(): Boolean = false

    override fun openFileChooser(onChooseFile: (java.io.File) -> Unit) {
        val activity = get<android.app.Activity>()
        activity.runOnUiThread {
            val owner = activity as? ActivityResultRegistryOwner
            if (owner == null) {
                io.github.rwpp.ui.UI.showWarning(io.github.rwpp.api.i18n.readI18n("rwpp.files.pickerUnavailable"))
                return@runOnUiThread
            }
            lateinit var launcher: ActivityResultLauncher<Array<String>>
            launcher = owner.activityResultRegistry.register(
                "rwpp:file:${java.util.UUID.randomUUID()}",
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                launcher.unregister()
                if (uri != null) fileScope.launch {
                    val context = activity.applicationContext
                    var directory: java.io.File? = null
                    var staged: java.io.File? = null
                    var complete = false
                    try {
                        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "selected-file"
                        context.contentResolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
                        }
                        name = java.io.File(name).name.takeUnless { it.isBlank() || it == "." || it == ".." }
                            ?: "selected-file"
                        directory = java.io.File(context.filesDir, "rwpp/imports/${java.util.UUID.randomUUID()}")
                        check(directory.mkdirs()) { "Cannot prepare file import" }
                        val file = java.io.File(directory, name)
                        staged = file
                        context.contentResolver.openInputStream(uri).use { input ->
                            checkNotNull(input) { "The document provider could not open the file" }
                            java.io.FileOutputStream(file).use { output -> input.copyTo(output); output.fd.sync() }
                        }
                        withContext(Dispatchers.Main) { onChooseFile(file) }
                        complete = true
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        withContext(Dispatchers.Main) {
                            io.github.rwpp.ui.UI.showWarning(
                                io.github.rwpp.api.i18n.readI18n(
                                    "rwpp.files.importFailed",
                                    failure.message.orEmpty()
                                )
                            )
                        }
                    } finally {
                        if (!complete) {
                            staged?.delete(); directory?.delete()
                        }
                    }
                }
            }
            try {
                launcher.launch(arrayOf("*/*"))
            } catch (failure: android.content.ActivityNotFoundException) {
                launcher.unregister()
                io.github.rwpp.ui.UI.showWarning(io.github.rwpp.api.i18n.readI18n("rwpp.files.pickerUnavailable"))
            }
        }
    }

    override fun externalStoragePath(path: String): String {
        return Environment.getExternalStorageDirectory().absolutePath + "/rustedWarfare/$path"
    }

    override fun shareFile(
        name: String,
        sourcePath: String,
        openSource: () -> java.io.InputStream,
        onResult: (Result<Unit>) -> Unit
    ) {
        val activity = get<android.app.Activity>()
        fileScope.launch {
            val temp = try {
                java.io.File.createTempFile("rwpp-replay-", ".tmp", activity.cacheDir).also { staged ->
                    try {
                        openSource().use { input -> staged.outputStream().use { input.copyTo(it) } }
                    } catch (failure: Exception) {
                        staged.delete(); throw failure
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                withContext(Dispatchers.Main) { onResult(Result.failure(failure)) }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val owner = activity as? ActivityResultRegistryOwner
                if (owner == null) {
                    temp.delete()
                    onResult(Result.failure(IllegalStateException("Document picker is unavailable")))
                    return@withContext
                }
                lateinit var launcher: ActivityResultLauncher<String>
                launcher = owner.activityResultRegistry.register(
                    "rwpp:export:${java.util.UUID.randomUUID()}",
                    ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri ->
                    launcher.unregister()
                    if (uri == null) temp.delete() else fileScope.launch {
                        val result = runCatching {
                            try {
                                val output = checkNotNull(
                                    activity.contentResolver.openOutputStream(
                                        uri,
                                        "wt"
                                    )
                                ) { "Cannot write selected document" }
                                temp.inputStream().use { input -> output.use { input.copyTo(it) } }
                                Unit
                            } finally {
                                temp.delete()
                            }
                        }
                        withContext(Dispatchers.Main) {
                            onResult(result)
                            if (result.isSuccess) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    clipData = android.content.ClipData.newRawUri(name, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    activity.startActivity(
                                        android.content.Intent.createChooser(
                                            intent,
                                            null
                                        )
                                    )
                                }
                                    .onFailure { onResult(Result.failure(it)) }
                            }
                        }
                    }
                }
                try {
                    launcher.launch(name)
                } catch (failure: Exception) {
                    launcher.unregister()
                    temp.delete()
                    onResult(Result.failure(failure))
                }
            }
        }
    }

    override fun exit() {
        try {
            val engine = runCatching { GameEngine.getInstance() }.getOrNull()
            runCatching { get<ConfigIO>().saveAllConfig() }.onFailure {
                io.github.rwpp.api.logging.logger().error("Could not save client configuration before exit", it)
            }
            runCatching {
                engine?.settings?.apply {
                    numLoadsSinceRunningGameOrNormalExit = 0
                    numIncompleteLoadAttempts = 0
                    save()
                }
            }
            exitActions.toList().forEach { action -> runCatching(action) }
        } finally {
            exitProcess(0)
        }
    }
}
