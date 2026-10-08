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
    testsFiltered = testTask.map {
        val filter = it.filter as DefaultTestFilter
        filter.commandLineIncludePatterns.isNotEmpty() || filter.includePatterns.isNotEmpty() ||
            filter.excludePatterns.isNotEmpty() || it.includes.isNotEmpty() || it.excludes.isNotEmpty()
    }
    projectPath = project.path
}

tasks.withType<Test>().named { it == "propertyTest" || it == "fuzzTest" }.configureEach {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
}

tasks.withType<Test>().named { it == "propertyTest" }.configureEach {
    mustRunAfter(coverageLint)
}

val coverageGate = providers.gradleProperty("mcav.coverage").map { !it.equals("false", ignoreCase = true) }.getOrElse(true)
if (coverageGate) {
    tasks.check {
        dependsOn(coverageLint)
    }
}
