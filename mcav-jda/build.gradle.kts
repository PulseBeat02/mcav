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
    // the release that fixes the known vulnerabilities of the Jackson JDA brings (see the catalog)
    constraints {
        api(libs.jackson2.bom)
    }
}
