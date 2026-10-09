import me.brandonli.mcav.gradle.lwjglNatives

plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    api(libs.bundles.lwjgl.opengl)
    compileOnlyApi(project(":mcav-common"))
    testImplementation(project(":mcav-common"))
    testImplementation(libs.lwjgl.glfw)
    lwjglNatives()?.let { natives ->
        listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengl, libs.lwjgl.glfw).forEach { library ->
            testRuntimeOnly(variantOf(library) { classifier(natives) })
        }
    }
}
