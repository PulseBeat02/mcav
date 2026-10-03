package me.brandonli.mcav.gradle

import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublishingPluginTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun thePublishedPomNamesTheProjectItsLicenseAndItsSourceRepository() {
        prepareProject()
        run("generatePomFileForMavenPublication")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(directory.resolve("build/publications/maven/pom-default.xml").toFile())
        val project = document.documentElement
        fun text(tag: String): String = project.getElementsByTagName(tag).item(0)?.textContent ?: ""

        assertEquals("me.brandonli", text("groupId"))
        assertEquals("mcav-fixture", text("artifactId"))
        assertEquals("1.2.3", text("version"))
        val license = project.getElementsByTagName("license").item(0)
        assertNotNull(license, "the POM must identify MCAV's license")
        assertEquals("GNU General Public License, version 3 or later", license.childNodes.let { children ->
            (0 until children.length).map { children.item(it) }.single { it.nodeName == "name" }.textContent
        })
        assertEquals("scm:git:https://github.com/PulseBeat02/mcav.git", text("connection"))
        assertEquals("mcav-fixture", text("name"))
    }

    @Test
    fun everyPublishedJarCarriesTheProjectLicenseAndThirdPartyNotices() {
        prepareProject()
        run("jar", "sourcesJar", "javadocJar")
        for (classifier in listOf("", "-sources", "-javadoc")) {
            val path = directory.resolve("build/libs/mcav-fixture-1.2.3$classifier.jar")
            ZipFile(path.toFile()).use { jar ->
                for (name in listOf("LICENSE", "THIRD-PARTY-NOTICES.md")) {
                    val entry = jar.getEntry("META-INF/" + if (name == "LICENSE") "LICENSE-MCAV" else name)
                    assertNotNull(entry, "$path must carry $name")
                    assertEquals(directory.resolve(name).toFile().readText(), jar.getInputStream(entry).reader().readText())
                }
            }
        }
    }

    private fun prepareProject() {
        val project = directory.toFile()
        project.resolve("settings.gradle").writeText("rootProject.name = 'mcav-fixture'\n")
        project.resolve("build.gradle").writeText("plugins { id 'mcav.publishing' }\nversion = '1.2.3'\n")
        project.resolve("LICENSE").writeText("Fixture license: preserve the complete root file.\n")
        project.resolve("THIRD-PARTY-NOTICES.md").writeText("Fixture notices: preserve the complete root file.\n")
        val source = project.resolve("src/main/java/Example.java")
        source.parentFile.mkdirs()
        source.writeText("/** A public example for the documentation jar. */ public class Example {}\n")
    }

    private fun run(vararg tasks: String) {
        GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withPluginClasspath()
            .withArguments(*tasks, "--max-workers=2", "--stacktrace")
            .build()
    }
}
