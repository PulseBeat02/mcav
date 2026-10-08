plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi(project(":mcav-vnc"))
    testImplementation(project(":mcav-common"))
    testImplementation(project(":mcav-vnc"))
    testRuntimeOnly(libs.slf4j.simple)
}
