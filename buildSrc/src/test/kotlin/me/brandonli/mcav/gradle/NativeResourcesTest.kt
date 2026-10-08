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

import java.nio.file.Path
import java.util.zip.ZipFile
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeResourcesTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun aShadowConsumerBuildsAndPackagesTheGeneratedResourceArtifact() {
        val project = directory.toFile()
        project.resolve("settings.gradle").writeText("""
            rootProject.name = 'native-resources'
            include 'producer', 'consumer'
            dependencyResolutionManagement {
                versionCatalogs {
                    libs {
                        version('zig', 'fixture')
                        version('clang-format', 'fixture')
                    }
                }
            }
        """.trimIndent())
        val producer = project.resolve("producer").apply { mkdirs() }
        val sources = producer.resolve("src/main/native/mcv2").apply { mkdirs() }
        sources.resolve("mcv2.cpp").writeText("native fixture bytes")
        val compiler = project.resolve("zig").apply { writeText("unused fixture compiler") }
        producer.resolve("build.gradle").writeText("""
            plugins { id 'mcav.natives' }
            tasks.named('buildMcv2Natives') {
                actions.clear()
                doLast {
                    def library = outputDirectory.file('mcav/mcv2/natives/linux-x86_64/libmcv2kernels.so').get().asFile
                    library.parentFile.mkdirs()
                    library.bytes = sourcesDirectory.file('mcv2.cpp').get().asFile.bytes
                }
            }
        """.trimIndent())
        val consumer = project.resolve("consumer").apply { mkdirs() }
        consumer.resolve("build.gradle").writeText("""
            plugins {
                id 'java'
                id 'com.gradleup.shadow'
            }
            configurations {
                natives { canBeConsumed = false; canBeResolved = true }
            }
            dependencies {
                natives project(path: ':producer', configuration: 'mcv2NativeResources')
            }
            tasks.named('shadowJar') { from(project.configurations.natives) }
        """.trimIndent())
        val runner = GradleRunner.create().withProjectDir(project).withPluginClasspath()
            .withEnvironment(System.getenv() + ("ZIG" to compiler.absolutePath))
            .withArguments(":consumer:shadowJar", "--stacktrace")
        val first = runner.build()
        assertNull(first.task(":producer:downloadMcv2Zig"))
        assertEquals(TaskOutcome.SUCCESS, first.task(":producer:buildMcv2Natives")!!.outcome)
        ZipFile(consumer.resolve("build/libs/consumer-all.jar")).use { archive ->
            val library = archive.getEntry("mcav/mcv2/natives/linux-x86_64/libmcv2kernels.so")
            assertNotNull(library)
            assertEquals("native fixture bytes", archive.getInputStream(library).reader().readText())
        }
        assertEquals(TaskOutcome.UP_TO_DATE, runner.build().task(":producer:buildMcv2Natives")!!.outcome)
        sources.resolve("mcv2.cpp").writeText("changed native fixture bytes")
        assertEquals(TaskOutcome.SUCCESS, runner.build().task(":producer:buildMcv2Natives")!!.outcome)
        ZipFile(consumer.resolve("build/libs/consumer-all.jar")).use { archive ->
            assertEquals("changed native fixture bytes", archive.getInputStream(
                archive.getEntry("mcav/mcv2/natives/linux-x86_64/libmcv2kernels.so")).reader().readText())
        }
    }
}
