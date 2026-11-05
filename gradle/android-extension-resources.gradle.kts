import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import java.util.Properties

val resourceAars = files(provider {
    configurations.getByName("releaseRuntimeClasspath").incoming.artifactView {
        attributes { attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar") }
        componentFilter { it is ModuleComponentIdentifier }
        isLenient = true
    }.files
})
val helper = tasks.register<JavaCompile>("compileAndroidResourceBundler") {
    source(rootProject.file("build-support/java/BundleAndroidResources.java"))
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("android-resource-bundler"))
    options.release.set(17)
}
val bundle = layout.buildDirectory.dir("android-extension-resources")
val bundleResources = tasks.register<JavaExec>("bundleAndroidExtensionResources") {
    dependsOn(helper)
    classpath = files(helper.flatMap { it.destinationDirectory })
    mainClass.set("BundleAndroidResources")
    inputs.files(resourceAars)
    outputs.dir(bundle)
    doFirst {
        val output = bundle.get().asFile.canonicalFile
        check(output.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        output.deleteRecursively(); output.mkdirs()
        // CI has no local.properties and uses a Linux SDK, so resolve the SDK the
        // same way as the archive script and accept both aapt2 executable names.
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
        }
        val sdk = file(
            providers.gradleProperty("androidSdkDir").orNull
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: System.getenv("ANDROID_HOME")
                ?: local.getProperty("sdk.dir")
                ?: error("Set androidSdkDir, sdk.dir, ANDROID_SDK_ROOT or ANDROID_HOME")
        )
        val android =
            listOf("android-37.0", "android-37", "android-36").map { sdk.resolve("platforms/$it/android.jar") }
                .firstOrNull { it.isFile } ?: error("No supported Android platform in ${sdk.resolve("platforms")}")
        val tools = sdk.resolve("build-tools").listFiles().orEmpty().sortedByDescending { it.name }
            .first { it.resolve("aapt2").isFile || it.resolve("aapt2.exe").isFile }
        val aapt2 = listOf("aapt2", "aapt2.exe").map { tools.resolve(it) }.first { it.isFile }
        val list = output.resolve("aars.txt").apply {
            writeText(resourceAars.files.filter { it.extension == "aar" }.sortedBy { it.absolutePath }
                .joinToString("\n") { it.absolutePath })
        }
        args(aapt2.absolutePath, android.absolutePath, output.absolutePath, list.absolutePath)
    }
}
tasks.named<Jar>("rwppAndroidProgramJar") {
    dependsOn(bundleResources)
    from(bundle.map { it.dir("classes") })
    from(bundle.map { it.dir("resources") })
}
