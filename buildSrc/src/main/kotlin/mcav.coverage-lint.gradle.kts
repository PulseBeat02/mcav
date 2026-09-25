// The coverage lint: `coverageLint` runs the tests and prints every line or branch of production code that no test
// covers as file:line, which IDEs turn into links, and fails when there is any. `check`, and so `build`, enforces it
// unless -Pmcav.coverage=false is passed: some tests skip themselves on machines without VLC, Chrome, QEMU, a display
// or a sound device, and the code they test would show up as gaps there.

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

// the gate is on unless -Pmcav.coverage=false turns it off; a bare -Pmcav.coverage, which used to be the switch that
// turned it on, still means on. The value is read, not only its presence, so that false really means false.
val coverageGate = providers.gradleProperty("mcav.coverage").map { !it.equals("false", ignoreCase = true) }.getOrElse(true)
if (coverageGate) {
    tasks.check {
        dependsOn(coverageLint)
    }
}
