/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

import java.util.zip.ZipFile

plugins {
    id("com.mikepenz.aboutlibraries.plugin") version "15.3.0" apply false
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.allopen)
    `maven-publish`
}

// Export metadata deliberately; ordinary builds consume the checked-in platform snapshots.
if (providers.gradleProperty("generateAboutLibraries").orNull == "true") {
    pluginManager.apply("com.mikepenz.aboutlibraries.plugin")
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
        val mainCompilation = compilations.getByName("main")
        val uiRegression = compilations.create("uiRegression") {
            associateWith(mainCompilation)
            defaultSourceSet.kotlin.srcDir("src/uiRegression/kotlin")
            defaultSourceSet.dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.game.lib)
                implementation(libs.rwml.core)
                implementation(libs.asm.framework)
                implementation(libs.asm.tree)
                runtimeOnly(files("$rootDir/lib/android.jar", "$rootDir/lib/lwjgl.jar", "$rootDir/lib/slick.jar"))
            }
        }
        tasks.register<Test>("aeroUiTest") {
            group = "verification"
            description = "Run focused map preview, HUD and save UI regression tests."
            testClassesDirs = uiRegression.output.classesDirs
            // Prefer the game's executable Android compatibility classes. SDK stubs supply only
            // missing interfaces/types, and must not shadow Rect/Paint/Color in native draw tests.
            classpath = uiRegression.output.allOutputs +
                    uiRegression.runtimeDependencyFiles.filter { it.name.startsWith("game-lib-") } +
                    uiRegression.runtimeDependencyFiles
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":rwpp-api"))
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
            implementation(libs.coil.gif)
        }

        jvmMain.dependencies {
            compileOnly(files("$rootDir/lib/slick.jar", "$rootDir/lib/lwjgl.jar"))
            api(libs.compose.desktop.common)
            // Compile against Android signatures without shadowing the JDK with the bundled
            // compatibility jar's java.* stubs.
            compileOnly(files(androidCompileStubs.flatMap { it.archiveFile }))
            implementation(libs.desktop.jvm.windows.x64)
            implementation(libs.desktop.jvm.macos.arm64)
            implementation(libs.desktop.jvm.linux.x64)
            compileOnly(
                fileTree(
                    mapOf(
                        "dir" to "$rootDir/lib",
                        "include" to listOf("lwjgl.jar", "slick.jar")
                    )
                )
            )
        }
    }
}

tasks.named<Test>("jvmTest") {
    // The game's desktop jar implements android.graphics; the SDK jar contains throwing stubs.
    // Keep the executable classes ahead of the stubs for tests exercising native drawing code.
    val testClasspath = classpath
    classpath = testClasspath.filter { it.name.startsWith("game-lib-") } + testClasspath
}

compose.resources {
    packageOfResClass = "io.github.rwpp.generated"
}

android {
    namespace = "io.github.rwpp"
    compileSdk = (findProperty("androidCompileSdk") as String?)?.toInt()
        ?: libs.versions.android.compileSdk.get().toInt()
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


// API and client are loaded as independent RWML extensions.
apply(from = rootProject.file("gradle/extension-archives.gradle.kts"))
apply(from = rootProject.file("gradle/android-extension-resources.gradle.kts"))

val rwppJvmJar = tasks.named<Jar>("jvmJar")
val verifyClientNetworkDependencies = tasks.register("verifyClientNetworkDependencies") {
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().incoming.resolutionResult.allComponents.mapNotNull {
            (it.id as? ModuleComponentIdentifier)?.takeIf { id ->
                id.group.startsWith("io.netty") || id.group == "com.github.l42111996"
            }?.displayName
        }
        check(forbidden.isEmpty()) { "Server network dependency leaked into client: $forbidden" }
    }
}
tasks.named("rwppJvmExtensionJar") { dependsOn(verifyClientNetworkDependencies) }

// Reuse client networking without Compose, audio or rendering, while keeping its sources in client.
val clientNetworkDescriptor = layout.buildDirectory.dir("generated/client-network-descriptor")
val generateClientNetworkDescriptor = tasks.register("generateClientNetworkDescriptor") {
    val descriptor = clientNetworkDescriptor.map { it.file("META-INF/extension.properties") }
    outputs.file(descriptor)
    doLast {
        descriptor.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                id=io.github.rwpp.client.network
                name=RWPP Client Network
                version=${project.version}
                apiVersion=1.0.2
                javaVersion=17
                mainClass=io.github.rwpp.client.net.ClientNetworkExtension
                dependsOn=io.github.rwpp.api
            """.trimIndent() + "\n"
            )
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
