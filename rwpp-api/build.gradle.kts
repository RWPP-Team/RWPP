/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

// ------------------------------------------------------------------------------------------------
// RWPP 扩展 API：事件、配置、网络与游戏对象模型。
//
// 这些内容不是加载器本身，因此不放进 :core。:core 有一条平台边界检查，禁止核心字节码出现 android/、
// com/corrodinggames/、java/awt/、javax/swing/ 的引用，而这里直接使用游戏引擎与 android.graphics
// 的绘图类型；:core 的 POM 也不声明任何依赖，而这里的模型需要 okhttp、序列化与 Koin。
// 单独成模块后，扩展只需依赖本模块即可同时拿到 :core。
//
// 绘图方法以 android.graphics 的 Paint/RectF 为参数。这两个类由游戏自带（映射后的 game-lib 里就有），
// 不是 Android 框架：桌面端与安卓端都由游戏本体提供，因此只对游戏编译，且不以 compileOnly 之外的形式外泄，
// 使用者的类路径不会因此带上游戏。本模块作为内置扩展提供统一的游戏实现。
// ------------------------------------------------------------------------------------------------

import java.util.Properties
import java.util.jar.JarFile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.codeorigin)
    `maven-publish`
    signing
}

extensions.configure<org.koin.compiler.plugin.KoinGradleExtension>("koinCompiler") {
    compileSafety.set(true)
}

description = "RWPP API: events, configuration, networking, and the game object model."

base { archivesName.set("rwpp-api") }

group = "io.github.rwpp-team"
version = libs.versions.rwml.core.get()

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    androidLibrary {
        namespace = "io.github.rwml.api"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.rwml.core)
            api(libs.kotlinx.coroutines.core)
            api(libs.okhttp)
            api(libs.tomlkt)
            api(libs.koin.core)
            api(libs.koin.annotations)
            api(libs.slf4j.api)
            api(libs.kotlin.reflect)
            api(libs.codeorigin)
            api(libs.protobuf.java)
            api(libs.korlibs.audio)
            implementation(libs.minimal.json)
            implementation(libs.kotlin.stdlib)
            compileOnly(libs.game.lib)
            compileOnly(libs.asm.framework)
            compileOnly("org.ow2.asm:asm-tree:9.10.1")
        }

        jvmMain.dependencies {
            compileOnly(libs.lwjgl)
        }
    }
}

// The API is shipped as an extension archive. In particular, Android must load API and game classes
// from the same game/extension loader instead of placing game-dependent API classes in the APK loader.
fun apiHostProvides(component: org.gradle.api.artifacts.component.ComponentIdentifier): Boolean {
    val module = component as? org.gradle.api.artifacts.component.ModuleComponentIdentifier ?: return false
    if (module.group == "io.github.rwpp-team" && module.module == "rwml-core") return true
    if (module.group == "org.jetbrains.kotlin" && module.module != "kotlin-reflect") return true
    if (module.group == "kim.der.relay-cn" || module.group == "org.ow2.asm") return true
    return false
}

fun apiArtifactFiles(configurationName: String, artifactType: String, embedded: Boolean) =
    files(providers.provider {
        configurations.getByName(configurationName).incoming.artifactView {
            attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, artifactType)
            componentFilter { !embedded || !apiHostProvides(it) }
        }.files
    })

val apiJvmRuntime = apiArtifactFiles("jvmRuntimeClasspath", "jar", embedded = true)

