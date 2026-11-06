/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.files.FileLoader
import com.corrodinggames.rts.gameFramework.network.ConnectSocketToServerRunnable
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.ApiRuntime
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.CatalogPage
import io.github.rwpp.api.game.LoadingContext
import io.github.rwpp.api.game.RoomJoinTimeoutException
import io.github.rwpp.api.game.map.*
import io.github.rwpp.api.net.impl.unwrapNativeInvocationFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens an abstract game path the way the engine itself resolves it. Custom maps live under
 * `/SD/rusted_warfare_maps`, which only `convertAbstractPath` maps to the real directory;
 * `openByPath` alone falls through to the asset stream and finds nothing. Mod archives and
 * built-in assets keep working through the first attempt.
 */
fun openGamePath(path: String): InputStream? {
    // Each backend is isolated: a virtual /SD path makes openByPath throw on some
    // hosts, and that failure must not skip the converted filesystem lookup.
    runCatching { FileLoader.openByPath(path) }.getOrNull()?.let { return it }
    val converted = runCatching { FileLoader.convertAbstractPath(path) }.getOrNull()
    if (!converted.isNullOrBlank() && converted != path) {
        runCatching {
            val direct = File(converted)
            if (direct.isFile) direct.inputStream() else null
        }.getOrNull()?.let { return it }
        val root = System.getProperty("rwpp.gameRoot")?.takeIf(String::isNotBlank)
            ?: System.getProperty("user.dir")?.takeIf(String::isNotBlank)
        if (root != null) {
            runCatching {
                val rooted = File(root, converted)
                if (rooted.isFile) rooted.inputStream() else null
            }.getOrNull()?.let { return it }
        }
    }
    return runCatching { FileLoader.openAsset(path) }.getOrNull()
}

/**
 * `MOD|<modHash>/<relativePath>` entries come from ModManager. The engine rewrites the tag while
 * resolving a virtual path, but the rewrite only happens on paths it recognises, so a mod map or
 * its preview image can be unreachable. This repeats that rewrite with the mod's own source path.
 */
fun openGamePathWithModTag(path: String): InputStream? {
    val normalized = path.replace('\\', '/')
    val start = normalized.indexOf("MOD|")
    if (start < 0) return null
    val tagged = normalized.substring(start + MOD_TAG.length)
    val separator = tagged.indexOf('/')
    if (separator <= 0) return null
    val hash = tagged.substring(0, separator)
    val relative = tagged.substring(separator + 1)
    if (relative.isBlank()) return null
    val source = runCatching {
        GameEngine.getInstance().modManager.getModByHash(hash)?.getSourcePath()
    }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
    val joined = if (source.endsWith("/") || source.endsWith("\\")) source + relative else "$source/$relative"
    return openGamePath(joined)
}

private const val MOD_TAG = "MOD|"

/**
 * Runs native socket completion work through the same frame-owned queue as the game's own
 * Root.joinServer callback. The connector callback runs on its socket worker and must not mutate
 * NetworkEngine there.
 */
