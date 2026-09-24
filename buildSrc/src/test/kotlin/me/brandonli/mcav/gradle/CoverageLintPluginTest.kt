package me.brandonli.mcav.gradle

import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoverageLintPluginTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun filteringOneModuleDoesNotDisableAnotherModulesCoverage() {
        val project = directory.toFile()
        project.resolve("settings.gradle").writeText("rootProject.name = 'coverage-fixture'\ninclude 'first', 'second'\n")
        for (name in listOf("first", "second")) {
            val module = project.resolve(name)
            module.mkdirs()
            module.resolve("build.gradle").writeText("plugins { id 'mcav.coverage-lint' }\n")
            val source = module.resolve("src/main/java/Example.java")
            source.parentFile.mkdirs()
            source.writeText("public class Example { public int value() { return 42; } }\n")
        }
        val result = GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withArguments(":first:test", "--tests", "ExampleTest", ":first:coverageLint", ":second:coverageLint", "--stacktrace")
            .buildAndFail()

        assertTrue(result.output.contains("coverageLint of :first skipped"), result.output)
        assertFalse(result.output.contains("coverageLint of :second skipped"), result.output)
        assertTrue(result.output.contains("no test ran, so none of this code is covered"), result.output)
        assertTrue(result.output.contains(":second:coverageLint FAILED"), result.output)
    }
}