fun verifyApiArchive(archive: File, android: Boolean) {
    JarFile(archive).use { jar ->
        val required = listOf(
            "io/github/rwpp/api/ApiExtension.class",
            "io/github/rwpp/api/game/impl/GameImpl.class",
            "io/github/rwpp/api/game/impl/GameRoomImpl.class",
            "io/github/rwpp/api/net/impl/NetImpl.class",
            "io/github/rwpp/api/config/ConfigIOImpl.class",
            "io/github/rwpp/api/game/audio/GameSoundPoolImpl.class",
            "io/github/rwpp/api/graphics/GL30.class",
            "org/koin/core/Koin.class",
            "kotlin/reflect/full/KClasses.class",
        )
        required.forEach { check(jar.getEntry(it) != null) { "API extension is missing $it" } }
        check((jar.getEntry("classes.dex") != null) == android) { "Incorrect API extension platform: $archive" }
        check(jar.getEntry("io/github/rwml/api/Extension.class") == null) { "API must use the host's core classes" }
        check(jar.getEntry("kotlin/Unit.class") == null) { "API must use the host's Kotlin standard library" }
        check(jar.getEntry("io/github/rwpp/api/graphics/GL.class") == null) { "Obsolete GL helper is still packaged" }
        listOf("GameI18nResolver", "I18nType", "TranslationTableProvider", "TranslationTableSnapshot").forEach {
            check(jar.getEntry("io/github/rwpp/api/i18n/$it.class") == null) { "Obsolete translation API: $it" }
        }
        check(jar.entries().asSequence().none { it.name.startsWith("com/corrodinggames/") }) {
            "API must not package game classes"
        }
        val descriptor = Properties().apply {
            jar.getInputStream(jar.getEntry("META-INF/extension.properties")).use { load(it) }
        }
        check(descriptor.getProperty("id") == "io.github.rwml.api")
        check(descriptor.getProperty("version") == project.version.toString()) { "API descriptor version is stale" }
    }
}

val jvmExtensionJar = tasks.register<Jar>("jvmExtensionJar") {
    group = "distribution"
    description = "Package the built-in JVM API extension and its shared runtime."
    archiveFileName.set("rwpp-api.jar")
    destinationDirectory.set(layout.buildDirectory.dir("extensions/jvm"))
    dependsOn("jvmJar")
    from({ zipTree(tasks.named<Jar>("jvmJar").get().archiveFile.get()) })
    from({ apiJvmRuntime.files.filter { it.isFile }.map { zipTree(it) } })
    from("src/commonMain/resources")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**", "module-info.class")
    doLast { verifyApiArchive(archiveFile.get().asFile, android = false) }
}

val apiAndroidRuntimeJars = apiArtifactFiles("androidRuntimeClasspath", "jar", embedded = true)
val apiAndroidRuntimeAars = apiArtifactFiles("androidRuntimeClasspath", "android-classes-jar", embedded = true)
val apiAndroidCompileJars = apiArtifactFiles("androidCompileClasspath", "jar", embedded = false)
val apiAndroidCompileAars = apiArtifactFiles("androidCompileClasspath", "android-classes-jar", embedded = false)
val androidProgramJar = tasks.register<Jar>("androidProgramJar") {
    group = "distribution"
    archiveFileName.set("rwpp-api-program.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/api-extension"))
    dependsOn("compileAndroidMain")
    from(layout.buildDirectory.dir("classes/kotlin/android/main"))
    from({ apiAndroidRuntimeJars.files.filter { it.isFile }.map { zipTree(it) } })
    from({ apiAndroidRuntimeAars.files.filter { it.isFile }.map { zipTree(it) } })
    from("src/commonMain/resources")
    from("src/androidMain/resources")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**", "module-info.class")
}

