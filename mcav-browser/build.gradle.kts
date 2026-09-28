plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    // JCEF through jcefmaven. jcef-api depends on JOGL and GlueGen for its own off-screen browser, which draws into an
    // OpenGL canvas; mcav's off-screen browser draws nothing, so both are left out and a server never downloads them
    implementation(libs.jcefmaven) {
        exclude(group = "me.friwi", module = "jogl-all")
        exclude(group = "me.friwi", module = "gluegen-rt")
    }
    // commons-compress (from jcefmaven) reads the xz-compressed Debian packages of the libraries a Linux server may lack
    implementation(libs.xz)
    compileOnlyApi(project(":mcav-common"))
    // the annotations of JavaCPP's package declarations, so reading them while compiling against OpenCV warns about
    // nothing, as in mcav-common
    compileOnly(libs.osgi.annotation)
    testImplementation(project(":mcav-common"))
    testRuntimeOnly(libs.slf4j.simple)
}

// The tests tagged "cef" start real browser helpers with Chromium; a mutant cannot reach code that runs inside a helper,
// which loads the unmutated classes, so they only cost mutation time.
pitest {
    excludedGroups = setOf("cef")
}

// the tests start browser helpers with the coverage agent of the test JVM, which write their coverage here; it is part
// of what the tests produce, so it is removed before they run and cached with their results
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
