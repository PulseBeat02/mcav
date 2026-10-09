plugins {
    id("mcav.paper-library")
}

dependencies {
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi(libs.netty.all)
    compileOnlyApi(libs.guava)
    compileOnlyApi(libs.gson)
    testImplementation(project(":mcav-common"))
}
