/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension

val libraryName = when (project.name) {
    "rwpp-api" -> "RWPP API"
    "rwpp-client" -> "RWPP Client"
    else -> error("Unexpected Central publication project ${project.path}")
}

val publishing = extensions.getByType<PublishingExtension>()
publishing.publications.withType<MavenPublication>().configureEach {
    groupId = "io.github.rwpp-team"
    artifactId = when (name) {
        "kotlinMultiplatform" -> project.name
        "jvm" -> "${project.name}-jvm"
        "android", "androidRelease" -> "${project.name}-android"
        else -> error("Unexpected Central publication ${project.path}:$name")
    }
    val publicationName = name
    val artifactName = artifactId
    val output = layout.buildDirectory.file("javadoc/$publicationName/README.txt")
    val prepareJavadoc = tasks.register("prepare${publicationName.replaceFirstChar(Char::uppercaseChar)}Javadoc") {
        inputs.property("artifact", artifactName)
        inputs.property("version", project.version.toString())
        outputs.file(output)
        doLast {
            output.get().asFile.apply {
                parentFile.mkdirs()
                writeText("$libraryName ${project.version}. See $artifactName-${project.version}-sources.jar for Kotlin KDoc.\n")
            }
        }
    }
    artifact(tasks.register<Jar>("${publicationName}JavadocJar") {
        dependsOn(prepareJavadoc)
        archiveBaseName.set(artifactName)
        archiveClassifier.set("javadoc")
        from(output)
    })
    pom {
        name.set(libraryName)
        description.set(project.description.toString())
        url.set("https://github.com/RWPP-Team/RWPP")
        licenses {
            license {
                name.set("RWPP-Team Shield License 1.0.0")
                url.set("https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md")
            }
        }
        developers {
            developer {
                id.set("rwpp-team")
                name.set("RWPP Team")
                email.set("1225327866@qq.com")
                organization.set("RWPP-Team")
                organizationUrl.set("https://github.com/RWPP-Team")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/RWPP-Team/RWPP.git")
            developerConnection.set("scm:git:ssh://git@github.com/RWPP-Team/RWPP.git")
            url.set("https://github.com/RWPP-Team/RWPP")
        }
    }
}
publishing.repositories.maven {
    name = "localStaging"
    url = uri(layout.buildDirectory.dir("central-staging"))
}

fun property(name: String) = providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)
fun environment(name: String) = providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)

extensions.configure<SigningExtension> {
    val privateKey = property("signingInMemoryKey") ?: environment("GPG_PRIVATE_KEY")
    val password = property("signingInMemoryPassword") ?: environment("GPG_PASSPHRASE")
    val keyId = property("signing.gnupg.keyName")
    isRequired = (privateKey != null || keyId != null) && !version.toString().endsWith("SNAPSHOT")
    if (privateKey != null || keyId != null) {
        if (privateKey != null) useInMemoryPgpKeys(privateKey, password) else useGpgCmd()
        sign(publishing.publications)
    }
}
