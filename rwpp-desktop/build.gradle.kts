
/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Native packs are built per host OS (MSI on Windows, DMG on macOS, Deb on Linux).
val classpathSeparator =
    if (System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)) ";" else ":"

ksp {
    arg("outputDir", project.buildDir.absolutePath + "/generated")
    arg("lib", "game-lib")
    arg("libDir", "$rootDir/lib")
    arg("pathType", "Path")
}

dependencies {
    api(project(":rwpp-core"))
    implementation(compose.desktop.macos_arm64)
    implementation(compose.desktop.windows_x64)
    implementation(compose.desktop.linux_x64)
    implementation("org.slf4j:slf4j-simple:2.0.16")
    compileOnly(fileTree(
        "dir" to rootDir.absolutePath + "/lib",
        "include" to "*.jar",
        "exclude" to listOf("android-game-lib.jar", "android.jar")
    ))

    runtimeOnly("party.iroiro.luajava:lua54-platform:4.0.2:natives-desktop")
    implementation("org.javassist:javassist:3.30.2-GA")
    val koinAnnotationsVersion = findProperty("koin.annotations.version") as String
    ksp("io.insert-koin:koin-ksp-compiler:$koinAnnotationsVersion")
    ksp(project(":rwpp-ksp"))
    testImplementation(kotlin("test"))
}

sourceSets.main {
    java.srcDirs("build/generated/ksp/main/kotlin")
    resources.srcDir(project.buildDir.absolutePath + "/generated")
    resources.include("config.toml")
}

val guid = "abc38343-cdb8-4e3f-aa7f-0ead99385de1"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

compose.desktop {
    application {
        mainClass = "io.github.rwpp.desktop.MainKt"
        buildTypes {
            release {
                proguard.isEnabled.set(false)
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)

            modules("jdk.unsupported")

            packageName = "RWPP"
            packageVersion = rootProject.version.toString()
            mainClass = "io.github.rwpp.desktop.MainKt"
            vendor = "RWPP Contributors"
            description = "Multiplatform launcher for Rusted Warfare"
            copyright = "Copyright 2023-2025 RWPP contributors"
            licenseFile.set(rootProject.file("LICENSE"))

            jvmArgs += listOf(
                "-Djava.net.preferIPv4Stack=true",
             //   "-Xmx2000M",
                "-Dfile.encoding=UTF-8",
                "-Djava.library.path=\$ROOTDIR",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "'-cp \$ROOTDIR/generated_lib/*${classpathSeparator}\$ROOTDIR/extension/*${classpathSeparator}\$ROOTDIR/app/*${classpathSeparator}\$ROOTDIR/libs/*'"
            )

            windows {
                iconFile.set(project.file("logo.ico"))
                upgradeUuid = guid
            }

            linux {
                iconFile.set(project.file("logo.png"))
            }

            macOS {
                iconFile.set(project.file("logo.png"))
            }

            args += listOf("-native")
        }
    }
}

task("packageWixDistribution") {
    dependsOn("createReleaseDistributable")

    doLast {
        val wixProject = rootProject.rootDir.resolve("wix").absolutePath
        val process = Runtime.getRuntime().exec(
            arrayOf("dotnet", "run", "--project=$wixProject", guid, rootProject.version.toString())
        )

        var line: String?
        while(true) {
            line = process.inputReader().readLine() ?: break
            logger.lifecycle(line)
        }
    }
}

// Uber-jar default name on macOS for launcher.sh.
// Compose Desktop always sets archiveBaseName/appendix/version; rename after package.
if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
    tasks.configureEach {
        if (name != "packageUberJarForCurrentOS" && name != "packageReleaseUberJarForCurrentOS") {
            return@configureEach
        }
        doLast {
            val produced = outputs.files.singleFile
            val renamed = File(produced.parentFile, "RWPP-macos.jar")
            if (produced.absolutePath == renamed.absolutePath) return@doLast
            if (renamed.exists() && !renamed.delete()) {
                throw GradleException("Cannot replace existing ${renamed.absolutePath}")
            }
            if (!produced.renameTo(renamed)) {
                produced.copyTo(renamed, overwrite = true)
                if (!produced.delete()) {
                    logger.warn("Left original uber-jar at ${produced.absolutePath}")
                }
            }
            logger.lifecycle("Renamed uber-jar to ${renamed.canonicalPath}")
        }
    }
}



