plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    api(libs.jda)
    compileOnlyApi(project(":mcav-common"))
    testImplementation(libs.jda)
    testImplementation(project(":mcav-common"))
    testImplementation(libs.jna)
}