val apiD8 = configurations.create("apiD8") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies { add(apiD8.name, "com.android.tools:r8:9.1.56") }
val apiAndroidSdkJar = providers.provider {
    val properties = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
    val sdk = providers.gradleProperty("androidSdkDir").orNull ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME") ?: properties.getProperty("sdk.dir")
        ?: error("Set androidSdkDir or sdk.dir to package the built-in API extension")
    file(sdk).resolve("platforms/android-${libs.versions.android.compileSdk.get()}/android.jar")
}
val androidExtensionDexDirectory = layout.buildDirectory.dir("intermediates/api-extension/dex")
val androidExtensionDex = tasks.register<JavaExec>("androidExtensionDex") {
    group = "distribution"
    dependsOn(androidProgramJar)
    classpath = apiD8
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(androidProgramJar.flatMap { it.archiveFile })
    inputs.files(apiAndroidCompileJars, apiAndroidCompileAars, apiAndroidSdkJar)
    outputs.dir(androidExtensionDexDirectory)
    doFirst {
        val output = androidExtensionDexDirectory.get().asFile
        output.mkdirs()
        output.listFiles().orEmpty().filter { it.isFile && Regex("classes\\d*\\.dex").matches(it.name) }
            .forEach { check(it.delete()) { "Could not replace generated DEX ${it.name}" } }
        setArgs(buildList {
            addAll(listOf("--min-api", libs.versions.android.minSdk.get(), "--output", output.absolutePath, "--lib", apiAndroidSdkJar.get().absolutePath))
            (apiAndroidCompileJars.files + apiAndroidCompileAars.files).filter { it.isFile && it.extension == "jar" }
                .distinct().forEach { addAll(listOf("--classpath", it.absolutePath)) }
            add(androidProgramJar.get().archiveFile.get().asFile.absolutePath)
        })
    }
}
val androidExtensionJar = tasks.register<Jar>("androidExtensionJar") {
    group = "distribution"
    description = "Package API class files for mixin discovery and DEX for the shared Android game loader."
    archiveFileName.set("rwpp-api.jar")
    destinationDirectory.set(layout.buildDirectory.dir("extensions/android"))
    dependsOn(androidExtensionDex)
    from({ zipTree(androidProgramJar.get().archiveFile.get()) })
    from(androidExtensionDexDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doLast { verifyApiArchive(archiveFile.get().asFile, android = true) }
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md")) { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    filesMatching("META-INF/extension.properties") {
        expand("version" to project.version.toString())
    }
}

// Game mappings and sources are published together before Gradle sync/build.
// Keep the Maven component identity so IDEA can attach the sources classifier.

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = if (name == "kotlinMultiplatform") "rwpp-api" else "rwpp-api-$name"
        pom {
            name.set("RWPP API")
            description.set(project.description.toString())
            url.set("https://github.com/RWPP-Team/RWML")
            licenses {
                license {
                    name.set("RWPP-Team Shield License 1.0.0")
                    url.set("https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md")
                }
            }
            developers {
                developer {
                    id.set("rwpp-team")
                    name.set("RWPP Team")
                    email.set("1225327866@qq.com")
                    organization.set("RWPP-Team")
                    organizationUrl.set("https://github.com/RWPP-Team")
                }
            }
            scm {
                connection.set("scm:git://github.com/RWPP-Team/RWML.git")
                developerConnection.set("scm:git:ssh://github.com/RWPP-Team/RWML.git")
                url.set("https://github.com/RWPP-Team/RWML")
            }
        }
    }

    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("central-staging").get().asFile)
        }
        maven {
            name = "centralPortal"
            val snapshot = version.toString().endsWith("SNAPSHOT")
            url = uri(
                if (snapshot) {
                    "https://central.sonatype.com/repository/maven-snapshots/"
                } else {
                    "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
                }
            )
            val user = providers.gradleProperty("centralUsername").orNull
            val secret = providers.gradleProperty("centralPassword").orNull
            if (user != null && secret != null) {
                credentials {
                    username = user
                    password = secret
                }
            }
        }
    }
}

fun gradlePropertyOrNull(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.trim().isNotEmpty() }

fun environmentOrNull(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

signing {
    val inMemoryKey = gradlePropertyOrNull("signingInMemoryKey") ?: environmentOrNull("GPG_PRIVATE_KEY")
    val inMemoryPassword =
        gradlePropertyOrNull("signingInMemoryPassword") ?: environmentOrNull("GPG_PASSPHRASE")
    val keyId = gradlePropertyOrNull("signing.gnupg.keyName") ?: gradlePropertyOrNull("signing.keyId")

    isRequired = (inMemoryKey != null || keyId != null) && !version.toString().endsWith("SNAPSHOT")
    if (inMemoryKey != null) {
        useInMemoryPgpKeys(inMemoryKey, inMemoryPassword)
        publishing.publications.withType<MavenPublication>().configureEach { sign(this) }
    } else if (keyId != null) {
        useGpgCmd()
        publishing.publications.withType<MavenPublication>().configureEach { sign(this) }
    }
}
