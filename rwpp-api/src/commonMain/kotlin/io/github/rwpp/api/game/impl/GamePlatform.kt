/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.files.FileLoader
import com.corrodinggames.rts.gameFramework.localization.LocaleEngine
import com.corrodinggames.rts.gameFramework.network.ConnectSocketToServerRunnable
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.ApiRuntime
import io.github.rwml.api.Rwml
import io.github.rwpp.api.game.LoadingContext
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.MapNames
import io.github.rwpp.api.game.map.Mission
import io.github.rwpp.api.game.map.MissionType
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.api.game.map.normalizeGameMapPath
import io.github.rwml.api.ui.GameUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

/** Shared game behavior; only native asset access and screen entry points vary by target. */
internal object GamePlatform {
    private const val DEFAULT_SKIRMISH_MAP = "maps/skirmish/[z;p10]Crossing Large (10p).tmx"
    private const val CUSTOM_MAP_DIRECTORY = "/SD/rusted_warfare_maps"
    private const val REPLAY_DIRECTORY = "/SD/rustedWarfare/replays/"
    private val replayDatePattern = Regex(".*\\((.*)\\).*")
    private val replayDateFormats = listOf(
        "d MMM yyyy HH.mm.ss",
        "d MMM yyyy HH:mm:ss",
        "d MMM yyyy HH_mm_ss",
        "d MMM yyyy HH-mm-ss",
        "d MMM. yyyy HH.mm.ss",
    )

    val generatedMapDirectory: String get() = GamePlatformHost.generatedMapDirectory

    private val connectionAttempt = AtomicReference<ConnectSocketToServerRunnable?>()
    private val connectionResult = AtomicReference<CompletableDeferred<Result<String>>?>()
    private val connectionCancelled = AtomicBoolean(false)

    fun initializeMap(force: Boolean = false) {
        val network = GameEngine.getInstance().networkEngine
        val setup = network.gameSetup
        if (network.currentGameMapPath == null || setup.mapName == null || force) {
            setup.mapType = GameSetupMapType.skirmishMap
        }
        if (network.currentGameMapPath == null || force) {
            network.currentGameMapPath = DEFAULT_SKIRMISH_MAP
        }
        if (setup.mapName == null || force) {
            setup.mapName = DEFAULT_SKIRMISH_MAP.substringAfter("maps/skirmish/")
        }
    }

    fun loadMission(path: String, sandbox: Boolean) = loadWithNativeSelector(path, sandbox)

    fun startSinglePlayer(path: String, sandbox: Boolean) = loadWithNativeSelector(path, sandbox)

    private fun loadWithNativeSelector(path: String, sandbox: Boolean) {
        val engine = GameEngine.getInstance()
        val skirmish = NativeMapLoader.isSkirmishMap(path)
        NativeMapLoader.loadMapWithAI(path, skirmish, 1, 0, true, false)
        engine.debugEditorMode = sandbox
        if (sandbox) {
            engine.map.fogOfWarEnabled = false
            engine.interfaceEngine.clearUnitSelection()
        }
    }

    suspend fun directJoinServer(
        address: String,
        uuid: String?,
        context: LoadingContext,
    ): Result<String> {
        val engine = GameEngine.getInstance()
        val network = engine.networkEngine
        if (uuid == null) engine.settings.lastNetworkIP = restrictedString(address.trim())
        network.currentServerId = uuid
        context.message("Connecting…")
        connectionCancelled.set(false)

        val result = CompletableDeferred<Result<String>>()
        connectionResult.set(result)
        val attemptRef = AtomicReference<ConnectSocketToServerRunnable?>()
        val callbackArrivedEarly = AtomicBoolean(false)
        val callbackHandled = AtomicBoolean(false)

        fun finish(attempt: ConnectSocketToServerRunnable) {
            if (!callbackHandled.compareAndSet(false, true)) return
            val socket = attempt.connectedSocket
            val outcome = when {
                socket == null -> Result.failure(IOException("Connection failed: target server may be unreachable."))
                else -> runCatching {
                    if (network.startClientConnection(socket)) "" else throw IOException("Connection failed.")
                }
            }
            result.complete(outcome)
        }

        val attempt = network.connectToServerAsync(address.trim(), true) {
            val current = attemptRef.get()
            if (current == null) callbackArrivedEarly.set(true) else finish(current)
        }
        attemptRef.set(attempt)
        connectionAttempt.set(attempt)
        if (callbackArrivedEarly.get()) finish(attempt)
        result.invokeOnCompletion { failure ->
            if (failure != null) attempt.cancel()
        }

        val outcome = try {
            result.await()
        } catch (cancelled: CancellationException) {
            attempt.cancel()
            throw cancelled
        } finally {
            connectionAttempt.compareAndSet(attempt, null)
            connectionResult.compareAndSet(result, null)
        }
        if (connectionCancelled.get()) return Result.failure(IOException("Connection cancelled."))
        return outcome
    }