internal fun dispatchNativeSocketCompletion(
    scope: CoroutineScope = GameScope,
    dispatcher: CoroutineDispatcher = GameMainDispatcher,
    completion: () -> Unit,
) {
    scope.launch(dispatcher) { completion() }
}

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

    private const val ROOM_JOIN_TIMEOUT_MS = 25_000L
    private val connectionAttemptLock = Any()
    private var connectionAttempt: ClientJoinAttempt? = null

    internal class ClientJoinAttempt(
        val roomTicket: NativeGameLifecycle.ClientRoomJoinTicket,
    ) {
        val callbackLock = Any()
        val socketResult = CompletableDeferred<Result<Unit>>()
        val socketRunnable = AtomicReference<ConnectSocketToServerRunnable?>()
        val callbackArrivedEarly = AtomicBoolean(false)
        val callbackHandled = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val connectionStarted = AtomicBoolean(false)
        val readyForCommit = AtomicBoolean(false)
        val committed = AtomicBoolean(false)
    }

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
        val attempt = synchronized(connectionAttemptLock) {
            if (connectionAttempt != null) {
                return Result.failure(IOException())
            }
            ClientJoinAttempt(NativeGameLifecycle.beginClientRoomJoin()).also { connectionAttempt = it }
        }

        fun failJoin(failure: Throwable): Result<String> {
            val cause = unwrapNativeInvocationFailure(failure)
            cancelJoinAttempt(attempt)
            if (cause is CancellationException) throw cause
            return Result.failure(cause)
        }

        try {
            if (uuid == null) engine.settings.lastNetworkIP = restrictedString(address.trim())
            network.currentServerId = uuid
            context.message("rwpp.multiplayer.join.connecting")
        } catch (cancelled: CancellationException) {
            cancelJoinAttempt(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            return failJoin(failure)
        }

        fun finish(runnable: ConnectSocketToServerRunnable) {
            if (!attempt.callbackHandled.compareAndSet(false, true)) return
            try {
                val socket = runnable.connectedSocket
                val isCurrent = synchronized(connectionAttemptLock) {
                    connectionAttempt === attempt && !attempt.cancelled.get()
                }
                if (!isCurrent) {
                    closeSocket(socket)
                    attempt.socketResult.complete(Result.failure(IOException("Connection cancelled.")))
                    return
                }
                if (socket == null) {
                    attempt.socketResult.complete(
                        Result.failure(IOException("Connection failed: target server may be unreachable.")),
                    )
                    return
                }

                val outcome = synchronized(connectionAttemptLock) {
                    if (connectionAttempt !== attempt || attempt.cancelled.get()) {
                        null
                    } else if (!NativeGameLifecycle.expectClientSocket(attempt.roomTicket, socket)) {
                        null
                    } else {
                        try {
                            if (network.startClientConnection(socket)) {
                                attempt.connectionStarted.set(true)
                                Result.success(Unit)
                            } else {
                                Result.failure(IOException("Connection failed."))
                            }
                        } catch (cancelled: CancellationException) {
                            attempt.socketResult.completeExceptionally(cancelled)
                            null
                        } catch (failure: Throwable) {
                            val cause = unwrapNativeInvocationFailure(failure)
                            if (cause is CancellationException) {
                                attempt.socketResult.completeExceptionally(cause)
                                null
                            } else {
                                Result.failure(cause)
                            }
                        }
                    }
                }
                if (outcome == null) {
                    closeSocket(socket)
                    attempt.socketResult.complete(Result.failure(IOException("Connection cancelled.")))
                } else {
                    attempt.socketResult.complete(outcome)
                }
            } catch (failure: Throwable) {
                val cause = unwrapNativeInvocationFailure(failure)
                if (cause is CancellationException) {
                    attempt.socketResult.completeExceptionally(cause)
                } else {
                    attempt.socketResult.complete(Result.failure(cause))
                }
                if (!attempt.connectionStarted.get()) closeSocket(runnable.connectedSocket)
            }
        }

        val runnable = try {
            network.connectToServerAsync(address.trim(), true) {
                val current = synchronized(attempt.callbackLock) {
                    attempt.socketRunnable.get().also { if (it == null) attempt.callbackArrivedEarly.set(true) }
                }
                if (current != null) dispatchNativeSocketCompletion { finish(current) }
            }
        } catch (cancelled: CancellationException) {
            cancelJoinAttempt(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            return failJoin(failure)
        }
        val finishImmediately = synchronized(attempt.callbackLock) {
            attempt.socketRunnable.set(runnable)
            attempt.callbackArrivedEarly.get()
        }
        val isCurrent = synchronized(connectionAttemptLock) {
            connectionAttempt === attempt && !attempt.cancelled.get()
        }
        if (!isCurrent) {
            runnable.cancel()
        }

        try {
            if (finishImmediately) dispatchNativeSocketCompletion { finish(runnable) }
        } catch (cancelled: CancellationException) {
            cancelJoinAttempt(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            return failJoin(failure)
        }

        try {
            if (!attempt.roomTicket.awaitConnectionReady(attempt.socketResult, ROOM_JOIN_TIMEOUT_MS) {
                    context.message("rwpp.multiplayer.join.waitingForRoom")
                }) {
                cancelJoinAttempt(attempt)
                return Result.failure(RoomJoinTimeoutException())
            }

            synchronized(connectionAttemptLock) {
                if (!attempt.committed.get() && (connectionAttempt !== attempt || attempt.cancelled.get())) {
                    return Result.failure(IOException("Connection cancelled."))
                }
                attempt.readyForCommit.set(true)
            }
            return Result.success("")
        } catch (cancelled: CancellationException) {
            cancelJoinAttempt(attempt)
            throw cancelled
        } catch (failure: Throwable) {
            return failJoin(failure)
        } finally {
            if (!attempt.readyForCommit.get() && !attempt.committed.get()) finishFailedJoinAttempt(attempt)
        }
    }

    fun cancelJoinServer() {
        val attempt = synchronized(connectionAttemptLock) {
            connectionAttempt.also { connectionAttempt = null }
        } ?: return
        cancelJoinAttempt(attempt, alreadyRemoved = true)
    }

    fun completeJoinServer(): Boolean {
        val attempt = synchronized(connectionAttemptLock) {
            val current = connectionAttempt ?: return false
            val nativeReady = current.roomTicket.roomReady.isCompleted && !current.roomTicket.roomReady.isCancelled
            if (current.cancelled.get() || !current.connectionStarted.get() ||
                (!current.readyForCommit.get() && !nativeReady)
            ) return false
            current.committed.set(true)
            connectionAttempt = null
            current
        }
        NativeGameLifecycle.finishClientRoomJoin(attempt.roomTicket)
        return true
    }

    private fun cancelJoinAttempt(attempt: ClientJoinAttempt, alreadyRemoved: Boolean = false) {
        val shouldCancel = synchronized(connectionAttemptLock) {
            if (!alreadyRemoved && connectionAttempt !== attempt) return
            if (!alreadyRemoved) connectionAttempt = null
            attempt.cancelled.compareAndSet(false, true)
        }
        if (!shouldCancel) return
        // Keep the runnable reference until any late completion has observed this cancelled
        // attempt; its callback still needs the socket so it can close it safely.
        attempt.socketRunnable.get()?.cancel()
        attempt.socketResult.complete(Result.failure(IOException("Connection cancelled.")))
        attempt.roomTicket.roomReady.completeExceptionally(IOException("Connection cancelled."))
        NativeGameLifecycle.finishClientRoomJoin(attempt.roomTicket)
        if (attempt.connectionStarted.get() && GameEngine.getInstance().networkEngine.networked) {
            GameEngine.getInstance().networkEngine.disconnect("Connection cancelled.")
        }
    }

    private fun finishFailedJoinAttempt(attempt: ClientJoinAttempt) {
        synchronized(connectionAttemptLock) {
            if (connectionAttempt === attempt) connectionAttempt = null
        }
        if (!attempt.cancelled.get()) {
            attempt.socketRunnable.getAndSet(null)?.cancel()
            NativeGameLifecycle.finishClientRoomJoin(attempt.roomTicket)
        }
    }

    private fun closeSocket(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
            // A late callback owns no active attempt; socket close is best-effort cleanup.
        }
    }

    fun getAllMissions(types: List<MissionType>): List<Mission> = buildList {
        for (type in types) {
            val directory = "maps/${type.pathName()}"
            val missionFiles = listGameAssetsRecursively(directory)
                .filter { it.endsWith(".tmx", ignoreCase = true) }
            missionFiles.forEachIndexed { index, path ->
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
                        enginePath = path,
                    ),
                )
            }
        }
    }

    /**
     * Enumerates mission assets as bounded pages. Directory traversal and object creation happen
     * as the collector requests pages, so cancelling a screen stops the remaining traversal.
     */
    fun getMissionPages(types: List<MissionType>, pageSize: Int): Flow<CatalogPage<Mission>> = flow {
        require(pageSize > 0) { "pageSize must be greater than zero" }
        val pages = CatalogPageEmitter(pageSize) { emit(it) }
        for (type in types) {
            var id = 0
            val directory = "maps/${type.pathName()}"
            forEachTmxAsset(directory) { path ->
                val mapName = path.removePrefix("$directory/").removeSuffixIgnoreCase(".tmx")
                val missionName = if (type == MissionType.Normal && "__-__" in mapName) {
                    mapName.substringAfter("__-__")
                } else mapName
                pages.add(
                    Mission(
                        id = id++,
                        name = missionName,
                        type = type,
                        mapName = mapName,
                        mapType = GameSetupMapType.skirmishMap,
                        inputStreamProvider = { openGameAsset(path) },
                        enginePath = path,
                    ),
                )
            }
        }
        pages.finish()
    }

    fun getAllMaps(): Map<GameSetupMapType, List<GameMap>> {
        val engine = GameEngine.getInstance()
        val skirmishMaps = listGameAssetsRecursively("maps/skirmish")
            .filter { it.endsWith(".tmx", ignoreCase = true) }
            .mapIndexed { index, path ->
                GameMap(
                    id = index,
                    mapName = path.removePrefix("maps/skirmish/").removeSuffixIgnoreCase(".tmx"),
                    mapType = GameSetupMapType.skirmishMap,
                    inputStreamProvider = { openGameAsset(path) },
                    enginePath = path,
                )
            }

        val customDirectory = CUSTOM_MAP_DIRECTORY
        val customNames = runCatching { FileLoader.listDir(customDirectory, true).orEmpty() }
            .getOrDefault(emptyArray())
        val extraMaps = engine.modManager.addExtraMapsForPath(customNames, customDirectory).orEmpty()
        val customMaps = extraMaps.mapIndexedNotNull { index, fileName ->
            if (!fileName.endsWith(".tmx", ignoreCase = true)) return@mapIndexedNotNull null
            // The native selector also prefixes MOD| and NEW_PATH| entries with the custom-map
            // directory. FileLoader resolves those virtual path tags after the directory prefix.
            val path = normalizeGameMapPath(
                if (fileName.startsWith('/')) fileName else "$customDirectory/$fileName",
            )
            GameMap(
                id = index,
                // The MOD|/NEW_PATH| tags route the engine to a mod archive; they are not part of
                // the name a player should read in the map list.
                mapName = MapNames.stripVirtualMapPrefix(fileName).removeSuffixIgnoreCase(".tmx"),
                mapType = GameSetupMapType.customMap,
                inputStreamProvider = {
                    openGamePath(path) ?: openGamePathWithModTag(path) ?: throw FileNotFoundException(path)
                },
                enginePath = path,
            )
        }

        val savesDirectory = GamePlatformHost.savedGameDirectory
        val saves = runCatching { FileLoader.listDir(savesDirectory, false).orEmpty().toList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".rwsave", ignoreCase = true) }
            .mapIndexed { index, fileName ->
                val path = normalizeGameMapPath(
                    if (fileName.startsWith('/')) fileName else "$savesDirectory/$fileName",
                )
                val mapName = relativeMapPath(fileName, savesDirectory)
                    .removeSuffixIgnoreCase(".rwsave")
                GameMap(
                    id = index,
                    mapName = mapName,
                    mapType = GameSetupMapType.savedGame,
                    inputStreamProvider = { openGamePath(path) ?: throw FileNotFoundException(path) },
                    enginePath = path,
                )
            }

        return linkedMapOf(
            GameSetupMapType.skirmishMap to skirmishMaps,
            GameSetupMapType.customMap to customMaps,
            GameSetupMapType.savedGame to saves,
        )
    }

    /**
     * Traverses built-in maps incrementally, then emits custom maps and saved games in bounded
     * pages. The native directory APIs return each directory as an array, but the recursive
     * traversal no longer builds one flattened list of every asset before the first page.
     */
    fun getMapPages(pageSize: Int, mapType: GameSetupMapType? = null): Flow<CatalogPage<GameMap>> = flow {
        require(pageSize > 0) { "pageSize must be greater than zero" }
        val pages = CatalogPageEmitter(pageSize) { emit(it) }
        val engine = GameEngine.getInstance()

        if (mapType == null || mapType == GameSetupMapType.skirmishMap) {
            var skirmishId = 0
            forEachTmxAsset("maps/skirmish") { path ->
                pages.add(
                    GameMap(
                        id = skirmishId++,
                        mapName = path.removePrefix("maps/skirmish/").removeSuffixIgnoreCase(".tmx"),
                        mapType = GameSetupMapType.skirmishMap,
                        inputStreamProvider = { openGameAsset(path) },
                        enginePath = path,
                    ),
                )
            }
        }

        if (mapType == null || mapType == GameSetupMapType.customMap) {
            val customDirectory = CUSTOM_MAP_DIRECTORY
            val customNames = runCatching { FileLoader.listDir(customDirectory, true).orEmpty() }
                .getOrDefault(emptyArray())
            val extraMaps = engine.modManager.addExtraMapsForPath(customNames, customDirectory).orEmpty()
            extraMaps.forEachIndexed { index, fileName ->
                currentCoroutineContext().ensureActive()
                if (fileName.endsWith(".tmx", ignoreCase = true)) {
                    val path = normalizeGameMapPath(
                        if (fileName.startsWith('/')) fileName else "$customDirectory/$fileName",
                    )
                    pages.add(
                        GameMap(
                            id = index,
                            mapName = fileName.removeSuffixIgnoreCase(".tmx"),
                            mapType = GameSetupMapType.customMap,
                            inputStreamProvider = { openGamePath(path) ?: throw FileNotFoundException(path) },
                            enginePath = path,
                        ),
                    )
                }
            }
        }

        if (mapType == null || mapType == GameSetupMapType.savedGame) {
            val savesDirectory = GamePlatformHost.savedGameDirectory
            val saveFiles = runCatching { FileLoader.listDir(savesDirectory, false).orEmpty().toList() }
                .getOrDefault(emptyList())
                .filter { it.endsWith(".rwsave", ignoreCase = true) }
            saveFiles.forEachIndexed { index, fileName ->
                currentCoroutineContext().ensureActive()
                val path = normalizeGameMapPath(
                    if (fileName.startsWith('/')) fileName else "$savesDirectory/$fileName",
                )
                pages.add(
                    GameMap(
                        id = index,
                        mapName = relativeMapPath(fileName, savesDirectory).removeSuffixIgnoreCase(".rwsave"),
                        mapType = GameSetupMapType.savedGame,
                        inputStreamProvider = { openGamePath(path) ?: throw FileNotFoundException(path) },
                        enginePath = path,
                    ),
                )
            }
        }
        pages.finish()
    }

    /** Resolve only the room's named map files; it never enumerates the full map catalog. */
    fun getDirectSetupMaps(
        type: GameSetupMapType,
        mapName: String?,
        currentMapPath: String?,
    ): List<GameMap> {
        val values = linkedSetOf<String>()
        currentMapPath?.takeIf(String::isNotBlank)?.let(values::add)
        mapName?.takeIf(String::isNotBlank)?.let(values::add)
        val paths = linkedSetOf<String>()
        values.forEach { value -> paths += setupMapCandidates(value, type) }

        return buildList {
            for (path in paths) {
                if (!isSafeSetupMapPath(path) || !isSetupMapPathCompatible(path, type) ||
                    !runCatching { FileLoader.fileExists(path) }.getOrDefault(false)
                ) {
                    continue
                }
                val mapNameFromPath = setupMapNameFromPath(path, type)
                val provider: () -> InputStream = if (path.startsWith("maps/", ignoreCase = true)) {
                    { openGameAsset(path) }
                } else {
                    { openGamePath(path) ?: throw FileNotFoundException(path) }
                }
                add(
                    GameMap(
                        id = -1,
                        mapName = mapNameFromPath,
                        mapType = type,
                        inputStreamProvider = provider,
                        enginePath = path,
                    ),
                )
            }
        }
    }

    private fun setupMapCandidates(value: String, type: GameSetupMapType): List<String> {
        val normalized = normalizeGameMapPath(value)
        val suffix = if (type == GameSetupMapType.savedGame) ".rwsave" else ".tmx"
        val fileName = if (normalized.endsWith(suffix, ignoreCase = true)) normalized else normalized + suffix
        val rooted = normalized.startsWith('/') || normalized.startsWith("maps/", ignoreCase = true) ||
                normalized.startsWith("mods/maps/", ignoreCase = true) || normalized.startsWith(
            "saves/",
            ignoreCase = true
        ) ||
                Regex("^[A-Za-z]:/").containsMatchIn(normalized)
        if (rooted) return listOf(normalizeGameMapPath(fileName))

        val directories = when (type) {
            GameSetupMapType.skirmishMap -> listOf("maps/skirmish")
            GameSetupMapType.customMap -> listOf(
                CUSTOM_MAP_DIRECTORY,
                "mods/maps",
                "/SD/rusted_warfare/maps",
            )

            GameSetupMapType.savedGame -> listOf(
                GamePlatformHost.savedGameDirectory,
                "/SD/rustedWarfare/saves",
                "saves",
            )
        }
        return directories.map { directory -> normalizeGameMapPath("$directory/$fileName") }
    }

    private fun setupMapNameFromPath(path: String, type: GameSetupMapType): String {
        val directories = when (type) {
            GameSetupMapType.skirmishMap -> listOf("maps/skirmish")
            GameSetupMapType.customMap -> listOf(
                CUSTOM_MAP_DIRECTORY,
                "mods/maps",
                "/SD/rusted_warfare/maps",
            )

            GameSetupMapType.savedGame -> listOf(
                GamePlatformHost.savedGameDirectory,
                "/SD/rustedWarfare/saves",
                "saves",
            )
        }
        val directory = directories.firstOrNull { path.startsWith("$it/", ignoreCase = true) }
        val relative = if (directory != null) path.drop(directory.length + 1) else path.substringAfterLast('/')
        val suffix = if (type == GameSetupMapType.savedGame) ".rwsave" else ".tmx"
        return relative.removeSuffixIgnoreCase(suffix)
    }

    private fun isSafeSetupMapPath(path: String): Boolean {
        val normalized = normalizeGameMapPath(path)
        return normalized.isNotBlank() && normalized.split('/').none { it == ".." } && '\u0000' !in normalized
    }

    private fun isSetupMapPathCompatible(path: String, type: GameSetupMapType): Boolean {
        val normalized = normalizeGameMapPath(path)
        val skirmishRoots = listOf("maps/skirmish/")
        val customRoots = listOf(
            "$CUSTOM_MAP_DIRECTORY/",
            "mods/maps/",
            "/SD/rusted_warfare/maps/",
        )
        val saveRoots = listOf(
            "${GamePlatformHost.savedGameDirectory.trimEnd('/')}/",
            "/SD/rustedWarfare/saves/",
            "saves/",
        )
        val roots = when (type) {
            GameSetupMapType.skirmishMap -> skirmishRoots
            GameSetupMapType.customMap -> customRoots
            GameSetupMapType.savedGame -> saveRoots
        }
        if (roots.any { normalized.startsWith(it, ignoreCase = true) }) return true
        val knownDifferentType = (skirmishRoots + customRoots + saveRoots)
            .any { normalized.startsWith(it, ignoreCase = true) }
        if (knownDifferentType) return false
        val absolutePath = normalized.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(normalized)
        return absolutePath && type != GameSetupMapType.skirmishMap
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
            .mapIndexed { index, name -> Replay(index, name) { MapNames.getPrettyMapName(name) } }
            .toList()
    }

    fun getReplayPages(pageSize: Int): Flow<CatalogPage<Replay>> = flow {
        require(pageSize > 0) { "pageSize must be greater than zero" }
        val replayFiles = FileLoader.listDir(REPLAY_DIRECTORY)
        if (replayFiles == null) {
            GameEngine.log("failed to find replay folder")
            return@flow
        }
        // FileLoader exposes replay names as one native array. Keep the legacy ordering, then
        // construct and publish replay objects in bounded batches instead of a full object list.
        val names = replayFiles.asSequence()
            .filterNot { it.endsWith(".map") }
            .sortedWith(Comparator(::compareReplayNames))
        val pages = CatalogPageEmitter(pageSize) { emit(it) }
        var id = 0
        for (name in names) {
            currentCoroutineContext().ensureActive()
            pages.add(Replay(id++, name) { MapNames.getPrettyMapName(name) })
        }
        pages.finish()
    }

    /** Resolve an exact replay filename without enumerating the replay directory. */
    fun getReplay(name: String): Replay? {
        if (name.isBlank() || name == "." || name == ".." || name != name.trim() ||
            name.any { it.isISOControl() || it in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') } ||
            name.endsWith(".map", ignoreCase = true)
        ) {
            return null
        }
        val file = runCatching {
            GameEngine.getInstance().replayEngine.getReplayFile(name, true)
        }.getOrNull() ?: return null
        if (!runCatching { FileLoader.fileExists(file.absolutePath) }.getOrDefault(false)) return null
        return Replay(-1, name) { MapNames.getPrettyMapName(name) }
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

    private suspend fun forEachTmxAsset(directory: String, action: suspend (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        for (name in GamePlatformHost.listGameAssetDirectory(directory).sorted()) {
            currentCoroutineContext().ensureActive()
            val path = "$directory/$name"
            if (name.endsWith(".tmx", ignoreCase = true)) action(path)
            else forEachTmxAsset(path, action)
        }
    }

    /** Compatibility path for the legacy synchronous catalog APIs. */
    private fun listGameAssetsRecursively(directory: String): List<String> =
        GamePlatformHost.listGameAssetDirectory(directory).sorted().flatMap { name ->
            val path = "$directory/$name"
            if (name.endsWith(".tmx", ignoreCase = true)) listOf(path) else listGameAssetsRecursively(path)
        }

    private class CatalogPageEmitter<T>(
        private val pageSize: Int,
        private val emitPage: suspend (CatalogPage<T>) -> Unit,
    ) {
        private var pageIndex = 0
        private val items = ArrayList<T>(pageSize)

        suspend fun add(item: T) {
            currentCoroutineContext().ensureActive()
            items += item
            if (items.size == pageSize) flush()
        }

        suspend fun finish() {
            if (items.isNotEmpty()) flush()
        }

        private suspend fun flush() {
            emitPage(CatalogPage(pageIndex++, items.toList()))
            items.clear()
        }
    }

    private fun openGameAsset(path: String): InputStream = runCatching {
        ApiRuntime.extensionContext.gameAssets().open(path)
    }.getOrElse {
        GamePlatformHost.openGameAsset(path)
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
