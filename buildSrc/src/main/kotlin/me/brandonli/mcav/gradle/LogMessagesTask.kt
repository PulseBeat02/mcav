package me.brandonli.mcav.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a log call writes its message inline instead of naming a constant, and reports each one as
 * `file:line: error: ...`, which IDEs turn into links.
 */
abstract class LogMessagesTask : DefaultTask() {

    private companion object {
        const val INLINE_MESSAGE =
            "{}:{}: error: the message of {} is written inline; name it in a private static final String constant, " +
                "with SLF4J placeholders for the arguments and no string concatenation"
    }

    /** The Java sources of every source set of the module. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Written when the sources pass, so the task is up to date until one of them changes. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        var count = 0
        sources.files.sorted().forEach { file ->
            LogMessages.find(file.readText()).forEach { found ->
                count++
                logger.error(INLINE_MESSAGE, file, found.line, found.call)
            }
        }
        if (count > 0) {
            throw GradleException("$count log calls with an inline message")
        }
        report.get().asFile.writeText("no log call with an inline message in ${sources.files.size} files\n")
    }
}
