import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    id("maven-publish")
}

dependencies {

    // project dependencies
    api("uk.co.caprica:vlcj:4.12.1")
    api("org.bytedeco:javacv-platform:1.5.14") {
        exclude(group = "org.bytedeco", module = "flycapture")
        exclude(group = "org.bytedeco", module = "flycapture-platform")
        exclude(group = "org.bytedeco", module = "libdc1394")
        exclude(group = "org.bytedeco", module = "libdc1394-platform")
        exclude(group = "org.bytedeco", module = "libfreenect")
        exclude(group = "org.bytedeco", module = "libfreenect-platform")
        exclude(group = "org.bytedeco", module = "libfreenect2")
        exclude(group = "org.bytedeco", module = "libfreenect2-platform")
        exclude(group = "org.bytedeco", module = "librealsense")
        exclude(group = "org.bytedeco", module = "librealsense-platform")
        exclude(group = "org.bytedeco", module = "videoinput")
        exclude(group = "org.bytedeco", module = "videoinput-platform")
        exclude(group = "org.bytedeco", module = "artoolkitplus")
        exclude(group = "org.bytedeco", module = "artoolkitplus-platform")
        exclude(group = "org.bytedeco", module = "flandmark")
        exclude(group = "org.bytedeco", module = "flandmark-platform")
        exclude(group = "org.bytedeco", module = "leptonica")
        exclude(group = "org.bytedeco", module = "leptonica-platform")
        exclude(group = "org.bytedeco", module = "tesseract")
        exclude(group = "org.bytedeco", module = "tesseract-platform")
    }
    api("com.google.guava:guava:33.4.8-jre")
    api("com.google.code.gson:gson:2.14.0")
    api("net.java.dev.jna:jna:5.19.1")
    api("net.java.dev.jna:jna-platform:5.19.1")

    // logging: the library logs through the SLF4J API and leaves the binding to the application
    api("org.slf4j:slf4j-api:2.0.17")

    // JavaCPP declares these annotations as provided; without them javac cannot read its package-info
    compileOnly("org.osgi:osgi.annotation:8.1.0")

    // test dependencies
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("com.google.jimfs:jimfs:1.3.0")
}

tasks {
    java {
        withSourcesJar()
        withJavadocJar()
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
}

// PIT mutates the deterministic part of the library. The players are left out: their tests drive a real VLC, FFmpeg
// and OpenCV, so a mutant that breaks playback makes every test of its batch hang until PIT's timeout, which takes
// hours without finding anything the fast tests do not already cover.
extensions.configure<PitestPluginExtension> {
    excludedClasses = setOf("me.brandonli.mcav.media.player.multimedia.*")
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
