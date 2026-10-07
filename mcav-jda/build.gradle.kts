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
    // Maven consumers need the same Jackson versions as this library's resolved runtime.
    api(platform(libs.jackson2.bom))
    implementation(libs.jackson2.core)
    implementation(libs.jackson2.databind)
}