    fun cancelJoinServer() {
        connectionCancelled.set(true)
        connectionAttempt.getAndSet(null)?.cancel()
        connectionResult.getAndSet(null)?.complete(Result.failure(IOException("Connection cancelled.")))
    }

    fun getAllMissions(types: List<MissionType>): List<Mission> = buildList {
        for (type in types) {
            val directory = "maps/${type.pathName()}"
            val missionFiles = listGameAssetsRecursively(directory)
                .filter { it.endsWith(".tmx", ignoreCase = true) }
            missionFiles.forEachIndexed { index, path ->
                val fileName = path.substringAfterLast('/')
                val mapName = path.removePrefix("$directory/").removeSuffixIgnoreCase(".tmx")
                val missionName = if (type == MissionType.Normal && "__-__" in mapName) {
                    mapName.substringAfter("__-__")
                } else mapName
                add(
                    Mission(
                        id = index,
                        name = missionName,
                        type = type,
                        mapName = mapName,
                        mapType = GameSetupMapType.skirmishMap,
                        inputStreamProvider = { openGameAsset(path) },
                        displayNameProvider = { localizedMapName(fileName) },
                        enginePath = path,
                    ),
                )
            }
        }
    }

    fun getAllMaps(): Map<GameSetupMapType, List<GameMap>> {
        val engine = GameEngine.getInstance()
        val skirmishMaps = listGameAssetsRecursively("maps/skirmish")
            .filter { it.endsWith(".tmx", ignoreCase = true) }
            .mapIndexed { index, path ->
                val fileName = path.substringAfterLast('/')
                GameMap(
                    id = index,
                    mapName = path.removePrefix("maps/skirmish/").removeSuffixIgnoreCase(".tmx"),
                    mapType = GameSetupMapType.skirmishMap,
                    inputStreamProvider = { openGameAsset(path) },
                    displayNameProvider = { localizedMapName(fileName) },
                    enginePath = path,
                )
            }

        val customDirectory = CUSTOM_MAP_DIRECTORY
        val customNames = runCatching { FileLoader.listDir(customDirectory, true).orEmpty() }
            .getOrDefault(emptyArray())
        val extraMaps = engine.modManager.addExtraMapsForPath(customNames, customDirectory).orEmpty()
        val customMaps = extraMaps.mapIndexedNotNull { index, fileName ->
            if (!fileName.endsWith(".tmx", ignoreCase = true)) return@mapIndexedNotNull null
            val name = fileName.substringAfterLast('/').removeSuffixIgnoreCase(".tmx")
            // The native selector also prefixes MOD| and NEW_PATH| entries with the custom-map
            // directory. FileLoader resolves those virtual path tags after the directory prefix.
            val path = normalizeGameMapPath(
                if (fileName.startsWith('/')) fileName else "$customDirectory/$fileName",
            )
            GameMap(
                id = index,
                mapName = fileName.removeSuffixIgnoreCase(".tmx"),
                mapType = GameSetupMapType.customMap,
                inputStreamProvider = { FileLoader.openByPath(path) ?: throw FileNotFoundException(path) },
                displayNameProvider = { localizedMapName(name) },
                enginePath = path,
            )
        }

        val savesDirectory = GamePlatformHost.savedGameDirectory
        val saves = runCatching { FileLoader.listDir(savesDirectory, true).orEmpty().toList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".rwsave", ignoreCase = true) }
            .mapIndexed { index, fileName ->
                val path = normalizeGameMapPath(
                    if (fileName.startsWith('/')) fileName else "$savesDirectory/$fileName",
                )
                val mapName = relativeMapPath(fileName, savesDirectory)
                    .removeSuffixIgnoreCase(".rwsave")
                val displayName = mapName.substringAfterLast('/')
                GameMap(
                    id = index,
                    mapName = mapName,
                    mapType = GameSetupMapType.savedGame,
                    inputStreamProvider = { FileLoader.openByPath(path) ?: throw FileNotFoundException(path) },
                    displayNameProvider = { localizedMapName(displayName) },
                    enginePath = path,
                )
            }

