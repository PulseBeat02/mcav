plugins {
    id("maven-publish")
    alias(libs.plugins.paperweight.userdev)
}

dependencies {

    // project dependencies
    paperweight.paperDevBundle(libs.versions.paper.get())

    // provided
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi(libs.netty.all)
    compileOnlyApi(libs.guava)
    compileOnlyApi(libs.gson)

    // testing
    testImplementation(project(":mcav-common"))
}

tasks {

    java {
        withSourcesJar()
        withJavadocJar()
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    withType<GenerateModuleMetadata>().configureEach {
        enabled = false
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
            from(components["java"])
            groupId = "me.brandonli"
            artifactId = project.name
            version = rootProject.version.toString()
            suppressAllPomMetadataWarnings()
        }
    }
}
