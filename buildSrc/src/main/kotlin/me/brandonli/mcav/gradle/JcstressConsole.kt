package me.brandonli.mcav.gradle

import java.io.File
import java.io.OutputStream
import org.gradle.api.GradleException

object JcstressConsole {

    private const val SKIPPED_MESSAGE = "jcstress skipped %d test(s), which failed their sanity check and never ran: %s"

    private val skippedLine = Regex("""\[SKIPPED]\s+(\S+)""")

    fun tee(console: OutputStream, file: File): OutputStream = Tee(console, file.outputStream().buffered())

    fun skippedTests(lines: List<String>): List<String> =
        lines.mapNotNull { line -> skippedLine.find(line)?.groupValues?.get(1) }.distinct()

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
