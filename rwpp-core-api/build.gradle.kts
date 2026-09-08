/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

group = "io.github.rwpp"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.gmazzo.buildconfig")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

group = "io.github.rwpp"

buildConfig {
    buildConfigField("VERSION", rootProject.version.toString())
}

dependencies {
    val koinVersion = findProperty("koin.version") as String
    val koinAnnotationsVersion = findProperty("koin.annotations.version") as String
    api("io.insert-koin:koin-core:$koinVersion")
    api("io.insert-koin:koin-annotations:${koinAnnotationsVersion}")
    api("org.slf4j:slf4j-api:2.0.16")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${findProperty("kotlin.coroutines.version")}")
    api("org.jetbrains.kotlin:kotlin-reflect:${findProperty("kotlin.version")}")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.eclipsesource.minimal-json:minimal-json:0.9.5")
    implementation("net.peanuuutz.tomlkt:tomlkt:0.3.7")
    compileOnly("org.javassist:javassist:3.30.2-GA")
    ksp("io.insert-koin:koin-ksp-compiler:$koinAnnotationsVersion")
}

tasks.kotlinSourcesJar {
    archiveClassifier.set("sources")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                group = "io.github.rwpp"
                artifactId = "api"
                version = rootProject.version.toString()

                from(components.getByName("kotlin"))
                artifact(tasks.kotlinSourcesJar)
            }
        }
    }
}