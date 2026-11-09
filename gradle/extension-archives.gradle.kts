import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.Properties
import java.util.zip.ZipFile

// Shared packaging for the two independent RWML extensions. Dependencies owned
// by API remain in API's archive and are reached through RWML's dependency loader.
val isApi = project.name == "rwpp-api"
val api = project(":rwpp-api")
val extensionName = project.name
val sourceDescriptor = file("src/commonMain/resources/META-INF/extension.properties")
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
// ServiceLoader runs in the provider's loader. Keep platform services beside
// their API, but only in extension archives: server must not acquire Swing.
if (isApi) listOf("jvm", "android").forEach { platform ->
    val base = configurations.getByName(if (platform == "jvm") "jvmRuntimeClasspath" else "androidRuntimeClasspath")
    val runtime = configurations.create("extension${platform.replaceFirstChar(Char::uppercaseChar)}RuntimeClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
        extendsFrom(base)
        base.attributes.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            attributes.attribute(key as Attribute<Any>, base.attributes.getAttribute(key)!!)
        }
    }
    dependencies.add(
        runtime.name,
        catalog.findLibrary(if (platform == "jvm") "slf4j-simple" else "logback-android").get()
    )
    val coroutines = catalog.findVersion("coroutines").get().requiredVersion
    dependencies.add(
        runtime.name,
        "org.jetbrains.kotlinx:kotlinx-coroutines-${if (platform == "jvm") "swing" else "android"}:$coroutines"
    )
}
val descriptorDirectory = layout.buildDirectory.dir("generated/extension-descriptor")
val generateExtensionDescriptor = tasks.register("generateExtensionDescriptor") {
    inputs.file(sourceDescriptor)
    inputs.property("version", provider { project.version.toString() })
    inputs.property("apiVersion", provider { api.version.toString() })
    outputs.dir(descriptorDirectory)
    doLast {
        descriptorDirectory.get().file("META-INF/extension.properties").asFile.apply {
            parentFile.mkdirs()
            writeText(
                sourceDescriptor.readText().replace("\${version}", project.version.toString())
                    .replace("\${apiVersion}", api.version.toString()), Charsets.UTF_8
            )
        }
    }
}
tasks.withType<ProcessResources>().configureEach {
    filesMatching("META-INF/extension.properties") {
        expand("version" to project.version.toString(), "apiVersion" to api.version.toString())
    }
}

fun moduleKey(id: ComponentIdentifier): String? =
    (id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}" }

fun suppliedByHost(id: ComponentIdentifier): Boolean {
    val module = id as? ModuleComponentIdentifier ?: return false
    return module.group == "org.jetbrains.kotlin" && module.module.startsWith("kotlin-stdlib") ||
            module.group == "io.github.rwpp-team" && module.module in setOf("rwml-core", "game-lib") ||
            module.group == "kim.der.relay-cn" || module.group == "org.ow2.asm"
}

fun runtimeArtifacts(configuration: String, type: String, include: (ComponentIdentifier) -> Boolean) =
    files(provider {
        configurations.getByName(configuration).incoming.artifactView {
            attributes { attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, type) }
            componentFilter(include)
        }.files
    })

fun ownRuntime(platform: String, type: String): FileCollection {
    val apiConfiguration = "extension${platform.replaceFirstChar(Char::uppercaseChar)}RuntimeClasspath"
    val configuration =
        if (isApi) apiConfiguration else if (platform == "jvm") "jvmRuntimeClasspath" else "releaseRuntimeClasspath"
    val shared = provider {
        if (isApi) emptySet() else api.configurations.getByName(apiConfiguration)
            .incoming.resolutionResult.allComponents.mapNotNull { moduleKey(it.id) }.toSet()
    }
    return runtimeArtifacts(configuration, type) { id ->
        !suppliedByHost(id) &&
                !(id is ProjectComponentIdentifier && id.projectPath == ":rwpp-api") &&
                moduleKey(id)?.let { it !in shared.get() } != false
    }
}

