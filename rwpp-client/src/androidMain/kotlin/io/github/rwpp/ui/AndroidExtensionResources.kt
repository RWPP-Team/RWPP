/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.ContextThemeWrapper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Android resource IDs belong to this extension, never to the generic RWML APK. */
internal object AndroidExtensionResources {
    private val loaders = ConcurrentHashMap<String, ResourcesLoader>()
    private val resourceBytes by lazy {
        checkNotNull(javaClass.getResourceAsStream("/META-INF/rwpp/android-resources.apk")) {
            "The Android extension resource bundle is missing"
        }.use { it.readBytes() }
    }
    private val digest by lazy {
        MessageDigest.getInstance("SHA-256").digest(resourceBytes).joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    fun context(base: Context): Context {
        val directory = File(base.codeCacheDir, "rwpp-ui-resources").apply { mkdirs() }
        val apk = File(directory, "$digest.apk")
        if (!apk.isFile) synchronized(this) {
            if (!apk.isFile) {
                val temporary = File.createTempFile("resources-", ".tmp", directory)
                try {
                    temporary.writeBytes(resourceBytes)
                    Files.move(temporary.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    check(apk.setReadOnly()) { "Could not protect the Android resource bundle" }
                } finally {
                    temporary.delete()
                }
            }
        }
        val bundleResources = if (Build.VERSION.SDK_INT >= 30) {
            val loader = loaders.getOrPut(apk.absolutePath) {
                val provider = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    ResourcesProvider.loadFromApk(it)
                }
                ResourcesLoader().apply { addProvider(provider) }
            }
            Resources(base.assets, base.resources.displayMetrics, Configuration(base.resources.configuration))
                .apply { addLoaders(loader) }
        } else {
            // Public PackageManager API combines the original application and this resource APK.
            val info = ApplicationInfo(base.applicationInfo).apply {
                sharedLibraryFiles =
                    (sharedLibraryFiles.orEmpty().toList() + apk.absolutePath).distinct().toTypedArray()
            }
            base.packageManager.getResourcesForApplication(info)
        }
        return object : ContextThemeWrapper(base, 0) {
            private val extensionTheme = bundleResources.newTheme().apply { setTo(base.theme) }
            override fun getResources(): Resources = bundleResources
            override fun getAssets() = bundleResources.assets
            override fun getTheme(): Resources.Theme = extensionTheme
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
                AndroidExtensionResources.context(base.createConfigurationContext(overrideConfiguration))
        }
    }
}
