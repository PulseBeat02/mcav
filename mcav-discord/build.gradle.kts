plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    api(platform(libs.jackson2.bom))
    api(libs.jda)

    implementation(libs.jackson2.core)
    implementation(libs.jackson2.databind)

    compileOnlyApi(project(":mcav-common"))

    testImplementation(project(":mcav-common"))
    testImplementation(libs.jna)
}
