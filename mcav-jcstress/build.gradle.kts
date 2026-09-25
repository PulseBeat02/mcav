import java.util.zip.ZipFile
import net.ltgt.gradle.errorprone.errorprone

// Concurrency tests of mcav on jcstress, OpenJDK's harness for racing a few actors over shared state millions of times.
// This module is a test harness, not product code: it is not published, and it is kept out of the coverage lint and
// of PIT, because its code only ever runs inside jcstress. `./gradlew jcstressTest` runs every test in quick mode;
// CI step 3 passes -Pjcstress.mode=quick|default|stress and -Pjcstress.timeBudgetMinutes=<n>. A result a test marks
// FORBIDDEN makes jcstress exit with an error, which fails the task.

val jcstressVersion = "0.16"

dependencies {
    implementation("org.openjdk.jcstress:jcstress-core:$jcstressVersion")
    annotationProcessor("org.openjdk.jcstress:jcstress-core:$jcstressVersion")

    // the code under test. The tests only run Java code, so the native libraries of every platform that the media
    // modules pull in through the JavaCV platform artifacts stay out: they would put more than a gigabyte into the jar
    implementation(project(":mcav-common")) {
        exclude(group = "org.bytedeco")
    }
    implementation("org.bytedeco:javacv:1.5.14") {
        isTransitive = false
    }
    implementation("org.bytedeco:javacpp:1.5.14") {
        isTransitive = false
    }
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14") {
        isTransitive = false
    }
    implementation("org.bytedeco:opencv:4.14.0-1.5.14") {
        isTransitive = false
    }
    implementation(project(":mcav-http")) {
        exclude(group = "org.bytedeco")
    }
    implementation(project(":mcav-vm")) {
        exclude(group = "org.bytedeco")
    }
    // the map encoder and the screens of the sandbox need none of the Minecraft server the two modules compile against
    implementation(project(":mcav-bukkit")) {
        isTransitive = false
    }
    implementation(project(":sandbox:plugin")) {
        isTransitive = false
    }
}

// the Checker Framework would check the harness jcstress generates from the annotations, which is not ours: it assigns
// null to fields the checker considers non-null
checkerFramework {
    skipCheckerFramework = true
}

tasks.named<JavaCompile>("compileJava") {
    // the harness jcstress generates is compiled with our code and does not keep every lint of -Xlint:all
    options.compilerArgs.remove("-Werror")
    options.errorprone {
        excludedPaths = ".*/build/generated/.*"
    }
}

// a harness, not product code: nothing of it is measured
tasks.named("coverageLint") {
    enabled = false
}
tasks.named("pitest") {
    enabled = false
}

