package me.brandonli.mcav.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JcstressConsoleTest {

    @TempDir
    lateinit var directory: Path

    private val summary = listOf(
        "RUN RESULTS:",
        "  Interesting tests: No matches.",
        "  Failed tests: No matches.",
        "  Error tests: No matches.",
        "  All remaining tests: 2 matching test results.",
        "..... [OK] me.brandonli.mcav.http.ListenerStopRace",
        "..... [SKIPPED] me.brandonli.mcav.bukkit.media.mcv2.KeyframeRequestRace",
    )

    @Test
    fun findsTheTestsARunSkipped() {
        assertEquals(listOf("me.brandonli.mcav.bukkit.media.mcv2.KeyframeRequestRace"), JcstressConsole.skippedTests(this.summary))
    }

    @Test
    fun namesEverySkippedTestOnce() {
        val twice = this.summary + "..... [SKIPPED] me.brandonli.mcav.bukkit.media.mcv2.KeyframeRequestRace"
        assertEquals(1, JcstressConsole.skippedTests(twice).size)
    }

    @Test
    fun failsARunThatSkippedATest() {
        val console = this.console(this.summary)
        val failure = assertThrows(GradleException::class.java) { JcstressConsole.checkNoneSkipped(console) }
        assertEquals(
            "jcstress skipped 1 test(s), which failed their sanity check and never ran: " +
                "me.brandonli.mcav.bukkit.media.mcv2.KeyframeRequestRace",
            failure.message,
        )
    }

    @Test
    fun passesARunThatRanEveryTest() {
        JcstressConsole.checkNoneSkipped(this.console(this.summary.dropLast(1)))
    }

    @Test
    fun keepsWhatItShowsAndLeavesTheConsoleOpen() {
        var closed = false
        val shown = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }
        val file = this.directory.resolve("console.txt").toFile()
        val tee = JcstressConsole.tee(shown, file)
        tee.write("[OK] one\n".toByteArray())
        tee.write('x'.code)
        tee.close()
        assertFalse(closed, "the build's console stays open for everything after the run")
        shown.write('!'.code)
        assertEquals("[OK] one\nx!", shown.toString())
        assertEquals("[OK] one\nx", file.readText())
    }

    private fun console(lines: List<String>): File {
        val file = this.directory.resolve("console.txt").toFile()
        file.writeText(lines.joinToString("\n"))
        return file
    }
}
