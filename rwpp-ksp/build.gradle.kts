/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    id("com.github.gmazzo.buildconfig")
    id("maven-publish")
}

group = "io.github.rwpp"

kotlin {
    jvmToolchain(21)
    jvm() {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
                implementation("org.javassist:javassist:3.30.2-GA")

                api(project(":rwpp-core-api"))
                //compileOnly(project(":desktop"))
            }
            kotlin.srcDir("src/main/kotlin")
            resources.srcDir("src/main/resources")
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                group = "io.github.rwpp"
                artifactId = "ksp"
                version = rootProject.version.toString()

                from(components.getByName("kotlin"))
            }
        }
    }
}