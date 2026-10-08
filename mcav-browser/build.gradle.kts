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

// CEF helpers load unmutated classes, so their integration tests cannot exercise PIT mutants.
pitest {
    excludedGroups = setOf("cef")
}

// Helper JVMs write coverage here; cache it with test results and remove stale files before reruns.
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
