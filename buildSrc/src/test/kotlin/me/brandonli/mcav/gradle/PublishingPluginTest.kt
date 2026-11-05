/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.brandonli.mcav.gradle

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList

class PublishingPluginTest {

    private companion object {

        const val POM_TASK: String = "generatePomFileForMavenPublication"

        const val GROUP_ID_ELEMENT: String = "groupId"

        const val SCM_CONNECTION: String = "scm:git:https://github.com/PulseBeat02/mcav.git"

        const val REPOSITORY_DIRECTORY: String = ".."
        const val CATALOG_PATH: String = "gradle/libs.versions.toml"
        const val BUILD_SCRIPT: String = "build.gradle"
        const val SOURCE_PATH: String = "src/main/java/Example.java"
        const val POM_PATH: String = "build/publications/maven/pom-default.xml"
        const val MUTATION_POM_PATH: String = "build/mutation-pom.txt"

        const val LICENSE_FILE: String = "LICENSE"
        const val NOTICES_FILE: String = "THIRD-PARTY-NOTICES.md"
        const val LICENSE_ENTRY: String = "META-INF/LICENSE-MCAV"
        const val NOTICES_ENTRY: String = "META-INF/THIRD-PARTY-NOTICES.md"

        const val SETTINGS_SCRIPT: String = "settings.gradle"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldNameTheProjectItsLicenseAndItsSourceRepositoryInThePublishedPom(): Unit {
        this.prepareProject()
        this.run(POM_TASK)

        val project: Element = this.readPom()
        val names: Map<String, String?> = mapOf(
            GROUP_ID_ELEMENT to this.textOf(project, GROUP_ID_ELEMENT),
            "artifactId" to this.textOf(project, "artifactId"),
            "version" to this.textOf(project, "version"),
            "license" to this.licenseNameOf(project),
            "connection" to this.textOf(project, "connection"),
            "name" to this.textOf(project, "name")
        )
        val expected: Map<String, String?> = mapOf(
            GROUP_ID_ELEMENT to "me.brandonli",
            "artifactId" to "mcav-fixture",
            "version" to "1.2.3",
            "license" to "GNU General Public License, version 3 or later",
            "connection" to SCM_CONNECTION,
            "name" to "mcav-fixture"
        )
        assertEquals(expected, names)
    }

    @Test
    fun shouldCarryTheProjectLicenseAndThirdPartyNoticesInEveryPublishedJar(): Unit {
        this.prepareProject()
        this.run("jar", "sourcesJar", "javadocJar")

        val classifiers: List<String> = listOf("", "-sources", "-javadoc")
        val legalTexts: List<String> = listOf(LICENSE_FILE, NOTICES_FILE).map { this.directory.resolve(it).toFile().readText() }
        val jars: Map<String, List<String?>> = classifiers.associateWith(this::readLegalEntries)
        val expected: Map<String, List<String?>> = classifiers.associateWith { legalTexts }
        assertEquals(expected, jars)
    }

    @Test
    fun shouldLetTheMutationTestJvmReadTheGeneratedPublication(): Unit {
        this.prepareProject()

        val catalog: File = this.directory.resolve(CATALOG_PATH).toFile()
        catalog.parentFile.mkdirs()
        Path.of(REPOSITORY_DIRECTORY, CATALOG_PATH).toFile().copyTo(catalog)
        this.directory.resolve(BUILD_SCRIPT).toFile().appendText(
            """
            apply plugin: 'mcav.mutation'
            tasks.register('readMutationPom', JavaExec) {
                dependsOn 'classes', 'generatePomFileForMavenPublication'
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'Example'
                doFirst { jvmArgs project.pitest.jvmArgs.get() }
            }
            """.trimIndent()
        )
        this.directory.resolve(SOURCE_PATH).toFile().writeText(
            """
            import java.nio.file.Files;
            import java.nio.file.Path;

            public class Example {
                public static void main(String[] args) throws Exception {
                    String pom = System.getProperty("mcav.published.pom");
                    if (pom == null) {
                        throw new IllegalStateException("PIT's JVM cannot see the generated publication POM");
                    }
                    String contents = Files.readString(Path.of(pom));
                    if (!contents.contains("<artifactId>mcav-fixture</artifactId>")) {
                        throw new IllegalStateException("PIT's JVM must read this project's generated POM");
                    }
                    Files.writeString(Path.of("build/mutation-pom.txt"), contents);
                }
            }
            """.trimIndent()
        )
        this.run("readMutationPom")

        val generated: String = this.directory.resolve(POM_PATH).toFile().readText()
        val read: String = this.directory.resolve(MUTATION_POM_PATH).toFile().readText()
        assertEquals(generated, read)
    }

    @Test
    fun shouldFollowTheRootProjectGroupInThePublishedGroup(): Unit {
        this.prepareProject()
        this.directory.resolve(BUILD_SCRIPT).toFile().appendText("group = 'fixture.changed'\n")
        this.run(POM_TASK)

        val project: Element = this.readPom()
        val groupId: String = this.textOf(project, GROUP_ID_ELEMENT)
        assertEquals("fixture.changed", groupId)
    }

    private fun prepareProject(): Unit {
        val project: File = this.directory.toFile()
        project.resolve(SETTINGS_SCRIPT).writeText("rootProject.name = 'mcav-fixture'\n")
        project.resolve(BUILD_SCRIPT).writeText("plugins { id 'mcav.publishing' }\ngroup = 'me.brandonli'\nversion = '1.2.3'\n")
        project.resolve(LICENSE_FILE).writeText("Fixture license: preserve the complete root file.\n")
        project.resolve(NOTICES_FILE).writeText("Fixture notices: preserve the complete root file.\n")

        val source: File = project.resolve(SOURCE_PATH)
        source.parentFile.mkdirs()
        source.writeText("/** A public example for the documentation jar. */ public class Example {}\n")
    }

    private fun run(vararg tasks: String): Unit {
        GradleRunner.create()
            .withProjectDir(this.directory.toFile())
            .withPluginClasspath()
            .withArguments(*tasks, "--max-workers=2", "--stacktrace")
            .build()
    }

    private fun readPom(): Element {
        val pom: File = this.directory.resolve(POM_PATH).toFile()
        val document: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
        return document.documentElement
    }

    private fun textOf(project: Element, tag: String): String {
        return project.getElementsByTagName(tag).item(0)?.textContent ?: ""
    }

    private fun licenseNameOf(project: Element): String? {
        val license: Node? = project.getElementsByTagName("license").item(0)
        return license?.childNodes?.let(this::nameOf)
    }

    private fun nameOf(children: NodeList): String {
        val indexes: IntRange = 0 until children.length
        val nodes: List<Node> = indexes.map(children::item)
        val name: Node = nodes.single { it.nodeName == "name" }
        return name.textContent
    }

    private fun readLegalEntries(classifier: String): List<String?> {
        val file: File = this.directory.resolve("build/libs/mcav-fixture-1.2.3$classifier.jar").toFile()
        return ZipFile(file).use(this::readLegalTexts)
    }

    private fun readLegalTexts(jar: ZipFile): List<String?> {
        return listOf(LICENSE_ENTRY, NOTICES_ENTRY).map { this.readEntry(jar, it) }
    }

    private fun readEntry(jar: ZipFile, name: String): String? {
        return jar.getEntry(name)?.let { jar.getInputStream(it).reader().readText() }
    }
}
