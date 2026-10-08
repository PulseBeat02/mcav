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
    // Pin Jackson for Maven consumers too: CVE-2026-89425 / CVE-2026-89407.
    api(platform(libs.jackson2.bom))
    implementation(libs.jackson2.core)
    implementation(libs.jackson2.databind)
}
