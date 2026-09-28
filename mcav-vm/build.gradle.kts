plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi(project(":mcav-vnc"))
    testImplementation(project(":mcav-common"))
    testImplementation(project(":mcav-vnc"))
    // the tests with a real QEMU log how it was started and why it fell back to software emulation
    testRuntimeOnly(libs.slf4j.simple)
}
