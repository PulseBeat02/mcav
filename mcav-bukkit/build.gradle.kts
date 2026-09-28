plugins {
    id("mcav.module")
    id("mcav.publishing")
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.get())
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi(libs.netty.all)
    compileOnlyApi(libs.guava)
    compileOnlyApi(libs.gson)
    testImplementation(project(":mcav-common"))
}

mcavPublishing {
    gradleModuleMetadata = false
}

// published API: the leaf's accessors are x(), y() and q(), so their names stay until a release may change them
tasks.variableNames {
    publishedNames.addAll(listOf("x", "y", "q").map { "me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frame.Leaf#$it" })
}

// The MCV2 live encoder's native kernels (src/main/native/mcv2) are built once and committed as resources, with the
// SHA-256 of each library compiled into Mcv2Natives, so no default task needs a C/C++ toolchain. With
// -Pmcav.natives=build, buildMcv2Natives rebuilds them with the Zig that build.sh pins (ZIG=/path/to/zig) and
// formatMcv2Natives formats their sources (CLANG_FORMAT=/path/to/clang-format); after a rebuild, the digests the build
// writes into SHA256SUMS go into Mcv2Natives.DIGESTS, which Mcv2NativesTest checks.
val nativeSources = layout.projectDirectory.dir("src/main/native/mcv2")
val buildsNatives = providers.gradleProperty("mcav.natives").map { it == "build" }.getOrElse(false)
val clangFormat = "\"\${CLANG_FORMAT:-clang-format}\""

tasks.register<Exec>("buildMcv2Natives") {
    description = "Rebuilds the MCV2 native kernels for every platform with -Pmcav.natives=build"
    group = "build"
    onlyIf("-Pmcav.natives=build asks for the native kernels to be rebuilt") { buildsNatives }
    workingDir = nativeSources.asFile
    commandLine("bash", "build.sh")
}

tasks.register<Exec>("formatMcv2Natives") {
    description = "Formats the MCV2 native kernels' sources with clang-format ${libs.versions.clang.format.get()} with -Pmcav.natives=build"
    group = "formatting"
    onlyIf("-Pmcav.natives=build asks for the native kernels to be formatted") { buildsNatives }
    workingDir = nativeSources.asFile
    // the .inc files are C++, which clang-format does not know from their extension
    commandLine(
        "bash",
        "-c",
        "$clangFormat -i --style=file *.h *.hpp *.cpp && for f in *.inc; do " +
            "$clangFormat --style=file --assume-filename=x.cpp < \"\$f\" > \"\$f.tmp\" && mv \"\$f.tmp\" \"\$f\"; done"
    )
}
