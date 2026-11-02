plugins {
    `kotlin-dsl`
    jacoco
    alias(libs.plugins.pitest)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

val buildPlugins: List<Provider<PluginDependency>> = listOf(
    libs.plugins.spotless,
    libs.plugins.paperweight.userdev,
    libs.plugins.checker.framework,
    libs.plugins.node,
    libs.plugins.errorprone,
    libs.plugins.pitest,
    libs.plugins.shadow,
    libs.plugins.run.paper,
    libs.plugins.resource.factory.paper,
    libs.plugins.gremlin,
    libs.plugins.javacpp.platform
)
val buildPluginMarkers: List<Provider<String>> =
    buildPlugins.map { plugin -> plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" } }

val javaAgentConfiguration: String = "javaAgent"
val javaAgentOption: String = "-javaagent:"

// Mockito and Jazzer take their instrumentation from this agent instead of attaching one at run time, which JEP 451 restricts
val javaAgent: NamedDomainObjectProvider<Configuration> = configurations.register(javaAgentConfiguration) {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(libs.xz)
    buildPluginMarkers.forEach { implementation(it) }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.jazzer.junit)
    testImplementation(gradleTestKit())

    testRuntimeOnly(libs.junit.platform.launcher)

    javaAgent(libs.byte.buddy.agent)
}

val catalogVersionsPath: String = "generated/sources/catalog/kotlin"
val catalogVersionsFile: String = "me/brandonli/mcav/gradle/CatalogVersions.kt"
val repositoryHeaderPath: String = "../HEADER"
val lineSeparator: String = "\n"

val buildPackageClasses: String = "me/brandonli/mcav/gradle/**"
val generatedClasses: String = "me/brandonli/mcav/gradle/CatalogVersions*"

val mutatedClasses: String = "me.brandonli.mcav.gradle.*"
val generatedClass: String = "me.brandonli.mcav.gradle.CatalogVersions"
val mutationThreads: Int = 4

val fullCoverage: String = "1.0"

val fuzzTag: String = "fuzz"
val fuzzSecondsProperty: String = "fuzz.seconds"
val fuzzInputsPath: String = "src/test/resources"
val jazzerBaseDirectoryProperty: String = "jazzer.internal.basedir"
val jazzerBaseDirectoryPath: String = "build/jazzer"
val jazzerFuzzVariable: String = "JAZZER_FUZZ"
val enabledValue: String = "1"
val jazzerDurationProperty: String = "jazzer.max_duration"

val repositoryPath: String = ".."
val repositoryBuildFiles: List<String> =
    listOf("HEADER", ".readthedocs.yaml", "*.gradle.kts", "*/*.gradle.kts", "gradle/libs.versions.toml")
val buildSourcesPath: String = "src"

val catalogVersions: Provider<Directory> = layout.buildDirectory.dir(catalogVersionsPath)
val jacocoVersion: Provider<String> = libs.versions.jacoco
val repositoryHeader: RegularFile = layout.projectDirectory.file(repositoryHeaderPath)
val generateCatalogVersions: TaskProvider<Task> = tasks.register("generateCatalogVersions") {
    this.group = "build"
    this.description = "Writes the catalog versions the plugins of this build need outside the main build"
    this.inputs.property("jacoco", jacocoVersion)
    this.inputs.file(repositoryHeader)
    this.outputs.dir(catalogVersions)
    this.doLast {
        val file: File = catalogVersions.get().file(catalogVersionsFile).asFile
        file.parentFile.mkdirs()
        file.writeText(
            repositoryHeader.asFile.readText() + lineSeparator + """
            |package me.brandonli.mcav.gradle
            |
            |object CatalogVersions {
            |    const val JACOCO: String = "${jacocoVersion.get()}"
            |}
            |""".trimMargin()
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateCatalogVersions)
}

val testSourceSet: SourceSet = sourceSets.test.get()
val fuzzSeconds: Int = providers.gradleProperty(fuzzSecondsProperty).map { it.toInt() }.getOrElse(0)
val fuzzInputs: Directory = layout.projectDirectory.dir(fuzzInputsPath)
val buildClasses: FileTree = sourceSets.main.get().output.classesDirs.asFileTree.matching {
    include(buildPackageClasses)
    exclude(generatedClasses)
}
val repositoryFiles: ConfigurableFileTree = fileTree(repositoryPath) {
    include(repositoryBuildFiles)
}
val buildSources: Directory = layout.projectDirectory.dir(buildSourcesPath)

tasks.withType<Test>().configureEach {
    this.jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("$javaAgentOption${javaAgent.get().singleFile}")
    })
    // as an input the agent is resolved under the project lock, before the provider reads it
    this.inputs.files(javaAgent)
        .withPropertyName(javaAgentConfiguration)
        .withPathSensitivity(PathSensitivity.NONE)
}

// the tests read these files from disk, so a change to one of them must run the tests again
tasks.test {
    this.useJUnitPlatform {
        this.excludeTags(fuzzTag)
    }
    this.inputs.files(repositoryFiles)
        .withPropertyName("repositoryFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    this.inputs.dir(buildSources)
        .withPropertyName("buildSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// one fuzz test method per class: forkEvery gives each class, and so each method, the JVM of its own that Jazzer needs
val fuzzTest: TaskProvider<Test> = tasks.register<Test>("fuzzTest") {
    this.group = "verification"
    this.description = "Replays the committed fuzz inputs of buildSrc, and fuzzes every fuzz test class with -Pfuzz.seconds=<n>"
    this.testClassesDirs = testSourceSet.output.classesDirs
    this.classpath = testSourceSet.runtimeClasspath
    this.useJUnitPlatform {
        this.includeTags(fuzzTag)
    }
    this.forkEvery = 1
    this.failOnNoDiscoveredTests = false
    this.inputs.files(fuzzInputs)
        .withPropertyName("fuzzInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    this.systemProperty(jazzerBaseDirectoryProperty, jazzerBaseDirectoryPath)
    if (fuzzSeconds > 0) {
        this.environment(jazzerFuzzVariable, enabledValue)
        this.systemProperty(jazzerDurationProperty, "${fuzzSeconds}s")
        this.outputs.upToDateWhen { false }
    }
}

tasks.jacocoTestReport {
    this.classDirectories.setFrom(buildClasses)
    this.reports {
        this.xml.required = true
        this.html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    this.classDirectories.setFrom(buildClasses)
    this.violationRules {
        this.rule {
            this.limit {
                this.counter = "LINE"
                this.minimum = fullCoverage.toBigDecimal()
            }
            this.limit {
                this.counter = "BRANCH"
                this.minimum = fullCoverage.toBigDecimal()
            }
        }
    }
}

pitest {
    pitestVersion = libs.versions.pitest.asProvider()
    junit5PluginVersion = libs.versions.pitest.junit5.plugin
    targetClasses = setOf(mutatedClasses)
    excludedClasses = setOf(generatedClass)
    excludedGroups = setOf(fuzzTag)
    threads = mutationThreads
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    jvmArgs = provider { listOf("--add-opens=java.base/java.lang=ALL-UNNAMED", "$javaAgentOption${javaAgent.get().singleFile}") }
}

tasks.check {
    this.dependsOn(fuzzTest)
    this.dependsOn(tasks.jacocoTestCoverageVerification)
}