val jvmRuntime = ownRuntime("jvm", "jar")
val jvmClasses = tasks.named<Jar>("jvmJar")
val verifyExtension: (File, Boolean) -> Unit = { archive, android ->
    if (!isApi) ZipFile(archive).use { zip ->
        check((zip.getEntry("io/github/rwpp/mixin/hud/NativeFpsHudMixin.class") != null) == !android) {
            "The native desktop FPS hook must only be shipped in the JVM client"
        }
    }
    ZipFile(archive).use { zip ->
        val descriptor = Properties().apply {
            zip.getInputStream(checkNotNull(zip.getEntry("META-INF/extension.properties"))).reader(Charsets.UTF_8)
                .use(::load)
        }
        val expectedId = if (isApi) "io.github.rwpp.api" else "io.github.rwpp"
        check(descriptor.getProperty("id") == expectedId) { "Wrong extension descriptor in $archive" }
        check(zip.getEntry(descriptor.getProperty("mainClass").replace('.', '/') + ".class") != null)
        check((zip.getEntry("classes.dex") != null) == android)
        check(zip.entries().asSequence().none {
            it.name.startsWith("io/github/rwml/") || it.name.startsWith("kim/der/") ||
                    it.name.startsWith("org/objectweb/asm/") || it.name.startsWith("kotlin/jvm/") ||
                    it.name.startsWith("com/corrodinggames/") || it.name.startsWith("io/netty/") || it.name.startsWith("kcp/")
        }) { "Host or server classes leaked into $archive" }
        if (!isApi) {
            check(descriptor.getProperty("ui-framework") == "io.github.rwpp.ui.RwppUiFramework")
            check(descriptor.getProperty("dependsOn").contains("io.github.rwpp.api"))
            check(zip.getEntry("io/github/rwpp/api/ApiRuntime.class") == null) { "Client must not embed API" }
            check(zip.getEntry("org/koin/core/Koin.class") == null) { "API owns the shared Koin runtime" }
            check(zip.getEntry("io/github/rwpp/ui/RwppUiFramework.class") != null)
            check(zip.getEntry("org/koin/compose/KoinApplicationKt.class") != null)
            check(zip.getEntry("composeResources/io.github.rwpp.generated/drawable/logo.png") != null)
        } else {
            check(descriptor.getProperty("ui-framework").isNullOrBlank())
            check(zip.getEntry("org/koin/core/Koin.class") != null)
            val service = checkNotNull(zip.getEntry("META-INF/services/org.slf4j.spi.SLF4JServiceProvider"))
            val provider = zip.getInputStream(service).reader(Charsets.UTF_8).use { it.readText().trim() }
            check(provider == if (android) "org.slf4j.impl.LoggerServiceProvider" else "org.slf4j.simple.SimpleServiceProvider")
            check(
                zip.getEntry(
                    if (android) "kotlinx/coroutines/android/AndroidDispatcherFactory.class"
                    else "kotlinx/coroutines/swing/SwingDispatcherFactory.class"
                ) != null
            )
            val dispatcherService =
                checkNotNull(zip.getEntry("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory"))
            val dispatcherFactories = zip.getInputStream(dispatcherService).reader(Charsets.UTF_8).use { it.readText() }
            check(
                dispatcherFactories.contains(
                    if (android) "kotlinx.coroutines.android.AndroidDispatcherFactory"
                    else "kotlinx.coroutines.swing.SwingDispatcherFactory"
                )
            )
        }
    }
}

val rwppJvmExtensionJar = tasks.register<Jar>("rwppJvmExtensionJar") {
    group = "distribution"
    description = "Build the standalone $extensionName JVM extension."
    archiveBaseName.set(extensionName)
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("jvm")
    destinationDirectory.set(layout.buildDirectory.dir("extensions"))
    dependsOn(jvmClasses, generateExtensionDescriptor)
    from({ zipTree(jvmClasses.get().archiveFile.get().asFile) }) { exclude("META-INF/extension.properties") }
    from({ jvmRuntime.files.map { zipTree(it) } }) { exclude("META-INF/extension.properties") }
    from(descriptorDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "META-INF/MANIFEST.MF",
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA",
        "META-INF/versions/**",
        "module-info.class"
    )
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doLast { verifyExtension(archiveFile.get().asFile, false) }
}

