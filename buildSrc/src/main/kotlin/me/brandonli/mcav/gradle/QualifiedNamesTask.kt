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
 * Fails when Java code names a type by its fully qualified name instead of importing it, and reports each one as
 * `file:line: error: ...`, which IDEs turn into links. A line whose qualified name is needed because two
 * types of the same simple name meet in one file is marked `// fqn: <why>`.
 */
abstract class QualifiedNamesTask : DefaultTask() {

    private companion object {
        const val WRITTEN_OUT = "{}:{}: error: {} is written out; import it, or mark the line // fqn: <why> if its simple name clashes"
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
            QualifiedNames.find(file.readText()).forEach { found ->
                count++
                logger.error(WRITTEN_OUT, file, found.line, found.name)
            }
        }
        if (count > 0) {
            throw GradleException("$count fully qualified type names in Java code")
        }
        report.get().asFile.writeText("no fully qualified type names in ${sources.files.size} files\n")
    }
}
