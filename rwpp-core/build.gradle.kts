/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

group = "io.github.rwpp"

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

kotlin {
    androidTarget()
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(compose.preview)
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.components.resources)
                api(compose.ui)
                api(project(":rwpp-core-api"))
                api("org.jetbrains.compose.material:material-icons-core:1.7.3")
                val koinVersion = findProperty("koin.version") as String
                val koinAnnotationsVersion = findProperty("koin.annotations.version") as String
                api("io.insert-koin:koin-annotations:${koinAnnotationsVersion}")
                api("io.insert-koin:koin-compose:${koinVersion}")
                val markdownVersion = findProperty("markdown.version") as String
                api("net.peanuuutz.tomlkt:tomlkt:0.3.7")
                implementation("com.eclipsesource.minimal-json:minimal-json:0.9.5")
                implementation("com.mikepenz:multiplatform-markdown-renderer:$markdownVersion")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:$markdownVersion")
                implementation("party.iroiro.luajava:luajava:4.0.2")
                implementation("party.iroiro.luajava:lua54:4.0.2")
                implementation("sh.calvin.reorderable:reorderable:2.4.3")
                api("io.coil-kt.coil3:coil-compose:3.2.0")
                api("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
            }
        }

        commonMain.kotlin.srcDirs("build/generated/ksp/main/kotlin")

        val androidMain by getting {
            dependencies {
                api("androidx.activity:activity-compose:1.10.1")
                api("androidx.appcompat:appcompat:1.7.1")
                api("androidx.core:core-ktx:1.16.0")
            }
        }

        val desktopMain by getting {
            dependencies {
                compileOnly(fileTree(
                    "dir" to "$rootDir/lib",
                    "include" to "lwjgl.jar"
                ))
                implementation(compose.desktop.common)
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                group = "io.github.rwpp"
                artifactId = "core"
                version = rootProject.version.toString()

                from(components.getByName("kotlin"))
            }
        }
    }
}

android {
    compileSdk = (findProperty("android.compileSdk") as String).toInt()
    buildToolsVersion = "34.0.0"
    namespace = "io.github.rwpp"

    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-test-junit:" + findProperty("kotlin.version") as String)
    val koinAnnotationsVersion = findProperty("koin.annotations.version") as String
    ksp("io.insert-koin:koin-ksp-compiler:$koinAnnotationsVersion")
}