// jcstress runs from one jar that it also puts on the class path of the JVMs it forks. The annotation processor writes
// the list of tests into META-INF/TestList of the main output, so that output comes first and duplicates are dropped
val jcstressJar = tasks.register<Jar>("jcstressJar") {
    description = "Packs the jcstress tests with everything they run"
    group = "build"
    archiveClassifier = "jcstress"
    manifest {
        attributes("Main-Class" to "org.openjdk.jcstress.Main")
    }
    from(sourceSets.main.get().output)
    // resolved when the jar is packed, after the jars of the modules under test were built
    val runtimeClasspath = configurations.runtimeClasspath
    dependsOn(runtimeClasspath)
    from(runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// quick is about a minute and a half per test on this machine and surfaced every race the tests of this module look
// for in every fork; sanity is too short to surface a race at all. The CPU count bounds how many actors run at once,
// so the machine stays usable
val jcstressMode = providers.gradleProperty("jcstress.mode").getOrElse("quick")
val jcstressBudgetMinutes = providers.gradleProperty("jcstress.timeBudgetMinutes").map { it.toInt() }
val jcstressTests = providers.gradleProperty("jcstress.tests")
val availableCpus = Runtime.getRuntime().availableProcessors()
val jcstressCpus = providers.gradleProperty("jcstress.cpus").getOrElse(minOf(4, availableCpus).toString())

tasks.register<JavaExec>("jcstressTest") {
    description = "Runs the jcstress concurrency tests; -Pjcstress.mode, -Pjcstress.timeBudgetMinutes, -Pjcstress.tests"
    group = "verification"
    val jar = jcstressJar.flatMap { it.archiveFile }
    inputs.file(jar)
    classpath = files(jar)
    mainClass = "org.openjdk.jcstress.Main"
    val workingDirectory = layout.buildDirectory.dir("jcstress")
    workingDir = workingDirectory.get().asFile
    val arguments = mutableListOf("-m", jcstressMode, "-c", jcstressCpus, "-r", "results", "-v")
    if (jcstressTests.isPresent) {
        arguments += listOf("-t", jcstressTests.get())
    }
    args(arguments)
    val budgetMinutes = jcstressBudgetMinutes.orNull
    val selection = jcstressTests.orNull
    val cpus = jcstressCpus.toInt()
    doFirst {
        workingDirectory.get().asFile.mkdirs()
        // jcstress 0.16 has no time budget of its own, so the budget becomes the time of one iteration
        if (budgetMinutes != null) {
            val testCount = JcstressBudget.countTests(jar.get().asFile, selection)
            val iterationMillis = JcstressBudget.iterationMillis(jcstressMode, budgetMinutes, testCount, cpus)
            logger.lifecycle("jcstress: {} tests in {} mode within {} minutes: {} ms per iteration", testCount, jcstressMode, budgetMinutes, iterationMillis)
            args("-time", iterationMillis.toString())
        }
    }
    // a stress run depends on the scheduling of the machine, so its result is never reused
    outputs.upToDateWhen { false }
}

/**
 * Turns a time budget into the time of one jcstress iteration. A jcstress run is made of runs of every test: one per
 * JVM configuration (14 for the two-actor tests here, measured) and fork, where a mode has a number of normal forks and
 * a multiple of that of stress forks, each run starting a JVM (about 2 s here) and then iterating a number of times;
 * two-actor runs take two CPUs, so the CPUs run several of them at once. Half of the budget goes to the iterations and
 * the rest is left for the JVM starts and for the machine being slower than this one.
 */
object JcstressBudget {

    private const val CONFIGURATIONS = 14
    private const val JVM_START_MILLIS = 2_000L
    private const val MIN_ITERATION_MILLIS = 50L
    private const val MAX_ITERATION_MILLIS = 10_000L

    // forks, stress fork multiplier and iterations of the presets of jcstress 0.16
    private val presets = mapOf(
        "sanity" to Triple(1, 1, 1),
        "quick" to Triple(1, 1, 5),
        "default" to Triple(1, 5, 5),
        "tough" to Triple(10, 5, 10),
        "stress" to Triple(10, 10, 50),
    )

    fun countTests(jar: File, selection: String?): Int {
        val pattern = selection?.let { Regex(it) }
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("META-INF/TestList") ?: return 0
            val lines = zip.getInputStream(entry).bufferedReader().readLines()
            return lines.count { line -> line.startsWith("JCTEST") && (pattern == null || pattern.containsMatchIn(testName(line))) }
        }
    }

    // a line starts with JCTEST, then the length of the class name, an S and the class name itself
    private fun testName(line: String): String {
        val lengthEnd = line.indexOf('S', 6)
        val length = line.substring(6, lengthEnd).toInt()
        return line.substring(lengthEnd + 1, lengthEnd + 1 + length)
    }

    fun iterationMillis(mode: String, budgetMinutes: Int, testCount: Int, cpus: Int): Long {
        val (forks, stressMultiplier, iterations) = presets[mode] ?: throw GradleException("Unknown jcstress mode $mode")
        val runs = testCount.toLong() * CONFIGURATIONS * (forks + forks * stressMultiplier)
        val parallelRuns = maxOf(1, cpus / 2)
        val budgetMillis = budgetMinutes * 60_000L
        val startMillis = runs * JVM_START_MILLIS / parallelRuns
        val iterationBudget = budgetMillis / 2 - startMillis
        val perIteration = iterationBudget * parallelRuns / maxOf(1L, runs * iterations)
        return perIteration.coerceIn(MIN_ITERATION_MILLIS, MAX_ITERATION_MILLIS)
    }
}
