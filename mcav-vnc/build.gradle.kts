plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    api(libs.vernacular)
    compileOnlyApi(project(":mcav-common"))
    testImplementation(project(":mcav-common"))
}
