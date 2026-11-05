import me.brandonli.mcav.gradle.ModuleCoordinates

plugins {
    id("mcav.browser")
}

dependencies {
    // Exclude JOGL/GlueGen: JCEF's OpenGL canvas needs them, but mcav's off-screen renderer does not.
    implementation(libs.jcefmaven) {
        exclude(group = ModuleCoordinates.JCEF_MAVEN_GROUP, module = ModuleCoordinates.JOGL_MODULE)
        exclude(group = ModuleCoordinates.JCEF_MAVEN_GROUP, module = ModuleCoordinates.GLUEGEN_RUNTIME_MODULE)
    }

    compileOnlyApi(project(":mcav-common"))

    compileOnly(libs.osgi.annotation)

    testImplementation(project(":mcav-common"))
    testImplementation(libs.jimfs)
    testImplementation(libs.xz)

    testRuntimeOnly(libs.slf4j.simple)
}
