plugins {
    id("mcav.lwjgl")
}

dependencies {
    api(libs.bundles.lwjgl.opengl)
    compileOnlyApi(project(":mcav-common"))
    testImplementation(project(":mcav-common"))
    testImplementation(libs.lwjgl.glfw)
}
