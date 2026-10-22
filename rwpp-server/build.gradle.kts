/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
    application
}

description = "RWPP native headless server launcher."

val serverRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Runtime-only game and native master-server HTTP libraries for the standalone distribution."
}

dependencies {
    implementation(project(":rwpp-api")) {
        exclude(group = "io.github.rwpp-team", module = "rwml-core")
    }
    implementation(libs.rwml.desktop)
    implementation(libs.jline)
    compileOnly(libs.game.lib)
    add(serverRuntime.name, libs.game.lib)
    add(serverRuntime.name, "org.apache.httpcomponents:httpclient:4.5.13")
}

// The game stays compileOnly for project consumers. Only the server application runtime and its
// generated distribution add the mapped game coordinate.
configurations.named("runtimeClasspath") {
    extendsFrom(serverRuntime)
}

application {
    mainClass.set("io.github.rwml.server.Main")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(":rwpp-api:jvmExtensionJar")
    from(project(":rwpp-api").layout.buildDirectory.file("extensions/jvm/rwpp-api.jar")) {
        into("META-INF/rwml/extensions")
    }
}

