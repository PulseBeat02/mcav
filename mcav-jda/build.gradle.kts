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
    api(platform(libs.jackson2.bom))
    implementation(libs.jackson2.core)
    implementation(libs.jackson2.databind)
}
