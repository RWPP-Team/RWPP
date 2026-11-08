/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

plugins {
    base
    id("jvm-toolchains")
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false

    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false

    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.allopen) apply false
}

val extensionPlatform = providers.gradleProperty("extensionPlatform").orElse("jvm")
val generateExtensionPackManifest = tasks.register("generateExtensionPackManifest") {
    val output = layout.buildDirectory.file("generated/extension-pack/rwml-pack.properties")
    inputs.property("version", provider { project.version.toString() })
    inputs.property("apiVersion", provider { project(":rwpp-api").version.toString() })
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                name=RWPP
                version=${project.version}
                authors=RWPP contributors
                description=RWPP API and client extensions.
                pin.io.github.rwpp.api=${project(":rwpp-api").version}
                pin.io.github.rwpp=${project(":rwpp-client").version}
                enabled=io.github.rwpp.api,io.github.rwpp
            """.trimIndent() + "\n", Charsets.UTF_8
            )
        }
    }
}
listOf("jvm", "android").forEach { platform ->
    val suffix = platform.replaceFirstChar(Char::uppercaseChar)
    tasks.register<Zip>("rwpp${suffix}ExtensionPack") {
        group = "distribution"
        description = "Build the $platform pack containing separate API and client extension JARs."
        archiveFileName.set(provider { "rwpp-${project.version}-$platform-extensions.zip" })
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        dependsOn(generateExtensionPackManifest)
        from(layout.buildDirectory.dir("generated/extension-pack"))
        listOf(":rwpp-api", ":rwpp-client").forEach { module ->
            val taskName = "rwpp${suffix}ExtensionJar"
            dependsOn("$module:$taskName")
            from(provider { project(module).tasks.named<Jar>(taskName).get().archiveFile.get().asFile })
        }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        doLast {
            java.util.zip.ZipFile(archiveFile.get().asFile).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                check(names.size == 3 && "rwml-pack.properties" in names && names.count { it.endsWith(".jar") } == 2)
            }
        }
    }
}

val extensionPack = tasks.register("extensionPack") {
    group = "distribution"
    description = "Build the JVM pack by default; select Android with -PextensionPlatform=android."
    dependsOn(provider {
        val platform = extensionPlatform.get()
        require(platform in listOf("jvm", "android")) { "extensionPlatform must be jvm or android" }
        "rwpp${platform.replaceFirstChar(Char::uppercaseChar)}ExtensionPack"
    })
}
tasks.assemble { dependsOn(extensionPack) }

val extensionPackCheckHost = configurations.create("extensionPackCheckHost") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(extensionPackCheckHost.name, libs.rwml.core)
    add(extensionPackCheckHost.name, libs.kotlin.stdlib)
}
val compileExtensionPackCheck = tasks.register<JavaCompile>("compileExtensionPackCheck") {
    sourceCompatibility = "25"
    targetCompatibility = "25"
    source("build-support/ExtensionPackCheck.java")
    classpath = extensionPackCheckHost
    destinationDirectory.set(layout.buildDirectory.dir("classes/extension-pack-check"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
listOf("jvm", "android").forEach { platform ->
    val suffix = platform.replaceFirstChar(Char::uppercaseChar)
    tasks.register<JavaExec>("verify${suffix}ExtensionPack") {
        group = "verification"
        description = "Validate RWML pack import, dependency ordering and extension ownership."
        dependsOn("rwpp${suffix}ExtensionPack", compileExtensionPackCheck)
        classpath = files(compileExtensionPackCheck.flatMap { it.destinationDirectory }) + extensionPackCheckHost +
                files("lib/slick.jar", "lib/lwjgl.jar", "lib/jogg-0.0.7.jar", "lib/jorbis-0.0.15.jar")
        mainClass.set("ExtensionPackCheck")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        doFirst {
            setArgs(
                listOf(
                    platform,
                    tasks.named<Zip>("rwpp${suffix}ExtensionPack").get().archiveFile.get().asFile.absolutePath,
                    layout.buildDirectory.dir("validation/extension-pack-$platform").get().asFile.absolutePath
                )
            )
        }
    }
}

allprojects {
    group = "io.github.rwpp"
    version = "2.0.0-beta04"

//    subprojects {
//        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
//            enabled = false
//        }
//    }
}

subprojects {
    apply(plugin = "idea")
    extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
        module.isDownloadSources = true
    }
}
