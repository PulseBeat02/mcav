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
    commandLine(
        "bash",
        "-c",
        "$clangFormat -i --style=file *.h *.hpp *.cpp && for f in *.inc; do " +
            "$clangFormat --style=file --assume-filename=x.cpp < \"\$f\" > \"\$f.tmp\" && mv \"\$f.tmp\" \"\$f\"; done"
    )
}
