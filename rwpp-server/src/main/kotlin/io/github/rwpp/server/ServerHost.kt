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
import io.github.rwpp.api.ApiExtensionDescriptor
import io.github.rwpp.api.ApiMixins
import java.io.IOException
import java.nio.file.Path

/**
 * Desktop JVM extension host with the desktop UI extension omitted for a headless process.
 *
 * The API is part of this application's class path and is installed as a built-in extension: there is
 * no archive to stage and no DEX to build, and the manager instantiates it with the host's own class
 * loader. A built-in extension has no archive to scan, so this host registers the API mixins itself
 * before any game class is loaded.
 */
internal class ServerHost : HostPlatform {
    private val desktopHost = DesktopHost()

    init {
        io.github.rwpp.api.net.transport.TransportRuntime.installIfAbsent {
            io.github.rwpp.server.net.ServerTransportBackend()
        }
        val bridge = desktopHost.mixins()
        bridge.install()
        ApiMixins.classes().forEach(bridge::register)
    }

    override fun mixins(): MixinBridge = desktopHost.mixins()

    override fun gameAssets(): GameAssets = desktopHost.gameAssets()

    override fun builtInExtensions(): List<ExtensionDescriptor> =
        listOf(
            ApiExtensionDescriptor.descriptor(),
            ExtensionDescriptor.builtIn(
                "io.github.rwpp.server", "RWPP Server", "2.0.0",
                "Native headless server host", ServerServices::class.java.name, null,
            ),
        )

    @Throws(IOException::class)
    override fun stageArchives(extensionDirectory: Path): List<String> =
        desktopHost.stageArchives(extensionDirectory)

    @Throws(IOException::class)
    override fun createExtensionLoader(descriptor: ExtensionDescriptor): ClassLoader =
        desktopHost.createExtensionLoader(descriptor)

    @Throws(IOException::class)
    override fun createExtensionLoader(
        descriptor: ExtensionDescriptor,
        dependencyParent: ClassLoader
    ): ClassLoader = desktopHost.createExtensionLoader(descriptor, dependencyParent)

    @Throws(ExtensionException::class)
    override fun reconcileJavaRequirement(
        descriptor: ExtensionDescriptor,
        progress: ProgressListener
    ): ExtensionDescriptor = desktopHost.reconcileJavaRequirement(descriptor, progress)
}
