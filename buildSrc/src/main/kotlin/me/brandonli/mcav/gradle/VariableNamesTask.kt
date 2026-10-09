package me.brandonli.mcav.gradle

import java.io.File
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input

abstract class VariableNamesTask : JavaLintTask() {

    private companion object {
        const val UNDESCRIPTIVE = "{}:{}: error: the {} {} says nothing; name what it holds"
    }

    @get:Input
    abstract val publishedNames: SetProperty<String>

    override fun findViolations(files: List<File>, reportViolation: (File, Number, List<String>) -> Unit) {
        val published = publishedNames.get()
        VariableNames.findInFiles(files).toSortedMap().forEach { (file, names) ->
            names.filter { it.member !in published }.forEach { found ->
                reportViolation(file, found.line, listOf(found.kind, found.name))
            }
        }
    }

    override fun diagnosticMessage(): String = UNDESCRIPTIVE

    override fun failureMessage(count: Int): String = "$count undescriptive variable names"

    override fun successMessage(fileCount: Int): String = "no undescriptive variable name in $fileCount files\n"
}
