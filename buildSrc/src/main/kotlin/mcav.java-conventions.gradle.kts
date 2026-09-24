// Conventions shared by every module of mcav: Java 25, the Checker Framework, Error Prone, Spotless, JUnit, JaCoCo,
// the coverage lint and PIT mutation testing. The root build applies this plugin to every subproject.

import info.solidsoft.gradle.pitest.PitestPluginExtension
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("org.checkerframework")
    id("net.ltgt.errorprone")
    id("info.solidsoft.pitest")
    id("com.github.node-gradle.node")
    id("com.diffplug.spotless")
    id("mcav.coverage-lint")
}

val javaVersion = 25
val windows = System.getProperty("os.name").lowercase().contains("windows")

repositories {
    mavenCentral()
    google()
    maven("https://repo.brandonli.me/snapshots")
    maven("https://maven.maxhenkel.de/repository/public")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.encoding = "UTF-8"
    // every javac warning is reported; annotation processing notes are left out because the Checker Framework does
    // not claim the annotations it reads
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Werror"))
    options.isFork = true
    options.forkOptions.memoryMaximumSize = "4g"
    // the Checker Framework Gradle plugin does not add this export for recent Checker Framework versions, see
    // https://github.com/typetools/checker-framework/issues/7241; it is added next to the exports and opens the Error
    // Prone plugin passes to the forked compiler, rather than replacing them
    options.forkOptions.jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
    })
    // Error Prone runs its default checks on production and test code; generated sources are not ours to fix
    options.errorprone {
        disableWarningsInGeneratedCode = true
    }
}

checkerFramework {
    version = "3.53.1"
    checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
    // tests pass nulls on purpose to check the preconditions, so only production code is checked
    excludeTests = true
    // javac keeps only the last value of a repeated -A option, so every stub folder goes into one option
    val stubFolders = listOf(project.file("checker-framework"), rootProject.file("checker-framework")).filter { it.isDirectory }
    extraJavacArgs = if (stubFolders.isEmpty()) emptyList() else listOf("-Astubs=" + stubFolders.joinToString(File.pathSeparator))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
    maxHeapSize = "2g"
    // the tests load native libraries and attach Mockito's agent, which Java 24 and newer report unless allowed;
    // the agent appends to the boot class path, which turns class data sharing off with a warning, so it starts off;
    // libraries of the Paper API such as JOML still read memory through sun.misc.Unsafe, which is allowed quietly
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
        "--sun-misc-unsafe-memory-access=allow"
    )
    // tests that download from the internet, such as the check of the bundled yt-dlp release, only run with
    // -Pmcav.networkTests=true
    val networkTests = providers.gradleProperty("mcav.networkTests")
    systemProperty("mcav.networkTests", networkTests.getOrElse("false"))
    // some JDK builds cannot load the bundled OpenCV natives on every machine, so the JDK that runs the tests can be
    // chosen with -Pmcav.testJavaHome=<path to a Java 25 JDK>
    val testJavaHome = providers.gradleProperty("mcav.testJavaHome")
    if (testJavaHome.isPresent) {
        val executableSuffix = if (windows) ".exe" else ""
        executable = testJavaHome.get() + "/bin/java" + executableSuffix
    }
}

// PIT mutation testing runs on demand with `./gradlew :<module>:pitest` (it is not part of check, because mutating
// every class and rerunning the tests takes far longer than the build); the report is written to build/reports/pitest
extensions.configure<PitestPluginExtension> {
    pitestVersion = "1.30.0"
    // the JUnit Platform launcher PIT needs is added by the plugin, matching the JUnit version of the tests
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("me.brandonli.mcav.*")
    threads = 4
    // a mutant that breaks a player, a browser or a server thread makes its test wait instead of fail, so PIT stops
    // it after this constant plus timeoutFactor times the time the test needed unmutated. The scaled part already
    // protects a slow but correct test, so the constant is only the grace a hung mutant burns before it is killed.
    // This is PIT's own default, stated explicitly: a shorter grace was measured and could not be shown to be safe,
    // because a test whose runtime varies by more than the grace would be reported as a kill it did not earn.
    timeoutConstInMillis = 4000
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    // the mutated code runs under the JVM options of the module's tests, including those a module adds itself, such
    // as the opened java.net package of mcav-installer
    val testTask = tasks.named<Test>("test")
    jvmArgs = testTask.map { test -> test.jvmArgs.orEmpty() }
    val testJavaHome = providers.gradleProperty("mcav.testJavaHome")
    if (testJavaHome.isPresent) {
        val executableSuffix = if (windows) ".exe" else ""
        val testJava = file(testJavaHome.get() + "/bin/java" + executableSuffix)
        jvmPath = testJava
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filteringCharset = "UTF-8"
}

// the sandbox plugin downloads the snapshot modules at runtime and records their hashes, which must be current
configurations.matching { it.name == "runtimeDownload" }.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

// Java sources are formatted with prettier-java, which runs on a Node.js the build downloads once per module
node {
    download = true
    version = "24.21.0"
    workDir = layout.buildDirectory.dir("nodejs")
}

val nodeExecutable = node.resolvedNodeDir.map { directory ->
    val relativePath = if (windows) "node.exe" else "bin/node"
    directory.file(relativePath).asFile
}

spotless {
    java {
        prettier(mapOf("prettier" to "3.3.3", "prettier-plugin-java" to "2.6.4"))
            .config(mapOf("parser" to "java", "tabWidth" to 2, "plugins" to listOf("prettier-plugin-java"), "printWidth" to 140))
            .nodeExecutable(nodeExecutable)
        licenseHeaderFile(rootProject.file("HEADER"))
        importOrder()
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("resources") {
        target(
            "src/**/*.json",
            "src/**/*.yml",
            "src/**/*.yaml",
            "src/**/*.properties",
            "src/**/*.astub",
            "checker-framework/**/*.astub",
            "coverage-exceptions.txt",
            "*.md"
        )
        targetExclude("**/build/**", "**/node_modules/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// prettier needs the downloaded Node.js
tasks.matching { it.name.startsWith("spotlessJava") }.configureEach {
    dependsOn("nodeSetup")
}

// the end-to-end test of the sandbox plugin runs a server with the modules of this build, published into a folder
plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "endToEnd"
                url = rootProject.layout.buildDirectory.dir("e2e-repository").get().asFile.toURI()
            }
        }
    }
}
