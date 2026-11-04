/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

plugins {
    kotlin("jvm")
    application
    `maven-publish`
}

description = "RWPP native headless server launcher."
group = "io.github.rwpp-team"

val serverRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Runtime-only game and native master-server HTTP libraries for the standalone distribution."
}

// Only the small source compatibility contracts under src/main/kotlin/android are needed.
// SDK stubs throw "Stub!" and must never shadow the game's Android implementations.
val clientNetworkRuntimeJar = project(":rwpp-client").tasks.named("networkRuntimeJar")

dependencies {
    implementation(project(":rwpp-api")) {
        exclude(group = "io.github.rwpp-team", module = "rwml-core")
    }
    implementation(libs.rwml.desktop)
    implementation(libs.jline)
    implementation(libs.bundles.netty)
    runtimeOnly(libs.slf4j.simple)
    compileOnly(libs.game.lib)
    testImplementation(kotlin("test"))
    testImplementation(libs.game.lib)
    testImplementation(libs.asm.framework)
    testImplementation(libs.asm)
    testImplementation(libs.kcp.get().toString()) {
        exclude(group = "io.netty", module = "netty-all")
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    testImplementation(files(clientNetworkRuntimeJar))
    add(serverRuntime.name, libs.game.lib)
    add(serverRuntime.name, "org.apache.httpcomponents:httpclient:4.5.13")
}

// The game stays compileOnly for project consumers. Only the server application runtime and its
// generated distribution add the mapped game coordinate.
configurations.named("runtimeClasspath") {
    extendsFrom(serverRuntime)
}

application {
    mainClass.set("io.github.rwpp.server.Main")
    // Mixin 依赖宿主 JAR 自带的 ASM agent。桌面启动器以 -javaagent 重启进程，服务器没有重启步骤，
    // 因此允许当前进程自行附加。
    applicationDefaultJvmArgs = listOf("-Djdk.attach.allowAttachSelf=true")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

publishing {
    publications {
        create<MavenPublication>("server") {
            from(components["java"])
            artifactId = "rwpp-server"
        }
    }
}

tasks.test {
    systemProperty("rwpp.server.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}

tasks.register<Jar>("networkBenchmarkJar") {
    group = "verification"
    description = "Package opt-in server transport benchmarks."
    dependsOn(tasks.testClasses)
    archiveFileName.set("rwpp-network-benchmark.jar")
    from(sourceSets.test.get().output, sourceSets.main.get().output)
    from({ sourceSets.test.get().runtimeClasspath.filter { it.isFile && it.extension == "jar" }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    manifest.attributes["Main-Class"] = "io.github.rwpp.api.net.impl.NetworkBenchmark"
}

// Check the resolved graph, including transitives, rather than relying on exclusions in the host.
val verifyServerDependencies = tasks.register("verifyServerDependencies") {
    group = "verification"
    description = "Reject client modules, audio, rendering and UI libraries from the server runtime."
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().incoming.resolutionResult.allComponents.mapNotNull { component ->
            when (val id = component.id) {
                is ProjectComponentIdentifier -> id.takeIf {
                    it.projectPath == ":rwpp-client"
                }?.displayName
                is ModuleComponentIdentifier -> id.takeIf {
                    it.group.startsWith("com.soywiz") || it.group.startsWith("korlibs") || it.group == "org.korge" ||
                        it.group.startsWith("org.jetbrains.compose") || it.group.startsWith("androidx.compose") ||
                        it.group.startsWith("org.lwjgl") || it.group == "io.github.rwpp-team" &&
                        it.module.startsWith("rwpp-client")
                }?.displayName
                else -> null
            }
        }
        check(forbidden.isEmpty()) { "Client dependencies in server runtime: ${forbidden.joinToString()}" }
    }
}

tasks.named("check") { dependsOn(verifyServerDependencies) }
tasks.named("installDist") { dependsOn(verifyServerDependencies) }
tasks.named("distZip") { dependsOn(verifyServerDependencies) }
tasks.named("distTar") { dependsOn(verifyServerDependencies) }

// The API ships inside this application's class path and is declared as a built-in extension, so there
// is no archive to copy into the distribution and no DEX to build.

