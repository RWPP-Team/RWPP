package io.github.rwpp.mixin.android

import android.util.Log
import com.corrodinggames.rts.game.units.custom.CustomUnitMetadata
import com.corrodinggames.rts.game.units.custom.CustomUnitMetadataLoader
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.NativeModLoadProgress
import kim.der.asm.api.annotation.*
import java.lang.reflect.InvocationTargetException

private const val LOADER = "com/corrodinggames/rts/game/units/custom/CustomUnitMetadataLoader"

@AsmMixin(LOADER)
object AndroidModMemoryMixin {
    private val oomErrors by lazy {
        CustomUnitMetadataLoader::class.java.getDeclaredField("oomErrors").apply { isAccessible = true }
    }

    @ModifyExpressionValue(
        method = "loadAllUnits()V",
        at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/mod/ModManager.refreshModList()Ljava/util/ArrayList;",
        ),
        require = 1,
    )
    fun trimDisabledModAssets(mods: ArrayList<*>): ArrayList<*> {
        val disabled = mods.filterIsInstance<Mod>().filter { it.disabled }
        if (evictModAssets(disabled)) System.gc()
        return mods
    }

    @WrapOperation(
        method = "loadAllUnits()V",
        at = At(
            InjectionPoint.INVOKE,
            target = "$LOADER.readAllCustomUnitConfigs(Ljava/lang/String;IZLcom/corrodinggames/rts/gameFramework/mod/Mod;Ljava/lang/String;Ljava/lang/String;)V",
        ),
        ordinal = -1,
        require = 3,
    )
    fun tolerateModMemoryPressure(
        path: String, depth: Int, userContent: Boolean, mod: Mod?, root: String, template: String?,
        original: Operation<Unit>,
    ) {
        if (mod == null || mod.disabled) {
            original.call(path, depth, userContent, mod, root, template)
            return
        }
        val before = oomErrors.getInt(null)
        try {
            original.call(path, depth, userContent, mod, root, template)
        } catch (failure: Throwable) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            if (cause !is OutOfMemoryError) throw failure
            skipFailedMod(mod)
            return
        }
        // Native decoders can substitute an OOM image or swallow an OOM in the INI parser.
        if (oomErrors.getInt(null) > before) skipFailedMod(mod)
    }

    private fun skipFailedMod(mod: Mod) {
        mod.disabled = true
        mod.loadFailed = true
        val pending = CustomUnitMetadata.networkSyncedCustomUnits
        synchronized(pending) {
            pending.removeAll { (it as? CustomUnitMetadata)?.mod === mod }
        }
        evictModAssets(listOf(mod))
        System.gc()
        val title = mod.getTitle() ?: mod.name.orEmpty()
        mod.errorMessage = runCatching { readI18n("rwpp.mod.memorySkipped", title) }
            .getOrNull()?.takeIf { it != "rwpp.mod.memorySkipped" } ?: "Not enough memory to load mod: $title"
        val runtime = Runtime.getRuntime()
        Log.w("RWPP", "Disabled mod after memory exhaustion: $title, Java heap cap=${runtime.maxMemory()}")
        if (!NativeModLoadProgress.failed(title)) {
            // Persist the disabled mod when the native startup loader recovered from a failed load.
            val engine = GameEngine.getInstance()
            engine.modManager.saveSelection()
            engine.settings.save()
        }
    }

    private fun evictModAssets(mods: List<Mod>): Boolean {
        val prefixes = mods.flatMap { mod -> listOfNotNull(mod.sourceFolder, mod.sourcePathRaw) }
            .map { it.replace('\\', '/').trimEnd('/') + "/" }.toSet()
        fun fromDisabledMod(key: Any?): Boolean {
            if (prefixes.isEmpty()) return false
            val source = (key as? String)?.substringAfter(']')?.replace('\\', '/') ?: return false
            return prefixes.any(source::startsWith)
        }
        val images = CustomUnitMetadataLoader.imageCache.entries.removeAll {
            val entry = it as Map.Entry<*, *>
            (entry.value as? BitmapOrTexture)?.isErrorImage == true || fromDisabledMod(entry.key)
        }
        val sounds = CustomUnitMetadataLoader.soundCache.entries.removeAll { fromDisabledMod((it as Map.Entry<*, *>).key) }
        return images || sounds
    }
}
