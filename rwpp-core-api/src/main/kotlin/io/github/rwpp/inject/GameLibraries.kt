/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.inject

import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.koinInit
import javassist.ClassPool
import javassist.LoaderClassPath
import java.io.File
import java.io.FileNotFoundException

@Suppress(
    "EnumEntryName",
    "RemoveRedundantBackticks",
    "SpellCheckingInspection",
    "MemberVisibilityCanBePrivate"
)
enum class GameLibraries {
    `game-lib`,
    `android`,
    `android-game-lib`,
    `android-platform-lib`,
    `commons-codec` { init {
        realName = "$name-1.6"
    } },
    `commons-logging` { init {
        realName = "$name-1.1.3"
    } },
    `fluent-hc` { init {
        realName = "$name-4.3.3"
    } },
    `httpclient` { init {
        realName = "$name-4.3.3"
    } },
    `httpclient-cache` { init {
        realName = "$name-4.3.3"
    } },
    `httpcore` { init {
        realName = "$name-4.3.2"
    } },
    `httpmime` { init {
        realName = "$name-4.3.3"
    } },
    `ibxm`,
    `jinput`,
    `jnlp`,
    `jogg` { init {
        realName = "$name-0.0.7"
    } },
    `jorbis` { init {
        realName = "$name-0.0.15"
    } },
    `lwjgl`,
    `lwjgl_util`,
    `lwjgl_util_applet`,
    `natives-linux`,
    /**
     * Optional macOS LWJGL/OpenAL/JInput jar. Not shipped in-repo; Mac installs use loose
     * dylibs from the Rusted Warfare game directory (`java.library.path=.`).
     */
    `natives-macos` {
        init {
            shouldLoad = false
        }
    },
    `slick`,
    `tinylinepp`;

    var realName = name
    var shouldLoad: Boolean = true

    lateinit var lib: File
    lateinit var classTree: ClassTree

    fun load(lib: File) {
        isLoaded = true
        this.lib = lib
        if(!lib.exists()) throw FileNotFoundException("cannot find lib: $name")
        if(this in includes) {
            classTree = ClassTree(ClassPool(defClassPool)).apply {
                defPool.childFirstLookup = true
                name = this@GameLibraries.name
                initByJarFile(lib)
            }
        }
    }

    companion object {
        @JvmStatic
        val defClassPool by lazy {
            ClassPool().apply {
                val cl = GameLibraries::class.java.classLoader
                if (!koinInit || appKoin.get<AppContext>().isDesktop()) {
                    appendClassPath(LoaderClassPath(cl))
                }
            }
        }

        /**
         * 需要被加载为[ClassTree]的lib
         */
        val includes by lazy { mutableSetOf<GameLibraries>() }
    }

    private var isLoaded = false
}