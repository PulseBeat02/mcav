import me.brandonli.mcav.gradle.javaExecutable
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.versionOf

plugins {
    java
    id("info.solidsoft.pitest")
}

pitest {
    pitestVersion = libs.versionOf("pitest")
    junit5PluginVersion = libs.versionOf("pitest-junit5-plugin")
    targetClasses = setOf("me.brandonli.mcav.*")
    threads = 4
    // A shorter PIT grace can count a slow test as a killed mutant; retain its default.
    timeoutConstInMillis = 4000
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    jvmArgs = provider { tasks.getByName<Test>("test").jvmArgs.orEmpty() }
    val testJavaHome = providers.gradleProperty("mcav.testJavaHome")
    if (testJavaHome.isPresent) {
        jvmPath = file(javaExecutable(testJavaHome.get()))
    }
}
