plugins {
    id("maven-publish")
}

dependencies {
    // provided
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi(project(":mcav-vnc"))

    // test dependencies
    testImplementation(project(":mcav-common"))
    testImplementation(project(":mcav-vnc"))
    // the tests with a real QEMU log how it was started and why it fell back to software emulation
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks {
    // the measurement of how far the sound of a guest drifts from its picture times real events, which a busy machine
    // delays; it runs on request, on a quiet machine: -Pmcav.syncMeasurement=true
    test {
        systemProperty("mcav.syncMeasurement", providers.gradleProperty("mcav.syncMeasurement").getOrElse("false"))
    }
    java {
        withSourcesJar()
        withJavadocJar()
    }
    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
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
            from(components["java"])
        }
    }
}