        return linkedMapOf(
            GameSetupMapType.skirmishMap to skirmishMaps,
            GameSetupMapType.customMap to customMaps,
            GameSetupMapType.savedGame to saves,
        )
    }

    fun getAllReplays(): List<Replay> {
        val replayFiles = FileLoader.listDir(REPLAY_DIRECTORY)
        if (replayFiles == null) {
            GameEngine.log("failed to find replay folder")
            return emptyList()
        }
        return replayFiles.asSequence()
            .filterNot { it.endsWith(".map") }
            .sortedWith(Comparator(::compareReplayNames))
            .mapIndexed { index, name -> Replay(index, name) { localizedMapName(name) } }
            .toList()
    }

    private fun compareReplayNames(first: String, second: String): Int {
        val firstDate = replayDate(first)
        val secondDate = replayDate(second)
        return when {
            firstDate == null && secondDate == null -> first.compareTo(second)
            firstDate != null && secondDate != null -> secondDate.compareTo(firstDate)
            firstDate == null -> -1
            else -> 1
        }
    }

    private fun replayDate(name: String): Date? {
        val value = replayDatePattern.matchEntire(name)?.groupValues?.getOrNull(1) ?: return null
        for (format in replayDateFormats) {
            val parsed = runCatching { SimpleDateFormat(format, Locale.ENGLISH).parse(value) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun openGameScreen() = GamePlatformHost.openGameScreen()

    fun closeGameScreen() = GamePlatformHost.closeGameScreen()

    fun continueGame() = GamePlatformHost.continueGame()

    private fun listGameAssetsRecursively(directory: String): List<String> =
        GamePlatformHost.listGameAssetDirectory(directory).sorted().flatMap { name ->
            val path = "$directory/$name"
            if (name.endsWith(".tmx", ignoreCase = true)) listOf(path) else listGameAssetsRecursively(path)
        }

    private fun openGameAsset(path: String): InputStream = runCatching {
        ApiRuntime.extensionContext.gameAssets().open(path)
    }.getOrElse {
        GamePlatformHost.openGameAsset(path)
    }

    private fun localizedMapName(path: String): String {
        val plainName = MapNames.getPrettyMapName(path) ?: path
        val hostText = runCatching { Rwml.get()?.ui()?.find(GameUi::class.java)?.mapName(plainName) }.getOrNull()
        return hostText?.takeIf(String::isNotBlank) ?: runCatching { LocaleEngine.b(plainName) }.getOrDefault(plainName)
    }

    private fun restrictedString(value: String): String = value
        .replace("'", ".")
        .replace('"', '.')
        .replace('(', '.')
        .replace(')', '.')
        .replace(',', '.')
        .replace('<', '.')
        .replace('>', '.')

    private fun relativeMapPath(path: String, directory: String): String {
        val normalizedPath = normalizeGameMapPath(path)
        val normalizedDirectory = normalizeGameMapPath(directory)
        val prefix = "$normalizedDirectory/"
        return if (normalizedPath.startsWith(prefix, ignoreCase = true)) {
            normalizedPath.drop(prefix.length)
        } else {
            normalizedPath.trimStart('/')
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

}

/** Narrow platform boundary for paths, asset access and native engine screens. */
internal expect object GamePlatformHost {
    val generatedMapDirectory: String
    val savedGameDirectory: String

    fun listGameAssetDirectory(path: String): List<String>

    fun openGameAsset(path: String): InputStream

    fun openGameScreen()

    fun closeGameScreen()

    fun continueGame()
}
