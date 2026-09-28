package me.brandonli.mcav.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a variable, parameter or field of the Java code is named by one letter or by a short form a word says
 * better ([VariableNames]), and reports each one as `file:line: error: ...`, which IDEs turn into links.
 */
abstract class VariableNamesTask : DefaultTask() {

    private companion object {
        const val UNDESCRIPTIVE = "{}:{}: error: the {} {} says nothing; name what it holds"
    }

    /** The Java sources of every source set of the module. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Names that stay because they are published API, as `package.Type#name`. */
    @get:Input
    abstract val publishedNames: SetProperty<String>

    /** Written when the sources pass, so the task is up to date until one of them changes. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val published = publishedNames.get()
        var count = 0
        VariableNames.findInFiles(sources.files.sorted()).toSortedMap().forEach { (file, names) ->
            names.filter { it.member !in published }.forEach { found ->
                count++
                logger.error(UNDESCRIPTIVE, file, found.line, found.kind, found.name)
            }
        }
        if (count > 0) {
            throw GradleException("$count undescriptive variable names")
        }
        report.get().asFile.writeText("no undescriptive variable name in ${sources.files.size} files\n")
    }
}
