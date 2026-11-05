/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import me.brandonli.mcav.gradle.CatalogVersions
import me.brandonli.mcav.gradle.CoverageLintTask
import me.brandonli.mcav.gradle.TestFilters

plugins {
    java
    jacoco
}

val coverageReportPath: String = "reports/jacoco/test/jacocoTestReport.xml"
val mainSourcePath: String = "src/main/java"
val coverageExceptionsPath: String = "coverage-exceptions.txt"

val coverageProperty: String = "mcav.coverage"
val disabledValue: String = "false"

val propertyTestName: String = "propertyTest"
val fuzzTaskPrefix: String = "fuzz"

jacoco {
    toolVersion = CatalogVersions.JACOCO
}

val jacocoReport: TaskProvider<JacocoReport> = tasks.named<JacocoReport>("jacocoTestReport") {
    this.reports {
        this.xml.required = true
        this.html.required = true
    }
}

val instrumentedClasses: List<String> = listOf("me.brandonli.*", "org.cef.browser.MCAVOffscreenBrowser*")

tasks.withType<Test>().configureEach {
    this.extensions.configure<JacocoTaskExtension> {
        this.includes = instrumentedClasses
    }
}

val testTask: TaskProvider<Test> = tasks.named<Test>("test") {
    this.finalizedBy(jacocoReport)
}

val coverageLint: TaskProvider<CoverageLintTask> = tasks.register<CoverageLintTask>("coverageLint") {
    this.group = "verification"
    this.description = "Runs the tests and reports every line and branch of production code that no test covers"
    this.dependsOn(testTask, jacocoReport)
    this.report.from(layout.buildDirectory.file(coverageReportPath))
    this.sourceDirectory = layout.projectDirectory.dir(mainSourcePath)
    this.exceptionsFile.from(layout.projectDirectory.file(coverageExceptionsPath))
    this.testsFiltered = testTask.map(TestFilters::isFiltered)
    this.projectPath = project.path
}

tasks.withType<Test>().named { it == propertyTestName || it.startsWith(fuzzTaskPrefix) }.configureEach {
    this.extensions.configure<JacocoTaskExtension> {
        this.isEnabled = false
    }
}

val coverageGate: Boolean = providers.gradleProperty(coverageProperty).map { !it.equals(disabledValue, ignoreCase = true) }.getOrElse(true)
if (coverageGate) {
    tasks.check {
        this.dependsOn(coverageLint)
    }
}
