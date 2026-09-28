// PIT mutation testing, on demand with `./gradlew :<module>:pitest`: mutating every class and rerunning its tests takes
// far longer than the build, so it is not part of `check`. The report is written to build/reports/pitest.

import me.brandonli.mcav.gradle.javaExecutable
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.versionOf

plugins {
    java
    id("info.solidsoft.pitest")
}

pitest {
    pitestVersion = libs.versionOf("pitest")
    // the plugin adds the JUnit Platform launcher PIT needs, matching the JUnit version of the tests
    junit5PluginVersion = libs.versionOf("pitest-junit5-plugin")
    targetClasses = setOf("me.brandonli.mcav.*")
    threads = 4
    // A mutant that breaks a player, a browser or a server thread makes its test wait instead of fail, so PIT stops it
    // after this grace plus timeoutFactor times the time the test took unmutated. It is PIT's own default: a shorter
    // grace could not be shown to be safe, as a test whose runtime varies by more than the grace would be reported as a
    // kill it did not earn.
    timeoutConstInMillis = 4000
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    // the mutated code runs with the JVM options of the module's tests, a module's own included, such as the opened
    // java.net package of mcav-installer
    jvmArgs = tasks.named<Test>("test").map { it.jvmArgs.orEmpty() }
    val testJavaHome = providers.gradleProperty("mcav.testJavaHome")
    if (testJavaHome.isPresent) {
        jvmPath = file(javaExecutable(testJavaHome.get()))
    }
}
