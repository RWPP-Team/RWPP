/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.game.units.custom.CustomUnitMetadataLoader
import com.corrodinggames.rts.gameFramework.files.FileLoader
import com.corrodinggames.rts.gameFramework.mod.Mod
import com.corrodinggames.rts.gameFramework.utility.FileReaderManager
import io.github.rwpp.api.i18n.readI18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

internal expect fun reloadNativeMods(engine: GameEngine)

internal expect suspend fun <T> runNativeModWork(reloadData: Boolean = false, action: () -> T): T
internal expect fun presentNativeModProgress()

private val modOperation = Mutex()

internal suspend fun scanMods(): List<Mod> = modOperation.withLock {
    runNativeModWork { GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>() }
}

internal suspend fun importRwmod(file: File): List<Mod> = modOperation.withLock {
    require(file.extension.equals("rwmod", ignoreCase = true)) { readI18n("rwpp.mod.loadInfo") }
    val archiveName = "${file.nameWithoutExtension}.rwmod"
    val directory = runNativeModWork {
        FileLoader.convertAbstractPath(CustomUnitMetadataLoader.getModsUnitsDirectory())
    }
    val destination = File(directory, archiveName).canonicalFile
    val copied = destination != file.canonicalFile
    if (copied) withContext(Dispatchers.IO) {
        val mount = FileReaderManager.getMountFileReader(directory)
        if (mount == null) {
            require(!destination.exists()) { readI18n("rwpp.mod.fileExists", archiveName) }
            installRwmod(file, File(directory))
        } else {
            val target = "$directory/$archiveName"
            require(!mount.fileExists(target)) { readI18n("rwpp.mod.fileExists", archiveName) }
            val staging = "$directory/.rwpp-mod-${java.util.UUID.randomUUID()}.part"
            check(mount.isDirectory(directory) || mount.createDirectory(directory)) { directory }
            try {
                file.inputStream().use { input ->
                    checkNotNull(mount.writableOutputSteam(staging, false)).use { input.copyTo(it, 64 * 1024) }
                }
                check(mount.rename(staging, target)) { target }
            } finally {
                if (mount.fileExists(staging)) mount.deleteFile(staging)
                mount.invalidate()
            }
        }
    }
    runNativeModWork {
        val engine = GameEngine.getInstance()
        val manager = engine.modManager
        val mods = manager.refreshModList().filterIsInstance<Mod>()
        // Android's dual backend adds a storage tag before hashing entries. The resolved
        // path identifies the archive on both platforms, even if names are duplicated.
        val imported = checkNotNull(mods.firstOrNull { mod ->
            mod.getSourcePathRaw()?.let { File(it).canonicalFile == destination } == true
        }) { readI18n("rwpp.mod.importNotFound", archiveName) }
        if (copied) {
            imported.disabled = true
            manager.saveSelection()
            check(engine.settings.save()) { "Could not save mod selection" }
        }
        mods
    }
}

/** Publish only a fully copied archive. The native ZIP reader validates its contents. */
internal fun installRwmod(file: File, directory: File): File {
    Files.createDirectories(directory.toPath())
    val target = File(directory, "${file.nameWithoutExtension}.rwmod")
    if (file.canonicalFile == target.canonicalFile) return target
    val temporary = Files.createTempFile(directory.toPath(), ".rwpp-mod-", ".part")
    try {
        file.inputStream().use { input ->
            Files.newOutputStream(temporary).use { output -> input.copyTo(output, 64 * 1024) }
        }
        Files.move(temporary, target.toPath())
        return target
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Reload selected mods without committing a potentially unbootable selection beforehand. */
suspend fun reloadMods() {
    applyModSelection(reloadData = true)
}

internal suspend fun applyModSelection(reloadData: Boolean): List<Mod> = modOperation.withLock {
    runNativeModWork(reloadData) {
        val engine = GameEngine.getInstance()
        val manager = engine.modManager
        val settings = engine.settings
        val previous = settings.modSettings
        val previousVersion = settings.modSettingsVersion
        val progress = NativeModLoadProgress()
        try {
            if (reloadData) progress.run {
                reloadNativeMods(engine)
            }
        } catch (failure: OutOfMemoryError) {
            settings.modSettings = previous
            settings.modSettingsVersion = previousVersion
            manager.loadSelection()
            settings.save()
            throw ModMemoryLoadFailure(readI18n("rwpp.mod.memoryExhausted"), failure)
        } catch (failure: Exception) {
            settings.modSettings = previous
            settings.modSettingsVersion = previousVersion
            manager.loadSelection()
            settings.save()
            throw failure
        }
        manager.saveSelection()
        check(settings.save()) { "Could not save mod selection" }
        if (progress.failedMods.isNotEmpty()) {
            throw ModMemoryLoadFailure(readI18n("rwpp.mod.memorySkipped", progress.failedMods.joinToString(", ")))
        }
        manager.refreshModList().filterIsInstance<Mod>()
    }
}
