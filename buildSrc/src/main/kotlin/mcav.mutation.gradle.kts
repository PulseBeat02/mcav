import me.brandonli.mcav.gradle.TestRuntimeProfile
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
    timeoutConstInMillis = 4000
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    jvmArgs = provider { TestRuntimeProfile.forMutation(tasks.getByName<Test>("test")) }
    val testJavaHome = providers.gradleProperty("mcav.testJavaHome")
    if (testJavaHome.isPresent) {
        jvmPath = file(javaExecutable(testJavaHome.get()))
    }
}
