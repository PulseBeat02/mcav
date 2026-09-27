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

// The MCV2 live encoder's native kernels (src/main/native/mcv2) are built once and committed as resources, with the
// SHA-256 of each library compiled into Mcv2Natives, so no default task needs a C/C++ toolchain. With
// -Pmcav.natives=build these tasks rebuild them with the pinned Zig 0.16.0 (ZIG=/path/to/zig) and format the sources
// with clang-format 18.1.8 (CLANG_FORMAT=/path/to/clang-format); after a rebuild, the new digests printed in SHA256SUMS
// go into Mcv2Natives.DIGESTS, which Mcv2NativesTest checks.
val nativeSources = layout.projectDirectory.dir("src/main/native/mcv2")
val buildsNatives = providers.gradleProperty("mcav.natives").map { it == "build" }.getOrElse(false)

tasks.register<Exec>("buildMcv2Natives") {
    description = "Rebuilds the MCV2 native kernels for every platform with -Pmcav.natives=build"
    group = "build"
    onlyIf("-Pmcav.natives=build asks for the native kernels to be rebuilt") { buildsNatives }
    workingDir = nativeSources.asFile
    commandLine("bash", "build.sh")
}

tasks.register<Exec>("formatMcv2Natives") {
    description = "Formats the MCV2 native kernels' sources with clang-format with -Pmcav.natives=build"
    group = "formatting"
    onlyIf("-Pmcav.natives=build asks for the native kernels to be formatted") { buildsNatives }
    workingDir = nativeSources.asFile
    commandLine(
        "bash",
        "-c",
        "\"${'$'}{CLANG_FORMAT:-clang-format}\" -i --style=file *.h *.hpp *.cpp && for f in *.inc; do " +
            "\"${'$'}{CLANG_FORMAT:-clang-format}\" --style=file --assume-filename=x.cpp < \"${'$'}f\" > \"${'$'}f.tmp\" && mv \"${'$'}f.tmp\" \"${'$'}f\"; done"
    )
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
