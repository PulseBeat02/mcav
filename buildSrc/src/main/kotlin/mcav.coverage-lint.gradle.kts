// The coverage lint: `coverageLint` runs the tests and prints every line or branch of production code that no test
// covers as file:line, which IDEs turn into links, and fails when there is any. `check` enforces it only with
// -Pmcav.coverage, because some tests skip themselves on machines without VLC, Chrome, QEMU or a display, or whose
// OpenCV build cannot read video files (the bundled Linux build cannot), and the code they test would show up as gaps.

import me.brandonli.mcav.gradle.CoverageLintTask

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

// a report of a filtered test run covers only part of the code, so the lint would print false gaps for it
val testsFilteredOnCommandLine = gradle.startParameter.taskRequests.any { request -> request.args.any { it.startsWith("--tests") } }

val coverageLint = tasks.register<CoverageLintTask>("coverageLint") {
    group = "verification"
    description = "Runs the tests and reports every line and branch of production code that no test covers."
    dependsOn(testTask, jacocoReport)
    report.from(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
    sourceDirectory = layout.projectDirectory.dir("src/main/java")
    exceptionsFile.from(layout.projectDirectory.file("coverage-exceptions.txt"))
    testsFiltered = testTask.map { testsFilteredOnCommandLine || it.filter.includePatterns.isNotEmpty() }
    projectPath = project.path
}

if (providers.gradleProperty("mcav.coverage").isPresent) {
    tasks.check {
        dependsOn(coverageLint)
    }
}
