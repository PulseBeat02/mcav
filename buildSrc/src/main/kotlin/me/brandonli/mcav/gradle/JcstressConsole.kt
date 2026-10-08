package me.brandonli.mcav.gradle

import java.io.File
import java.io.OutputStream
import org.gradle.api.GradleException

/**
 * Reads the console of a jcstress run for the tests it skipped. jcstress skips a test whose state or actors cannot even
 * be created, its sanity check, and still exits with success, so a test that could never run passed every run.
 */
object JcstressConsole {

    private const val SKIPPED_MESSAGE = "jcstress skipped %d test(s), which failed their sanity check and never ran: %s"

    private val skippedLine = Regex("""\[SKIPPED]\s+(\S+)""")

    /**
     * An output that writes everything both to the console and to a file. Closing it closes the file only.
     *
     * @param console the console of the build
     * @param file the file to keep the output in
     * @return the output
     */
    fun tee(console: OutputStream, file: File): OutputStream = Tee(console, file.outputStream().buffered())

    /**
     * Finds the tests a run skipped.
     *
     * @param lines the lines of the run's console
     * @return the class names of the skipped tests, each once
     */
    fun skippedTests(lines: List<String>): List<String> =
        lines.mapNotNull { line -> skippedLine.find(line)?.groupValues?.get(1) }.distinct()

    /**
     * Fails if a run skipped a test.
     *
     * @param console the file that kept the run's console
     * @throws GradleException if a test was skipped
     */
    fun checkNoneSkipped(console: File) {
        val skipped = skippedTests(console.readLines())
        if (skipped.isNotEmpty()) {
            throw GradleException(SKIPPED_MESSAGE.format(skipped.size, skipped.joinToString(", ")))
        }
    }

    private class Tee(private val console: OutputStream, private val file: OutputStream) : OutputStream() {

        override fun write(byte: Int) {
            this.console.write(byte)
            this.file.write(byte)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            this.console.write(bytes, offset, length)
            this.file.write(bytes, offset, length)
        }

        override fun flush() {
            this.console.flush()
            this.file.flush()
        }

        override fun close() {
            this.console.flush()
            this.file.close()
        }
    }
}