val androidRuntimeJars = ownRuntime("android", "jar")
val androidRuntimeAars = ownRuntime("android", "android-classes-jar")
val androidCompileConfiguration = if (isApi) "androidCompileClasspath" else "releaseCompileClasspath"
val androidCompileJars = runtimeArtifacts(androidCompileConfiguration, "jar") { true }
val androidCompileAars = runtimeArtifacts(androidCompileConfiguration, "android-classes-jar") { true }
val androidClassesName = if (isApi) "createFullJarAndroidMain" else "createFullJarRelease"
val androidClasses = provider { tasks.getByName(androidClassesName) }
val androidProgram = tasks.register<Jar>("rwppAndroidProgramJar") {
    group = "build"
    archiveFileName.set("$extensionName-android-program.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/extension"))
    dependsOn(androidClassesName)
    from({ androidClasses.get().outputs.files.files.map { zipTree(it) } }) { exclude("META-INF/extension.properties") }
    from({ androidRuntimeJars.files.map { zipTree(it) } }) { exclude("META-INF/extension.properties") }
    from({ androidRuntimeAars.files.map { zipTree(it) } }) { exclude("META-INF/extension.properties") }
    from("src/commonMain/resources") { exclude("META-INF/extension.properties") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "META-INF/MANIFEST.MF",
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA",
        "META-INF/versions/**",
        "module-info.class"
    )
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val d8 = configurations.create("extensionD8") { isCanBeConsumed = false; isCanBeResolved = true }
dependencies.add(d8.name, catalog.findLibrary("r8").get())
val androidSdkJar = provider {
    val local =
        Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load) }
    val sdk = providers.gradleProperty("androidSdkDir").orNull ?: System.getenv("ANDROID_SDK_ROOT")
    ?: System.getenv("ANDROID_HOME") ?: local.getProperty("sdk.dir") ?: error("Set androidSdkDir or sdk.dir")
    val level = (project.findProperty("androidCompileSdk") as String?)
        ?: extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("android-compileSdk")
            .get().requiredVersion
    file(sdk).resolve("platforms/android-$level/android.jar").also { check(it.isFile) { "Missing Android SDK: $it" } }
}
val dexDirectory = layout.buildDirectory.dir("dex/extension")
val androidDex = tasks.register<JavaExec>("rwppAndroidExtensionDex") {
    group = "distribution"
    dependsOn(androidProgram)
    if (!isApi) dependsOn(":rwpp-api:rwppAndroidProgramJar")
    classpath = d8
    mainClass.set("com.android.tools.r8.D8")
    inputs.file(androidProgram.flatMap { it.archiveFile })
    inputs.files(androidCompileJars, androidCompileAars, androidSdkJar)
    if (!isApi) inputs.file(provider { api.tasks.named<Jar>("rwppAndroidProgramJar").get().archiveFile.get().asFile })
    outputs.dir(dexDirectory)
    doFirst {
        val directory = dexDirectory.get().asFile.canonicalFile
        val buildRoot = layout.buildDirectory.get().asFile.canonicalFile.toPath()
        require(directory.toPath().startsWith(buildRoot) && directory.toPath() != buildRoot)
        project.delete(directory)
        directory.mkdirs()
        val compileFiles =
            (androidCompileJars.files + androidCompileAars.files).filter { it.isFile && it.extension == "jar" }
                .toMutableSet()
        if (!isApi) compileFiles.add(api.tasks.named<Jar>("rwppAndroidProgramJar").get().archiveFile.get().asFile)
        setArgs(buildList {
            addAll(
                listOf(
                    "--release",
                    "--min-api",
                    "26",
                    "--output",
                    directory.absolutePath,
                    "--lib",
                    androidSdkJar.get().absolutePath
                )
            )
            compileFiles.forEach { addAll(listOf("--classpath", it.absolutePath)) }
            add(androidProgram.get().archiveFile.get().asFile.absolutePath)
        })
    }
}
val androidServicesDirectory = layout.buildDirectory.dir("generated/extension-android-services")
val androidServices = if (isApi) tasks.register("generateExtensionAndroidServices") {
    outputs.dir(androidServicesDirectory)
    doLast {
        androidServicesDirectory.get().file("META-INF/services/org.slf4j.spi.SLF4JServiceProvider").asFile.apply {
            parentFile.mkdirs()
            writeText("org.slf4j.impl.LoggerServiceProvider\n", Charsets.UTF_8)
        }
    }
} else null
val rwppAndroidExtensionJar = tasks.register<Jar>("rwppAndroidExtensionJar") {
    group = "distribution"
    description = "Build the standalone $extensionName Android extension (classes, DEX and resources)."
    archiveBaseName.set(extensionName)
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("android")
    destinationDirectory.set(layout.buildDirectory.dir("extensions"))
    dependsOn(androidDex, generateExtensionDescriptor)
    from({ zipTree(androidProgram.get().archiveFile.get().asFile) })
    from(dexDirectory)
    from(descriptorDirectory)
    if (androidServices != null) {
        dependsOn(androidServices)
        from(androidServicesDirectory)
    }
    if (!isApi) {
        dependsOn("copyReleaseComposeResourcesToAndroidAssets")
        from(layout.buildDirectory.dir("generated/assets/copyReleaseComposeResourcesToAndroidAssets"))
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doLast { verifyExtension(archiveFile.get().asFile, true) }
}
