import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    id("maven-publish")
}

// The tests tagged "cef" start real browser helpers with Chromium; a mutant cannot reach code that runs inside a helper
// process, which loads the unmutated classes, so they only cost mutation time. Every other test runs under PIT.
extensions.configure<PitestPluginExtension> {
    excludedGroups = setOf("cef")
}

dependencies {
    // JCEF through jcefmaven. jcef-api depends on JOGL and GlueGen for its own off-screen browser, which draws into an
    // OpenGL canvas; mcav's off-screen browser draws nothing, so both are left out and a server never downloads them
    implementation("me.friwi:jcefmaven:146.0.10") {
        exclude(group = "me.friwi", module = "jogl-all")
        exclude(group = "me.friwi", module = "gluegen-rt")
    }
    // the Debian packages of the libraries a Linux server may lack are xz-compressed tar archives, which
    // commons-compress (from jcefmaven) reads with this library
    implementation("org.tukaani:xz:1.10")

    // provided
    compileOnlyApi(project(":mcav-common"))
    // the annotations JavaCPP's package declarations carry, so reading them while compiling against OpenCV warns about
    // nothing, as in mcav-common
    compileOnly("org.osgi:osgi.annotation:8.1.0")

    // test dependencies
    testImplementation(project(":mcav-common"))
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks {
    // the tests start browser helper processes with the coverage agent of the test JVM, which write their coverage
    // here; it is part of what the tests produce, so it is removed before they run and cached with their results
    val helperCoverage = layout.buildDirectory.file("jacoco/helper.exec")
    test {
        outputs.file(helperCoverage).withPropertyName("helperCoverage")
        doFirst {
            delete(helperCoverage)
        }
        // the measurement of how far the sound of a page drifts from its picture times real events, which a busy
        // machine delays; it runs on request, on a quiet machine: -Pmcav.syncMeasurement=true
        systemProperty("mcav.syncMeasurement", providers.gradleProperty("mcav.syncMeasurement").getOrElse("false"))
    }
    jacocoTestReport {
        executionData(helperCoverage)
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
