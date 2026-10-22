/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dalvik.system.BaseDexClassLoader
import dalvik.system.PathClassLoader
import io.github.rwpp.android.impl.PlayerInternal
import io.github.rwpp.api.util.Reflect
import kotlinx.coroutines.channels.Channel
import org.koin.core.KoinApplication
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet


@Volatile
var init = false
var requireReloadingLib = false
var message by mutableStateOf("loading")
lateinit var fileChooser: ActivityResultLauncher<Intent>
val mainThreadChannel = Channel<() -> Unit>(Channel.UNLIMITED)
var gameOver = false
val defeatedPlayerSet = CopyOnWriteArraySet<PlayerInternal>()
var questionOption: String? = null
var isSinglePlayerGame: Boolean = false
var isGaming = false
lateinit var koinApplication: KoinApplication
lateinit var dexFolder: File
val pickFileActions = mutableListOf<(File) -> Unit>()
var gameLoaded = false
val uiHandler by lazy {
    Handler(Looper.getMainLooper())
}
val cachePlayerSet = CopyOnWriteArraySet<PlayerInternal>()
fun loadDex(context: Context, dexPath: String) {
    val pathClassLoader = context.classLoader as PathClassLoader
    val systemPathList = Reflect.reifiedGet<BaseDexClassLoader, Any>(pathClassLoader, "pathList")!!
    systemPathList::class.java.getDeclaredMethod("addDexPath", String::class.java, File::class.java)
        .apply { isAccessible = true }
        .invoke(systemPathList, dexPath, null)
//    会报错 Attempt to register dex file with multiple class loaders， 尝试使用addDexPath方式加载dex文件
//    val cl = classLoader ?: PathClassLoader("${dexFolder.absolutePath}/classes.dex", pathClassLoader)
//    val systemPathList = Reflect.reifiedGet<BaseDexClassLoader, Any>(pathClassLoader, "pathList")!!
//    val pathList = Reflect.reifiedGet<BaseDexClassLoader, Any>(cl, "pathList")!!
//
//    val systemDexElements = Reflect.get<Array<*>>(systemPathList, "dexElements")!!
//    val dexElements = Reflect.get<Array<*>>(pathList, "dexElements")!!
//
//    val elements = java.lang.reflect.Array
//        .newInstance(
//            dexElements.javaClass.componentType,
//            systemDexElements.size + dexElements.size
//        )
//
//    systemDexElements.toMutableList()
//        .apply { addAll(dexElements) }
//        .forEachIndexed { i, v -> java.lang.reflect.Array.set(elements, i, v) }
//
//    Reflect.set(systemPathList, "dexElements", elements)
    //FileOutputStream(File(dexFolder, "classes.dex")).channel.tryLock()
}
