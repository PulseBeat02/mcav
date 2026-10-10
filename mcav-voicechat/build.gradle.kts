plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    compileOnlyApi(libs.voicechat.api)
    compileOnlyApi(project(":mcav-common"))
    testImplementation(project(":mcav-common"))
    testImplementation(libs.voicechat.api)
}
