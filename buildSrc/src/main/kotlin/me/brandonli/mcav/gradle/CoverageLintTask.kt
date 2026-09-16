package me.brandonli.mcav.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Reports every line of production code that the tests leave uncovered, as {@code file:line: warning: reason}, and
 * fails when there is any. Lines listed in {@code coverage-exceptions.txt} are allowed to stay uncovered.
 */
abstract class CoverageLintTask : DefaultTask() {

    /** The JaCoCo XML report of the test run. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: ConfigurableFileCollection

    /** The production source directory the report refers to. */
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    /** The exceptions file of the project, which may not exist. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val exceptionsFile: ConfigurableFileCollection

    /** Whether the tests ran with a filter, in which case the report covers only part of the code. */
    @get:Input
    abstract val testsFiltered: Property<Boolean>

    /** The path of the project, for messages; read at configuration time so the task never touches the project. */
    @get:Internal
    abstract val projectPath: Property<String>

    @TaskAction
    fun lint() {
        if (testsFiltered.get()) {
            logger.warn("coverageLint of {} skipped: the tests ran with a filter, so the report covers only part of the code", projectPath.get())
            return
        }
        val exceptionFile = exceptionsFile.singleFile
        val exceptions = CoverageExceptions.read(exceptionFile)
        val sources = sourceDirectory.get().asFile
        val gaps = CoverageReport.findGaps(report.singleFile, sources, exceptions)
        gaps.forEach { gap -> logger.error(gap) }
        // an entry that matches no uncovered line must be removed, so the list never hides new gaps
        val staleExceptions = exceptions.filterNot { it.used }
        staleExceptions.forEach { exception ->
            logger.error("{}: warning: '{} | {}' matches no uncovered line, remove it", exceptionFile, exception.path, exception.sourceLine)
        }
        if (gaps.isNotEmpty() || staleExceptions.isNotEmpty()) {
            throw GradleException("${gaps.size} lines are not fully covered by tests and ${staleExceptions.size} coverage exceptions are stale")
        }
    }
}
