/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

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
            implementation(libs.minimal.json)
            implementation(libs.kotlin.stdlib)

            compileOnly(libs.game.lib)
            compileOnly(libs.asm.framework)
            compileOnly("org.ow2.asm:asm-tree:9.10.1")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.game.lib)
            implementation(libs.asm.framework)
            implementation(libs.asm)
            implementation(libs.rwml.core)
        }
    }
}

// 描述符里的版本在资源处理阶段展开，因此类路径上直接使用 classes 目录时也能读到真实版本。
val verifyApiDependencies = tasks.register("verifyApiDependencies") {
    val runtime = configurations.named("jvmRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().incoming.resolutionResult.allComponents.mapNotNull { component ->
            (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.takeIf {
                it.group.startsWith("io.netty") || it.group == "com.github.l42111996"
            }?.displayName
        }
        check(forbidden.isEmpty()) { "Transport runtime leaked into API: ${forbidden.joinToString()}" }
    }
}
tasks.named("check") { dependsOn(verifyApiDependencies) }

tasks.withType<ProcessResources>().configureEach {
    filesMatching("META-INF/extension.properties") {
        expand("version" to project.version.toString())
    }
}

tasks.named<ProcessResources>("jvmProcessResources") {
    // 内置扩展使用独占路径，避免类路径中其他扩展的通用描述符遮蔽本模块。
    from("src/commonMain/resources/META-INF/extension.properties") {
        into("META-INF/rwpp-api")
        expand("version" to project.version.toString())
    }
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md")) { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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
