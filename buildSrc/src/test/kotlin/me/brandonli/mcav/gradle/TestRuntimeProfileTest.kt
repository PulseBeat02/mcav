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

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test as GradleTest
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestRuntimeProfileTest {

    private companion object {

        const val JQWIK_DATABASE_PATH: String = "build/jqwik-database"

        const val C1_ONLY_ARGUMENT: String = "-XX:TieredStopAtLevel=1"
    }

    @Test
    fun shouldGiveMutationTheTestArgumentsAndEverySystemPropertyExactlyOnce(): Unit {
        val project: Project = ProjectBuilder.builder().build()
        val test: GradleTest = project.tasks.register("ordinaryTest", GradleTest::class.java).get()
        test.jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dmcav.published.pom=build/pom.xml")
        test.maxHeapSize = "2g"
        test.jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:coverage.jar") })
        test.systemProperty("mcav.networkTests", "false")
        test.systemProperty("mcav.sync_measurement", "false")
        test.systemProperty("jqwik.database", JQWIK_DATABASE_PATH)
        test.systemProperty("jqwik.failures.after.default", "PREVIOUS_SEED")
        test.systemProperty("jqwik.tries.default", "10")

        val arguments: List<String> = TestRuntimeProfile.forMutation(test)
        assertEquals(listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Djqwik.database=build/jqwik-database", "-Djqwik.failures.after.default=PREVIOUS_SEED", "-Djqwik.tries.default=10",
            "-Dmcav.networkTests=false", "-Dmcav.published.pom=build/pom.xml", "-Dmcav.sync_measurement=false",
            C1_ONLY_ARGUMENT
        ), arguments)

        test.systemProperty("mcav.networkTests", "true")
        test.systemProperty("mcav.sync_measurement", "true")
        test.systemProperty("jqwik.tries.default", "500")

        val changedArguments: List<String> = TestRuntimeProfile.forMutation(test)
        assertEquals(listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Djqwik.database=build/jqwik-database", "-Djqwik.failures.after.default=PREVIOUS_SEED", "-Djqwik.tries.default=500",
            "-Dmcav.networkTests=true", "-Dmcav.published.pom=build/pom.xml", "-Dmcav.sync_measurement=true",
            C1_ONLY_ARGUMENT
        ), changedArguments)
    }
}
