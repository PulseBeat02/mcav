plugins {
    id("maven-publish")
    id("com.gradleup.shadow") version "8.3.6"
}

dependencies {
    implementation("org.apache.maven.resolver:maven-resolver-impl:2.0.9")
    implementation("org.apache.maven.resolver:maven-resolver-supplier:2.0.0-alpha-8")
}

tasks {

    java {
        withSourcesJar()
        withJavadocJar()
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    assemble {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        val relocations = listOf(
            "com.ctc",
            "jakarta.inject",
            "org.apache",
            "org.codehaus",
            "org.eclipse",
            "org.slf4j"
        )
        relocations.forEach { relocate(it, "me.brandonli.mcav.libs.$it") }
    }
}

publishing {
    repositories {
        maven {
            name = "brandonli"
            url = uri("https://repo.brandonli.me/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = "me.brandonli"
            artifactId = project.name
            version = "${rootProject.version}"
            artifact(tasks["shadowJar"])
            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])
        }
    }
}