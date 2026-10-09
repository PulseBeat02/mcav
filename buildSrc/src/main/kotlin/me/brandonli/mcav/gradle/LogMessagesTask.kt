package me.brandonli.mcav.gradle

import java.io.File

abstract class LogMessagesTask : JavaLintTask() {

    private companion object {
        const val INLINE_MESSAGE =
            "{}:{}: error: the message of {} is written inline; name it in a private static final String constant, " +
                "with SLF4J placeholders for the arguments and no string concatenation"
    }

    override fun findViolations(files: List<File>, reportViolation: (File, Number, List<String>) -> Unit) {
        files.forEach { file ->
            LogMessages.find(file.readText()).forEach { found ->
                reportViolation(file, found.line, listOf(found.call))
            }
        }
    }

    override fun diagnosticMessage(): String = INLINE_MESSAGE

    override fun failureMessage(count: Int): String = "$count log calls with an inline message"

    override fun successMessage(fileCount: Int): String = "no log call with an inline message in $fileCount files\n"
}
