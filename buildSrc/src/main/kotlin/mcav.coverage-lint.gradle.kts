// The coverage lint: `coverageLint` runs the tests and prints every line or branch of production code that no test
// covers as file:line, which IDEs turn into links, and fails when there is any. `check` enforces it only with
// -Pmcav.coverage, because some tests skip themselves on machines without VLC, Chrome, QEMU or a display, or whose
// OpenCV build cannot read video files (the bundled Linux build cannot), and the code they test would show up as gaps.

import me.brandonli.mcav.gradle.CoverageLintTask
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter

plugins {
    java
    jacoco
}

jacoco {
    toolVersion = "0.8.15"
}

val testTask = tasks.named<Test>("test")
val jacocoReport = tasks.named<JacocoReport>("jacocoTestReport")

jacocoReport {
    reports {
        xml.required = true
        html.required = true
    }
}

testTask {
    finalizedBy(jacocoReport)
}

val coverageLint = tasks.register<CoverageLintTask>("coverageLint") {
    group = "verification"
    description = "Runs the tests and reports every line and branch of production code that no test covers."
    dependsOn(testTask, jacocoReport)
    report.from(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
    sourceDirectory = layout.projectDirectory.dir("src/main/java")
    exceptionsFile.from(layout.projectDirectory.file("coverage-exceptions.txt"))
    // Gradle stores --tests separately from public includePatterns. Consult this task's filter, not the global
    // command line: filtering one module must not disable coverage verification for every other module.
    // DefaultTestFilter is internal API; keep the functional test when updating the Gradle wrapper.
    testsFiltered = testTask.map {
        val filter = it.filter as DefaultTestFilter
        filter.commandLineIncludePatterns.isNotEmpty() || filter.includePatterns.isNotEmpty() ||
            filter.excludePatterns.isNotEmpty() || it.includes.isNotEmpty() || it.excludes.isNotEmpty()
    }
    projectPath = project.path
}

if (providers.gradleProperty("mcav.coverage").isPresent) {
    tasks.check {
        dependsOn(coverageLint)
    }
}
