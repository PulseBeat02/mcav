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

abstract class CoverageLintTask : DefaultTask() {

    private companion object {
        const val FILTERED = "coverageLint of {} skipped: the tests ran with a filter, so the report covers only part of the code"
        const val GAP = "{}"
        const val STALE_EXCEPTION = "{}: warning: '{} | {}' matches no uncovered line, remove it"
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: ConfigurableFileCollection

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val exceptionsFile: ConfigurableFileCollection

    @get:Input
    abstract val testsFiltered: Property<Boolean>

    @get:Internal
    abstract val projectPath: Property<String>

    @TaskAction
    fun lint() {
        if (testsFiltered.get()) {
            logger.warn(FILTERED, projectPath.get())
            return
        }
        val exceptionFile = exceptionsFile.singleFile
        val exceptions = CoverageExceptions.read(exceptionFile)
        val sources = sourceDirectory.get().asFile
        val gaps = CoverageReport.findGaps(report.singleFile, sources, exceptions)
        gaps.forEach { gap -> logger.error(GAP, gap) }
        val staleExceptions = exceptions.filterNot { it.used }
        staleExceptions.forEach { exception ->
            logger.error(STALE_EXCEPTION, exceptionFile, exception.path, exception.sourceLine)
        }
        if (gaps.isNotEmpty() || staleExceptions.isNotEmpty()) {
            throw GradleException("${gaps.size} lines are not fully covered by tests and ${staleExceptions.size} coverage exceptions are stale")
        }
    }
}
