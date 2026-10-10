package me.brandonli.mcav.gradle

import java.io.File

abstract class QualifiedNamesTask : JavaLintTask() {

    private companion object {
        const val WRITTEN_OUT = "{}:{}: error: {} is written out; import it, or mark the line // fqn: <why> if its simple name clashes"
    }

    override fun findViolations(files: List<File>, reportViolation: (File, Number, List<String>) -> Unit) {
        files.forEach { file ->
            QualifiedNames.find(file.readText()).forEach { found ->
                reportViolation(file, found.line, listOf(found.name))
            }
        }
    }

    override fun diagnosticMessage(): String = WRITTEN_OUT

    override fun failureMessage(count: Int): String = "$count fully qualified type names in Java code"

    override fun successMessage(fileCount: Int): String = "no fully qualified type names in $fileCount files\n"
}
