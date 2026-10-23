/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

import java.util.Properties
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.allopen)
    `maven-publish`
}

extensions.configure<org.koin.compiler.plugin.KoinGradleExtension>("koinCompiler") {
    // API services are registered by the built-in RWML extension; this module adds RWPP's services
    // to that container through the annotation-generated modules.
    compileSafety.set(true)
}

allOpen {
    annotations("org.koin.core.annotation.Single")
}

// The bundled Android compatibility jar also contains java.* stubs. Keep those off the
// JVM compile classpath: they shadow the JDK classes used by Kotlin/JVM (ThreadLocal,
// reflection, collections, etc.). JVM sources only need Android signatures from it.
val androidCompileStubs = tasks.register<Zip>("androidCompileStubs") {
    archiveFileName.set("android-compile-stubs.jar")
    destinationDirectory.set(layout.buildDirectory.dir("compile-stubs"))
    from(zipTree("$rootDir/lib/android.jar")) {
        include("android/**")
    }
}

kotlin {
    androidTarget()
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":rwpp-api"))
            api(libs.korlibs.audio)
            compileOnly(libs.rwml.core)
            compileOnly(libs.game.lib)
            compileOnly(libs.asm.framework)
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.components.resources)
            api(libs.compose.ui)
            api(libs.compose.material.icons.core)
            api(libs.koin.annotations)
            api(libs.koin.compose)
            api(libs.tomlkt)
            api(libs.coil.compose)
            api(libs.coil.network.okhttp)
            implementation(libs.minimal.json)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
            implementation(libs.reorderable)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(libs.rwml.core)
            implementation(libs.game.lib)
            implementation(libs.asm.framework)
            implementation(libs.asm.tree)
            runtimeOnly(files("$rootDir/lib/lwjgl.jar", "$rootDir/lib/slick.jar"))
            runtimeOnly(files("$rootDir/lib/android.jar"))
        }

        androidMain.dependencies {
            api(libs.androidx.activity.compose)
            api(libs.androidx.appcompat)
            api(libs.androidx.core.ktx)
            implementation(libs.logback.android)
        }

        jvmMain.dependencies {
            api(libs.compose.desktop.common)
            // Compile against Android signatures without shadowing the JDK with the bundled
            // compatibility jar's java.* stubs.
            compileOnly(files(androidCompileStubs.flatMap { it.archiveFile }))
            implementation(libs.desktop.jvm.windows.x64)
            implementation(libs.desktop.jvm.macos.arm64)
            implementation(libs.desktop.jvm.linux.x64)
            implementation(libs.slf4j.simple)
            implementation(libs.vorbisspi)
            compileOnly(fileTree(mapOf(
                "dir" to "$rootDir/lib",
                "include" to listOf("lwjgl.jar", "slick.jar")
            )))
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.rwpp.generated"
}

android {
    namespace = "io.github.rwpp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            groupId = "io.github.rwpp"
            version = rootProject.version.toString()
        }
    }
}


/*
 * Platform-specific extension archives keep the ASM scanner from seeing hooks for the other
 * platform. The archive carries class files for mixin discovery, Android dex for execution,
 * Compose resources as classloader resources.
 */
val rwppAndroidD8Tool = configurations.create("rwppAndroidD8Tool") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(rwppAndroidD8Tool.name, libs.r8)
}

tasks.register("rwppAndroidConfigurationNames") {
    group = "help"
    doLast {
        configurations.names.sorted().filter {
            it.contains("android", ignoreCase = true) || it.contains("debug", ignoreCase = true) ||
                it.contains("release", ignoreCase = true) || it.contains("jvm", ignoreCase = true)
        }.forEach(::println)
    }
}
fun moduleOf(component: ComponentIdentifier): ModuleComponentIdentifier? =
    component as? ModuleComponentIdentifier

/** Only the RWML host runtime is shared; it supplies rwml-core, Kotlin stdlib and the mixin runtime. */
fun isRwmlHostArtifact(component: ComponentIdentifier): Boolean {
    val module = moduleOf(component) ?: return false
    return module.group == "io.github.rwpp-team" && module.module == "rwml-core"
}

fun isRwmlAndroidHostArtifact(component: ComponentIdentifier): Boolean {
    val module = moduleOf(component) ?: return false
    val group = module.group
    val kotlinStdlib = group == "org.jetbrains.kotlin" && module.module.startsWith("kotlin-stdlib")
    return kotlinStdlib || isRwmlHostArtifact(module) || group == "kim.der.relay-cn" || group == "org.ow2.asm"
}

/**
 * RWPP ships its API and UI in one extension archive. The host supplies only Kotlin stdlib,
 * rwml-core and the ASM/mixin runtime, so the archive carries every other runtime dependency.
 */
