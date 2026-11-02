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

import me.brandonli.mcav.gradle.CatalogUtils
import me.brandonli.mcav.gradle.ExclusiveResource
import me.brandonli.mcav.gradle.FuzzTarget
import me.brandonli.mcav.gradle.FuzzTargets
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.libs

plugins {
    java
}

val junitBomAlias: String = "junit-bom"
val testingBundleAlias: String = "testing"
val junitLauncherAlias: String = "junit-platform-launcher"
val byteBuddyAgentAlias: String = "byte-buddy-agent"

val javaAgentConfiguration: String = "javaAgent"
val javaAgentOption: String = "-javaagent:"

val networkTestsProperty: String = "mcav.networkTests"
val syncMeasurementProperty: String = "mcav.syncMeasurement"
val testJavaHomeProperty: String = "mcav.testJavaHome"
val propertyTriesProperty: String = "property.tries"
val propertySmokeTriesProperty: String = "property.smokeTries"
val fuzzSecondsProperty: String = "fuzz.seconds"
val disabledValue: String = "false"
val defaultPropertyTries: String = "200"
val defaultPropertySmokeTries: String = "10"

val quietMachineService: String = "quietMachine"

val testHeapSize: String = "2g"
val fuzzHeapSize: String = "1g"
val processorsPerFork: Int = 2

val jqwikEngine: String = "jqwik"
val jqwikDatabaseProperty: String = "jqwik.database"
val jqwikDatabasePath: String = "build/jqwik-database"
val jqwikFailuresProperty: String = "jqwik.failures.after.default"
val previousSeed: String = "PREVIOUS_SEED"
val jqwikTriesProperty: String = "jqwik.tries.default"
val noDatabase: String = ""

val fuzzTag: String = "fuzz"
val fuzzTestName: String = "fuzzTest"
val fuzzInputsPath: String = "src/test/resources"
val fuzzTestClasses: String = "**/*FuzzTest.class"
val jazzerPath: String = "jazzer"
val jazzerBaseDirectoryProperty: String = "jazzer.internal.basedir"
val jazzerBaseDirectoryPath: String = "build/jazzer"
val testTemplateTimeoutProperty: String = "junit.jupiter.execution.timeout.testtemplate.method.default"
val replayTimeout: String = "30s"
val jazzerFuzzVariable: String = "JAZZER_FUZZ"
val enabledValue: String = "1"
val jazzerDurationProperty: String = "jazzer.max_duration"
val timeoutModeProperty: String = "junit.jupiter.execution.timeout.mode"
val disabledTimeoutMode: String = "disabled"

// Mockito and Jazzer take their instrumentation from this agent instead of attaching one at run time, which JEP 451 restricts
val javaAgent: NamedDomainObjectProvider<Configuration> = configurations.register(javaAgentConfiguration) {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    testImplementation(platform(CatalogUtils.libraryOf(libs, junitBomAlias)))
    testImplementation(CatalogUtils.bundleOf(libs, testingBundleAlias))

    testRuntimeOnly(CatalogUtils.libraryOf(libs, junitLauncherAlias))

    javaAgent(CatalogUtils.libraryOf(libs, byteBuddyAgentAlias))
}

val networkTests: String = providers.gradleProperty(networkTestsProperty).getOrElse(disabledValue)
val syncMeasurement: String = providers.gradleProperty(syncMeasurementProperty).getOrElse(disabledValue)
val measuresSync: Boolean = syncMeasurement.toBoolean()
val testJavaHome: Provider<String> = providers.gradleProperty(testJavaHomeProperty)
val propertyTries: String = providers.gradleProperty(propertyTriesProperty).getOrElse(defaultPropertyTries)
val propertySmokeTries: String = providers.gradleProperty(propertySmokeTriesProperty).getOrElse(defaultPropertySmokeTries)
val fuzzSeconds: Int = providers.gradleProperty(fuzzSecondsProperty).map { it.toInt() }.getOrElse(0)
val testSourceSet: SourceSet = sourceSets.test.get()
val quietMachine: Provider<ExclusiveResource> = gradle.sharedServices.registerIfAbsent(quietMachineService, ExclusiveResource::class) {
    maxParallelUsages = 1
}

