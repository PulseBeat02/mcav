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

import org.gradle.api.tasks.testing.Test as GradleTest
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.process.CommandLineArgumentProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestRuntimeProfileTest {

    @Test
    fun mutationReceivesTheTestArgumentsAndEverySystemPropertyExactlyOnce() {
        val project = ProjectBuilder.builder().build()
        val test = project.tasks.create("ordinaryTest", GradleTest::class.java)
        test.jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dmcav.published.pom=build/pom.xml")
        test.maxHeapSize = "2g"
        test.jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:coverage.jar") })
        test.systemProperty("mcav.networkTests", "false")
        test.systemProperty("mcav.syncMeasurement", "false")
        test.systemProperty("jqwik.database", "build/jqwik-database")
        test.systemProperty("jqwik.failures.after.default", "PREVIOUS_SEED")
        test.systemProperty("jqwik.tries.default", "10")
        assertEquals(listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Djqwik.database=build/jqwik-database", "-Djqwik.failures.after.default=PREVIOUS_SEED", "-Djqwik.tries.default=10",
            "-Dmcav.networkTests=false", "-Dmcav.published.pom=build/pom.xml", "-Dmcav.syncMeasurement=false"
        ), TestRuntimeProfile.forMutation(test))
        test.systemProperty("mcav.networkTests", "true")
        test.systemProperty("mcav.syncMeasurement", "true")
        test.systemProperty("jqwik.tries.default", "500")
        assertEquals(listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Djqwik.database=build/jqwik-database", "-Djqwik.failures.after.default=PREVIOUS_SEED", "-Djqwik.tries.default=500",
            "-Dmcav.networkTests=true", "-Dmcav.published.pom=build/pom.xml", "-Dmcav.syncMeasurement=true"
        ), TestRuntimeProfile.forMutation(test))
    }
}
