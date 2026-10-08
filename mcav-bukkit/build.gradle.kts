plugins {
    id("mcav.module")
    id("mcav.mcv2-tools")
    id("mcav.natives")
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