tasks.withType<Test>().configureEach {
    this.useJUnitPlatform()
    this.failOnNoDiscoveredTests = false
    this.maxHeapSize = testHeapSize
    this.jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
        "--sun-misc-unsafe-memory-access=allow"
    )
    this.jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("$javaAgentOption${javaAgent.get().singleFile}")
    })
    // as an input the agent is resolved under the project lock, before the provider reads it
    this.inputs.files(javaAgent)
        .withPropertyName(javaAgentConfiguration)
        .withPathSensitivity(PathSensitivity.NONE)
    this.systemProperty(networkTestsProperty, networkTests)
    this.systemProperty(syncMeasurementProperty, syncMeasurement)

    if (testJavaHome.isPresent) {
        this.executable = HostPlatform.current().javaExecutable(testJavaHome.get())
    }

    this.systemProperty(jqwikDatabaseProperty, "$jqwikDatabasePath-${this.name}")
    this.systemProperty(jqwikFailuresProperty, previousSeed)

    if (measuresSync) {
        this.usesService(quietMachine)
    }
}

tasks.test {
    this.useJUnitPlatform {
        this.excludeTags(fuzzTag)
    }
    this.systemProperty(jqwikTriesProperty, propertySmokeTries)
    this.maxParallelForks = if (measuresSync) 1 else (Runtime.getRuntime().availableProcessors() / processorsPerFork).coerceAtLeast(1)
    this.systemProperty(jqwikDatabaseProperty, noDatabase)
}

val propertyTest: TaskProvider<Test> = tasks.register<Test>("propertyTest") {
    this.group = "verification"
    this.description = "Runs the jqwik property tests of this module"
    this.testClassesDirs = testSourceSet.output.classesDirs
    this.classpath = testSourceSet.runtimeClasspath
    this.useJUnitPlatform {
        this.includeEngines(jqwikEngine)
    }
    this.systemProperty(jqwikTriesProperty, propertyTries)
}

val fuzzInputs: Directory = layout.projectDirectory.dir(fuzzInputsPath)
val fuzzing: Boolean = fuzzSeconds > 0
val fuzzTargets: List<FuzzTarget> = if (fuzzing) FuzzTargets.find(testSourceSet.java.srcDirs) else emptyList()
val fuzzTaskNames: Set<String> = fuzzTargets.map { it.taskName() }.toSet() + fuzzTestName

tasks.withType<Test>().matching { it.name in fuzzTaskNames }.configureEach {
    this.testClassesDirs = testSourceSet.output.classesDirs
    this.classpath = testSourceSet.runtimeClasspath
    this.useJUnitPlatform {
        this.includeTags(fuzzTag)
    }
    this.include(fuzzTestClasses)
    this.inputs.files(fuzzInputs)
        .withPropertyName("fuzzInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    this.outputs.dir(layout.buildDirectory.dir("$jazzerPath/${this.name}")).withPropertyName("jazzerDirectory")
    this.systemProperty(jazzerBaseDirectoryProperty, "$jazzerBaseDirectoryPath/${this.name}")
    this.systemProperty(testTemplateTimeoutProperty, replayTimeout)
    this.maxHeapSize = fuzzHeapSize
}

val fuzzTasks: List<TaskProvider<Test>> = fuzzTargets.map { target ->
    tasks.register<Test>(target.taskName()) {
        this.group = "verification"
        this.description = "Fuzzes ${target.filterPattern()} for $fuzzSeconds seconds in a JVM of its own"
        this.filter.includeTestsMatching(target.filterPattern())
        this.environment(jazzerFuzzVariable, enabledValue)
        this.systemProperty(jazzerDurationProperty, "${fuzzSeconds}s")
        this.systemProperty(timeoutModeProperty, disabledTimeoutMode)
        this.outputs.upToDateWhen { false }
        this.testLogging {
            this.events("started", "failed", "skipped")
        }
    }
}

val fuzzTest: TaskProvider<Test> = tasks.register<Test>(fuzzTestName) {
    this.group = "verification"
    this.description = "Replays the committed fuzz inputs of this module, and fuzzes every fuzz test method with -P$fuzzSecondsProperty=<n>"
    this.dependsOn(fuzzTasks)
}

tasks.check {
    this.dependsOn(propertyTest, fuzzTest)
}
