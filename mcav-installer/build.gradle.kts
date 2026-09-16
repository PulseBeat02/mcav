plugins {
    id("maven-publish")
    id("com.gradleup.shadow") version "8.3.8"
}

dependencies {
    implementation("org.apache.maven.resolver:maven-resolver-supplier-mvn3:2.0.22")
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

    // the reflective injector needs java.net opened, as the error message of the injector tells users to do
    test {
        jvmArgs("--add-opens", "java.base/java.net=ALL-UNNAMED")
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
