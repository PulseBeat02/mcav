// The coverage lint: `coverageLint` runs the tests and prints every line or branch of production code that no test
// covers as file:line, which IDEs turn into links, and fails when there is any. A line no test can run is listed with
// its reason in the module's coverage-exceptions.txt. `check`, and so `build`, enforces the lint unless
// -Pmcav.coverage=false is passed: some tests skip themselves on machines without VLC, Chrome, QEMU, a display or a sound
// device, and the code they test would show up as gaps there.

import me.brandonli.mcav.gradle.CatalogVersions
import me.brandonli.mcav.gradle.CoverageLintTask
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter

plugins {
    java
    jacoco
}

jacoco {
    toolVersion = CatalogVersions.JACOCO
}

val jacocoReport = tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}

val testTask = tasks.named<Test>("test") {
    finalizedBy(jacocoReport)
}

val coverageLint = tasks.register<CoverageLintTask>("coverageLint") {
    group = "verification"
    description = "Runs the tests and reports every line and branch of production code that no test covers."
    dependsOn(testTask, jacocoReport)
    report.from(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
    sourceDirectory = layout.projectDirectory.dir("src/main/java")
    exceptionsFile.from(layout.projectDirectory.file("coverage-exceptions.txt"))
    // --tests is kept apart from the public include patterns, so the filter of this task's own test run is read: a
    // filter on one module must not turn the lint of another off. DefaultTestFilter is internal API, which the
    // functional test of this plugin checks on every Gradle update.
    testsFiltered = testTask.map {
        val filter = it.filter as DefaultTestFilter
        filter.commandLineIncludePatterns.isNotEmpty() || filter.includePatterns.isNotEmpty() ||
            filter.excludePatterns.isNotEmpty() || it.includes.isNotEmpty() || it.excludes.isNotEmpty()
    }
    projectPath = project.path
}

// the lint reads the coverage of `test` alone, and the agent instruments every class a test loads, the Minecraft
// server's included, so the other test tiers run without it
tasks.withType<Test>().named { it == "propertyTest" || it == "fuzzTest" }.configureEach {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
}

tasks.withType<Test>().named { it == "propertyTest" }.configureEach {
    mustRunAfter(coverageLint)
}

// any value but false keeps the gate on, a bare -Pmcav.coverage included
val coverageGate = providers.gradleProperty("mcav.coverage").map { !it.equals("false", ignoreCase = true) }.getOrElse(true)
if (coverageGate) {
    tasks.check {
        dependsOn(coverageLint)
    }
}