fun isRwppJvmProviderArtifact(component: ComponentIdentifier): Boolean {
    val module = moduleOf(component) ?: return false
    val group = module.group
    val kotlinStdlib = group == "org.jetbrains.kotlin" && module.module.startsWith("kotlin-stdlib")
    return kotlinStdlib || isRwmlHostArtifact(module) || group == "kim.der.relay-cn" || group == "org.ow2.asm"
}

fun rwppAndroidArtifactView(
    configurationName: String,
    artifactType: String,
    includeComponent: (ComponentIdentifier) -> Boolean
): FileCollection {
    return files(providers.provider {
        val configuration = configurations.findByName(configurationName)
            ?: error("No Android dependency configuration '$configurationName'. Available configurations: ${configurations.names.sorted().joinToString()}")
        configuration.incoming.artifactView {
            attributes {
                attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    artifactType
                )
            }
            componentFilter { component -> includeComponent(component) }
        }.files
    })
}

val rwppAndroidEmbeddedRuntimeJars =
    rwppAndroidArtifactView("releaseRuntimeClasspath", "jar") { !isRwmlAndroidHostArtifact(it) }
val rwppAndroidEmbeddedRuntimeAarClasses =
    rwppAndroidArtifactView("releaseRuntimeClasspath", "android-classes-jar") { !isRwmlAndroidHostArtifact(it) }
val rwppAndroidCompileJars =
    rwppAndroidArtifactView("releaseCompileClasspath", "jar") { true }
val rwppAndroidCompileAarClasses =
    rwppAndroidArtifactView("releaseCompileClasspath", "android-classes-jar") { true }

val rwppAndroidSdkJar = providers.provider {
    val localProperties = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { stream -> localProperties.load(stream) }
    val sdkPath = rootProject.providers.gradleProperty("androidSdkDir").orNull
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: localProperties.getProperty("sdk.dir")
        ?: error("Set -PandroidSdkDir or sdk.dir in local.properties to build the Android extension archive")
    val platforms = file(sdkPath).resolve("platforms")
    platforms.listFiles()
        ?.filter { it.isDirectory && it.resolve("android.jar").isFile }
        ?.maxByOrNull {
            it.name.removePrefix("android-").substringBefore("-").toIntOrNull() ?: 0
        }
        ?.resolve("android.jar")
        ?: error("No installed Android platform android.jar found under $platforms")
}

