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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModuleClassesTest {

    private companion object {

        const val MCAV_CLASS: String = "me/brandonli/mcav/MCAV"
        const val RESULT_CLASS: String = "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result"
        const val PACK_SERVER_CLASS: String = "me/brandonli/mcav/bukkit/media/mcv2/Mcv2PackServer"
        const val SANDBOX_CLASS: String = "me/brandonli/mcav/plugin/MCAVSandbox"

        const val COMMON_JAR: String = "mcav-common.jar"
        const val BUKKIT_JAR: String = "mcav-bukkit.jar"
        const val MISSING_JAR: String = "missing.jar"
        const val MANIFEST_ENTRY: String = "META-INF/MANIFEST.MF"
        const val PACKAGE_ENTRY: String = "me/brandonli/mcav/common/"

        const val VM_PACKAGE_PATH: String = "me/brandonli/mcav/vm"
        const val VM_PLAYER_CLASS_FILE: String = "me/brandonli/mcav/vm/VMPlayer.class"
        const val VM_NOTES_FILE: String = "me/brandonli/mcav/vm/notes.txt"

        const val PLUGIN_DIRECTORY: String = "plugin"
        const val PLUGIN_PACKAGE_PATH: String = "me/brandonli/mcav/plugin"
        const val SANDBOX_CLASS_FILE: String = "me/brandonli/mcav/plugin/MCAVSandbox.class"
        const val PLUGIN_README_FILE: String = "me/brandonli/mcav/plugin/README.txt"

        const val NOT_A_CLASS: String = "not a class"

        const val CLASSES_DIRECTORY: String = "classes"
        const val ABSENT_DIRECTORY: String = "absent"
        const val PROJECT_DIRECTORY: String = "project"
        const val OUTPUT_DIRECTORY: String = "output"
        const val LIBRARIES_DIRECTORY: String = "libraries"

        const val JAVA_LIBRARY_PLUGIN: String = "java-library"
        const val IMPLEMENTATION_CONFIGURATION: String = "implementation"
        const val MODULE_NAME: String = "mcav-common"
    }

    @TempDir
    lateinit var folder: Path

    @Test
    fun shouldReadTheModuleClassesThatAClassFileNamesInItsDescriptorsAndSignatures(): Unit {
        val bytes: ByteArray = this.classBytes(
            RESULT_CLASS,
            "(Lme/brandonli/mcav/MCAV;)V",
            "Ljava/util/List<Lme/brandonli/mcav/bukkit/media/mcv2/Mcv2Result\$Statistics;>;",
            "java/lang/String"
        )
        val referenced: Set<String> = ModuleClasses.referenced(bytes)
        assertEquals(
            setOf(
                RESULT_CLASS,
                MCAV_CLASS,
                "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result\$Statistics"
            ),
            referenced
        )
    }

    @Test
    fun shouldFindTheModuleClassesOfJarsAndFoldersButNoOthers(): Unit {
        val classes: File = this.folder.resolve(CLASSES_DIRECTORY).toFile()
        File(classes, VM_PACKAGE_PATH).mkdirs()
        File(classes, VM_PLAYER_CLASS_FILE).writeBytes(ByteArray(0))
        File(classes, VM_NOTES_FILE).writeText(NOT_A_CLASS)

        val jar: File = this.jar(COMMON_JAR, MCAV_CLASS, "com/google/common/base/Preconditions")
        val available: Set<String> = ModuleClasses.available(listOf(classes, jar, this.folder.resolve(MISSING_JAR).toFile()))
        assertEquals(setOf("me/brandonli/mcav/vm/VMPlayer", MCAV_CLASS), available)
    }

    @Test
    fun shouldCloseTheJarOnceItsClassesAreRead(): Unit {
        OpenFiles.assumeListable()

        val jar: File = this.jar(COMMON_JAR, MCAV_CLASS)
        ModuleClasses.available(listOf(jar))

        val open: Boolean = OpenFiles.isOpen(jar)
        assertFalse(open)
    }

    @Test
    fun shouldRequireTheModuleClassesThatThePluginUsesAndTheDownloadedModulesHold(): Unit {
        val plugin: File = this.folder.resolve(PLUGIN_DIRECTORY).toFile()
        File(plugin, PLUGIN_PACKAGE_PATH).mkdirs()
        File(plugin, PLUGIN_README_FILE).writeText(NOT_A_CLASS)
        File(plugin, SANDBOX_CLASS_FILE).writeBytes(
            this.classBytes(
                SANDBOX_CLASS,
                MCAV_CLASS,
                "me/brandonli/mcav/voicechat/SVCFilter",
                PACK_SERVER_CLASS
            )
        )

        val modules: List<File> = listOf(
            this.jar(COMMON_JAR, MCAV_CLASS),
            this.jar(BUKKIT_JAR, PACK_SERVER_CLASS)
        )
        val absent: File = this.folder.resolve(ABSENT_DIRECTORY).toFile()
        val required: List<String> = ModuleClasses.required(listOf(plugin, absent), modules).toList()
        assertEquals(listOf(MCAV_CLASS, PACK_SERVER_CLASS), required)
    }

    @Test
    fun shouldWriteTheRequiredClassesOneALineIntoTheTaskResource(): Unit {
        val plugin: File = this.folder.resolve(PLUGIN_DIRECTORY).toFile()
        File(plugin, PLUGIN_PACKAGE_PATH).mkdirs()
        File(plugin, SANDBOX_CLASS_FILE).writeBytes(
            this.classBytes(
                SANDBOX_CLASS,
                MCAV_CLASS,
                PACK_SERVER_CLASS
            )
        )

        val project: Project = ProjectBuilder.builder().withProjectDir(this.folder.resolve(PROJECT_DIRECTORY).toFile()).build()
        val task: RequiredModuleClassesTask = project.tasks.register("requiredModuleClasses", RequiredModuleClassesTask::class.java).get()
        task.pluginClasses.from(plugin)
        task.modules.from(
            this.jar(COMMON_JAR, MCAV_CLASS),
            this.jar(BUKKIT_JAR, PACK_SERVER_CLASS)
        )
        task.output.set(this.folder.resolve(OUTPUT_DIRECTORY).toFile())
        task.list()

        val resource: String = this.folder.resolve("output/${RequiredModuleClassesTask.RESOURCE}").toFile().readText()
        assertEquals("me/brandonli/mcav/MCAV\nme/brandonli/mcav/bukkit/media/mcv2/Mcv2PackServer\n", resource)
    }

    @Test
    fun shouldKeepOnlyTheArtifactsOfTheModulesOnTheClasspath(): Unit {
        val root: Project = ProjectBuilder.builder().withProjectDir(this.folder.resolve(PROJECT_DIRECTORY).toFile()).build()
        val module: Project = ProjectBuilder.builder().withName(MODULE_NAME).withParent(root).build()
        val other: Project = ProjectBuilder.builder().withName("mcav-other").withParent(root).build()
        for (project in listOf(root, module, other)) {
            project.pluginManager.apply(JAVA_LIBRARY_PLUGIN)
        }

        val libraries: File = this.folder.resolve(LIBRARIES_DIRECTORY).toFile()
        libraries.mkdirs()
        libraries.resolve("external-1.0.jar").writeText("")
        root.repositories.flatDir { this.dirs(libraries) }

        for (path in listOf(module.path, other.path)) {
            val dependency: Dependency = root.dependencies.project(mapOf("path" to path))
            root.dependencies.add(IMPLEMENTATION_CONFIGURATION, dependency)
        }

        root.dependencies.add(IMPLEMENTATION_CONFIGURATION, "fixture:external:1.0")

        val classpath: Configuration = root.configurations.getByName("compileClasspath")

        val artifacts: FileCollection = ModuleClasses.moduleArtifacts(classpath, listOf(module.path))
        val files: Set<File> = artifacts.files
        val owners: Set<String> = files.map { it.toRelativeString(root.projectDir).substringBefore(File.separatorChar) }.toSet()
        assertEquals(setOf(MODULE_NAME), owners)
    }

    private fun classBytes(vararg names: String): ByteArray {
        val text: String = names.joinToString("\u0001\u0000\u0010", prefix = "Êþº¾", postfix = "\u0007")
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    private fun jar(name: String, vararg classes: String): File {
        val file: File = this.folder.resolve(name).toFile()
        ZipOutputStream(file.outputStream()).use { this.writeEntries(it, classes) }

        return file
    }

    private fun writeEntries(zip: ZipOutputStream, classes: Array<out String>): Unit {
        this.writeEmptyEntry(zip, PACKAGE_ENTRY)
        for (name in classes) {
            this.writeEmptyEntry(zip, "$name.class")
        }

        this.writeEmptyEntry(zip, MANIFEST_ENTRY)
    }

    private fun writeEmptyEntry(zip: ZipOutputStream, name: String): Unit {
        zip.putNextEntry(ZipEntry(name))
        zip.closeEntry()
    }
}
