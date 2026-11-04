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
        val properties = Properties().apply { rootProject.file("local.properties").inputStream().use(::load) }
        val sdk = file(properties.getProperty("sdk.dir"))
        val android =
            listOf("android-37.0", "android-37", "android-36").map { sdk.resolve("platforms/$it/android.jar") }
                .first { it.isFile }
        val tools = sdk.resolve("build-tools").listFiles()!!.sortedByDescending { it.name }
            .first { it.resolve("aapt2.exe").isFile }
        val list = output.resolve("aars.txt").apply {
            writeText(resourceAars.files.filter { it.extension == "aar" }.sortedBy { it.absolutePath }
                .joinToString("\n") { it.absolutePath })
        }
        args(tools.resolve("aapt2.exe").absolutePath, android.absolutePath, output.absolutePath, list.absolutePath)
    }
}
tasks.named<Jar>("rwppAndroidProgramJar") {
    dependsOn(bundleResources)
    from(bundle.map { it.dir("classes") })
    from(bundle.map { it.dir("resources") })
}
