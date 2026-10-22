/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.server

import io.github.rwml.api.ExtensionException
import io.github.rwml.api.GameAssets
import io.github.rwml.api.MixinBridge
import io.github.rwml.api.ProgressListener
import io.github.rwml.core.ExtensionDescriptor
import io.github.rwml.desktop.DesktopHost
import io.github.rwml.host.HostPlatform
import java.io.IOException
import java.nio.file.Path

/** Desktop JVM extension host with the desktop UI extension omitted for a headless process.  */
internal class ServerHost : HostPlatform {
    private val desktopHost = DesktopHost()

    override fun mixins(): MixinBridge? {
        return desktopHost.mixins()
    }

    override fun gameAssets(): GameAssets? {
        return desktopHost.gameAssets()
    }

    override fun builtInExtensions(): List<ExtensionDescriptor?> {
        return mutableListOf()
    }

    @Throws(IOException::class)
    override fun stageArchives(extensionDirectory: Path?): MutableList<String?>? {
        return desktopHost.stageArchives(extensionDirectory)
    }

    @Throws(IOException::class)
    override fun createExtensionLoader(descriptor: ExtensionDescriptor): ClassLoader? {
        return desktopHost.createExtensionLoader(descriptor)
    }

    @Throws(IOException::class)
    override fun createExtensionLoader(descriptor: ExtensionDescriptor, dependencyParent: ClassLoader?): ClassLoader {
        return desktopHost.createExtensionLoader(descriptor, dependencyParent)
    }

    @Throws(ExtensionException::class)
    override fun reconcileJavaRequirement(
        descriptor: ExtensionDescriptor,
        progress: ProgressListener?
    ): ExtensionDescriptor? {
        return desktopHost.reconcileJavaRequirement(descriptor, progress)
    }
}
