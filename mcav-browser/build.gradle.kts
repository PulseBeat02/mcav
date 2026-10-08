plugins {
    id("mcav.module")
    id("mcav.publishing")
    id("mcav.script-testing")
}

dependencies {
    // Exclude JOGL/GlueGen: JCEF's OpenGL canvas needs them, but mcav's off-screen renderer does not.
    implementation(libs.jcefmaven) {
        exclude(group = "me.friwi", module = "jogl-all")
        exclude(group = "me.friwi", module = "gluegen-rt")
    }
    implementation(libs.xz)
    compileOnlyApi(project(":mcav-common"))
    compileOnly(libs.osgi.annotation)
    testImplementation(project(":mcav-common"))
    testRuntimeOnly(libs.slf4j.simple)
}

pitest {
    excludedGroups = setOf("cef")
}

val helperCoverage = layout.buildDirectory.file("jacoco/helper.exec")

tasks.test {
    outputs.file(helperCoverage).withPropertyName("helperCoverage")
    doFirst {
        delete(helperCoverage)
    }
}

tasks.jacocoTestReport {
    executionData(helperCoverage)
}