val rwppAndroidProgramJar = tasks.register<Jar>("rwppAndroidProgramJar") {
    group = "build"
    description = "Assemble Android RWPP classes and extension-private runtime classes for D8."
    archiveFileName.set("rwpp-android-program.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/rwpp-android-extension"))
    dependsOn(
        "compileDebugKotlinAndroid",
        "copyDebugComposeResourcesToAndroidAssets",
        ":rwpp-api:createFullJarAndroidMain",
        ":rwpp-api:bundleAndroidMainClassesToRuntimeJar",
    )
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    from({ rwppAndroidEmbeddedRuntimeJars.files.map { zipTree(it) } }) {
        exclude("META-INF/extension.properties")
    }
    from({ rwppAndroidEmbeddedRuntimeAarClasses.files.map { zipTree(it) } }) {
        exclude("META-INF/extension.properties")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/versions/**", "module-info.class")
}

val rwppAndroidDexDirectory = layout.buildDirectory.dir("dex/rwpp-android-extension")
val rwppAndroidExtensionDex = tasks.register<JavaExec>("rwppAndroidExtensionDex") {
    group = "distribution"
    description = "D8 compile the Android-only RWPP extension archive."
    dependsOn(rwppAndroidProgramJar, rwppAndroidD8Tool)
    classpath = rwppAndroidD8Tool
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(rwppAndroidProgramJar.flatMap { it.archiveFile })
    inputs.files(rwppAndroidCompileJars, rwppAndroidCompileAarClasses, rwppAndroidSdkJar)
    outputs.dir(rwppAndroidDexDirectory)
    doFirst {
        val output = rwppAndroidDexDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val classpathFiles = (rwppAndroidCompileJars.files + rwppAndroidCompileAarClasses.files)
            .filter { it.isFile && it.extension == "jar" }
            .distinct()
        setArgs(
            buildList {
                add("--min-api")
                add("26")
                add("--output")
                add(output.absolutePath)
                add("--lib")
                add(rwppAndroidSdkJar.get().absolutePath)
                classpathFiles.forEach {
                    add("--classpath")
                    add(it.absolutePath)
                }
                add(rwppAndroidProgramJar.get().archiveFile.get().asFile.absolutePath)
            }
        )
    }
}

val rwppAndroidDescriptorDirectory = layout.buildDirectory.dir("generated/rwpp-android-extension-descriptor")
val generateRwppAndroidExtensionDescriptor = tasks.register("generateRwppAndroidExtensionDescriptor") {
    group = "distribution"
    val descriptor = rwppAndroidDescriptorDirectory.map { it.file("META-INF/extension.properties") }
    outputs.file(descriptor)
    doLast {
        val target = descriptor.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            id=io.github.rwpp
            name=RWPP
            version=${project.version}
            authors=RWPP contributors
            description=RWPP Compose UI framework and Rusted Warfare gameplay extension.
            apiVersion=1.0.2
            javaVersion=17
            mainClass=io.github.rwpp.RwppExtension
            ui-framework=io.github.rwpp.ui.RwppUiFramework
            tags=rwpp,ui
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
    }
}

val rwppAndroidSlf4jProviderDirectory = layout.buildDirectory.dir("generated/rwpp-android-slf4j-provider")
val generateRwppAndroidSlf4jProvider = tasks.register("generateRwppAndroidSlf4jProvider") {
    val providerFile = rwppAndroidSlf4jProviderDirectory.map {
        it.file("META-INF/services/org.slf4j.spi.SLF4JServiceProvider")
    }
    outputs.file(providerFile)
    doLast {
        val target = providerFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText("org.slf4j.impl.LoggerServiceProvider\n", Charsets.UTF_8)
    }
}

val rwppAndroidExtensionJar = tasks.register<Jar>("rwppAndroidExtensionJar") {
    group = "distribution"
    description = "Package RWPP Android classes, dex, and Compose resources."
    archiveBaseName.set("rwpp")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("android")
    destinationDirectory.set(layout.buildDirectory.dir("extensions"))
    dependsOn(rwppAndroidExtensionDex, generateRwppAndroidExtensionDescriptor, generateRwppAndroidSlf4jProvider)
    from({ zipTree(rwppAndroidProgramJar.get().archiveFile.get().asFile) })
    from(rwppAndroidDexDirectory)
    from(rwppAndroidDescriptorDirectory)
    from(rwppAndroidSlf4jProviderDirectory)
    from(layout.buildDirectory.dir("generated/assets/copyDebugComposeResourcesToAndroidAssets"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/versions/**", "module-info.class")
    doLast {
        val archive = archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            check(zip.getEntry("META-INF/extension.properties") != null) { "RWPP extension descriptor is missing" }
            check(zip.getEntry("classes.dex") != null) { "RWPP Android DEX is missing" }
            check(zip.getEntry("io/github/rwpp/RwppExtension.class") != null) { "RWPP Extension class is missing" }
            check(zip.getEntry("io/github/rwpp/api/ApiRuntime.class") != null) { "RWML API runtime is missing" }
            check(zip.getEntry("io/github/rwpp/client/api/ClientServicesKt.class") != null) { "Client services are missing" }
            check(zip.getEntry("io/github/rwpp/api/game/audio/GameSoundPoolImpl.class") != null) { "Client audio is missing" }
            check(zip.getEntry("io/github/rwpp/api/graphics/GL20.class") != null) { "Client OpenGL API is missing" }
            check(zip.getEntry("io/github/rwpp/api/ApiExtension.class") != null) { "RWML API entry point is missing" }
            check(zip.getEntry("org/koin/compose/KoinApplicationKt.class") != null) {
                "Koin Compose classes are missing from the RWPP Android archive"
            }
            check(zip.getEntry("org/slf4j/impl/LoggerServiceProvider.class") != null) {
                "The SLF4J Android provider is missing from the RWPP Android archive"
            }
            val slf4jServices = zip.getEntry("META-INF/services/org.slf4j.spi.SLF4JServiceProvider")
                ?: error("The SLF4J Android provider service registration is missing")
            val slf4jProviderName =
                zip.getInputStream(slf4jServices).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            check(slf4jProviderName == "org.slf4j.impl.LoggerServiceProvider") {
                "Unexpected SLF4J Android provider registration: $slf4jProviderName"
            }
            check(zip.getEntry("composeResources/io.github.rwpp.generated/drawable/logo.png") != null) {
                "Compose resources are missing from the RWPP Android archive"
            }
        }
    }
}

tasks.assemble {
    dependsOn(rwppAndroidExtensionJar)
}

val rwppJvmRuntimeJars =
    rwppAndroidArtifactView("jvmRuntimeClasspath", "jar") { !isRwppJvmProviderArtifact(it) }
val rwppJvmJar = tasks.named<Jar>("jvmJar")
val verifyClientNetworkDependencies = tasks.register("verifyClientNetworkDependencies") {
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().incoming.resolutionResult.allComponents.mapNotNull {
            (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.takeIf { id ->
                id.group.startsWith("io.netty") || id.group == "com.github.l42111996"
            }?.displayName
        }
        check(forbidden.isEmpty()) { "Server network dependency leaked into client: ${forbidden.joinToString()}" }
    }
}
val rwppJvmExtensionJar = tasks.register<Jar>("rwppJvmExtensionJar") {
    group = "distribution"
    description = "Package the desktop-only RWPP extension classes, resources, and private runtime dependencies."
    archiveBaseName.set("rwpp")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("jvm")
    destinationDirectory.set(layout.buildDirectory.dir("extensions"))
    dependsOn(rwppJvmJar, generateRwppAndroidExtensionDescriptor, verifyClientNetworkDependencies)
    from({ zipTree(rwppJvmJar.get().archiveFile.get().asFile) })
    from({ rwppJvmRuntimeJars.files.map { zipTree(it) } }) {
        exclude("META-INF/extension.properties")
    }
    from(rwppAndroidDescriptorDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/versions/**", "module-info.class")
    doLast {
        val archive = archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            check(zip.getEntry("META-INF/extension.properties") != null) { "RWPP JVM extension descriptor is missing" }
            check(zip.getEntry("io/github/rwpp/client/api/ClientServicesKt.class") != null) { "Client services are missing" }
            check(zip.getEntry("io/github/rwpp/api/game/audio/GameSoundPoolImpl.class") != null) { "Client audio is missing" }
            check(zip.getEntry("io/github/rwpp/api/graphics/GL20.class") != null) { "Client OpenGL API is missing" }
            check(zip.getEntry("io/github/rwpp/RwppExtension.class") != null) { "RWPP JVM Extension class is missing" }
            check(zip.getEntry("io/github/rwpp/api/ApiRuntime.class") != null) { "RWML API runtime is missing" }
            check(zip.getEntry("io/github/rwpp/api/ApiExtension.class") != null) { "RWML API entry point is missing" }
            check(zip.getEntry("org/koin/compose/KoinApplicationKt.class") != null) {
                "Koin Compose classes are missing from the RWPP JVM archive"
            }
            val slf4jServices = zip.getEntry("META-INF/services/org.slf4j.spi.SLF4JServiceProvider")
                ?: error("The SLF4J JVM provider service registration is missing")
            val slf4jProviderName =
                zip.getInputStream(slf4jServices).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            check(slf4jProviderName == "org.slf4j.simple.SimpleServiceProvider") {
                "Unexpected SLF4J JVM provider registration: $slf4jProviderName"
            }
            check(zip.getEntry("classes.dex") == null) { "The desktop archive must not contain Android DEX" }
            check(zip.entries().asSequence().none { it.name.startsWith("io/netty/") || it.name.startsWith("kcp/") }) {
                "Netty and Java-KCP belong to the server, not the client archive"
            }
            check(zip.entries().asSequence().none { it.name.startsWith("com/badlogic/") }) {
                "The desktop extension must not bundle libGDX"
            }
            check(zip.getEntry("composeResources/io.github.rwpp.generated/drawable/logo.png") != null) {
                "Compose resources are missing from the desktop extension archive"
            }
        }
    }
}

tasks.assemble {
    dependsOn(rwppJvmExtensionJar)
}

// Reuse client networking without Compose, audio or rendering, while keeping its sources in client.
val clientNetworkDescriptor = layout.buildDirectory.dir("generated/client-network-descriptor")
val generateClientNetworkDescriptor = tasks.register("generateClientNetworkDescriptor") {
    val descriptor = clientNetworkDescriptor.map { it.file("META-INF/extension.properties") }
    outputs.file(descriptor)
    doLast {
        descriptor.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""
                id=io.github.rwpp.client.network
                name=RWPP Client Network
                version=${project.version}
                apiVersion=1.0.2
                javaVersion=17
                mainClass=io.github.rwpp.client.net.ClientNetworkExtension
                dependsOn=io.github.rwpp.api
            """.trimIndent() + "\n")
        }
    }
}
val networkRuntimeJar = tasks.register<Jar>("networkRuntimeJar") {
    group = "distribution"
    description = "Package only the JDK client network backend for non-UI extensions."
    dependsOn(rwppJvmJar, generateClientNetworkDescriptor)
    archiveClassifier.set("network")
    from({ zipTree(rwppJvmJar.get().archiveFile.get().asFile) }) { include("io/github/rwpp/client/net/**") }
    from(clientNetworkDescriptor)
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            check(zip.entries().asSequence().none { it.name.startsWith("io/netty/") || it.name.startsWith("kcp/") })
        }
    }
}
publishing.publications.withType<MavenPublication>().configureEach {
    if (name == "jvm") artifact(networkRuntimeJar)
}
