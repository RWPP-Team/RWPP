/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

rootProject.name = "RWPP"

include(":rwpp-api", ":rwpp-server", ":rwpp-client")


pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

fun resolveCurrentGameLibraryRepository(baseRepository: java.io.File): java.io.File {
    val base = baseRepository.toPath().toAbsolutePath().normalize()
    val pointer = base.resolve("current-repository.txt")
    if (!java.nio.file.Files.isRegularFile(pointer)) return base.toFile()

    val relative = java.nio.file.Files.readString(pointer, Charsets.UTF_8).trim()
    require(relative.isNotEmpty()) { "${pointer.toFile()} is empty." }
    val relativePath = java.nio.file.Path.of(relative)
    require(!relativePath.isAbsolute) { "${pointer.toFile()} must contain a relative repository path." }
    val current = base.resolve(relativePath).normalize()
    require(current.startsWith(base)) { "${pointer.toFile()} escapes the local game repository." }
    require(java.nio.file.Files.isDirectory(current)) {
        "The current game library repository does not exist: $current"
    }
    return current.toFile()
}

val rwmlLocalGameRepository = providers.gradleProperty("rwmlLocalRepo").orNull
    ?.let { resolveCurrentGameLibraryRepository(java.io.File(it)) }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal {
            content {
                includeModule("io.github.rwpp-team", "rwml-core")
                includeModule("io.github.rwpp-team", "rwml-desktop")
            }
        }
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        rwmlLocalGameRepository?.let { localRepository ->
            exclusiveContent {
                forRepository {
                    maven {
                        name = "RWMLLocal"
                        url = uri(localRepository)
                    }
                }
                filter {
                    includeModule("io.github.rwpp-team", "game-lib")
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
