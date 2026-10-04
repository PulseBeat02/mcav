package me.brandonli.mcav.gradle

import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExplicitLocalePluginTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun productionNumberFormattingMustChooseALocale() {
        val project = directory.toFile()
        project.resolve("settings.gradle").writeText("rootProject.name = 'locale-fixture'\n")
        project.resolve("build.gradle").writeText("plugins { id 'mcav.java-library' }\n")
        val catalog = project.resolve("gradle/libs.versions.toml")
        catalog.parentFile.mkdirs()
        Path.of("..", "gradle", "libs.versions.toml").toFile().copyTo(catalog)
        val source = project.resolve("src/main/java/fixture/NumericOutput.java")
        source.parentFile.mkdirs()
        source.writeText(
            """
            package fixture;
            public final class NumericOutput {
                private NumericOutput() { throw new UnsupportedOperationException(); }
                public static String render(final int value) {
                    return "%d".formatted(value);
                }
            }
            """.trimIndent()
        )
        val runner = GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withArguments("compileJava", "--no-parallel", "--max-workers=1")
        val rejected = runner.buildAndFail()
        assertTrue(rejected.output.contains("error: [DefaultLocale]"), rejected.output)

        source.writeText(
            """
            package fixture;
            import java.util.Locale;
            public final class NumericOutput {
                private NumericOutput() { throw new UnsupportedOperationException(); }
                public static String render(final int value) {
                    return String.format(Locale.ROOT, "%d", value);
                }
            }
            """.trimIndent()
        )
        val accepted = runner.build()
        assertEquals(TaskOutcome.SUCCESS, accepted.task(":compileJava")?.outcome, accepted.output)
    }
}
