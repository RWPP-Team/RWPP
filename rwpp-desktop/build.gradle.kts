
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
                "'-cp \$ROOTDIR/generated_lib/*;\$ROOTDIR/extension/*;\$ROOTDIR/app/*;\$ROOTDIR/libs/*'"
            )

            windows {
                iconFile.set(project.file("logo.ico"))
                upgradeUuid = guid
            }

            linux {
                iconFile.set(project.file("logo.png"))
            }

            args += listOf("-native")
        }
    }
}

task("packageWixDistribution") {
    dependsOn("createReleaseDistributable")

    doLast {
        val process = Runtime.getRuntime().exec(
            "dotnet run --project=${rootProject.rootDir.absolutePath + "\\wix"} $guid ${rootProject.version}"
        )

        var line: String?
        while(true) {
            line = process.inputReader().readLine() ?: break
            logger.lifecycle(line)
        }
    }
}



