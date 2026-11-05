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

import me.brandonli.mcav.gradle.CatalogUtils
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.TestRuntimeProfile
import me.brandonli.mcav.gradle.libs

plugins {
    java
    id("info.solidsoft.pitest")
}

val pitestAlias: String = "pitest"
val pitestJunit5PluginAlias: String = "pitest-junit5-plugin"
val mutatedClasses: String = "me.brandonli.mcav.*"
val testJavaHomeProperty: String = "mcav.test_java_home"
val javaAgentConfiguration: String = "javaAgent"
val javaAgentOption: String = "-javaagent:"

val mutationThreads: Int = 4
val mutationTimeoutMilliseconds: Int = 4000

// forMutation copies no argument providers, so the minions get the agent of mcav.testing here, when it is applied
val javaAgentArguments: Provider<List<String>> = provider {
    configurations.matching { it.name == javaAgentConfiguration }.map { "$javaAgentOption${it.singleFile}" }
}

pitest {
    pitestVersion = CatalogUtils.versionOf(libs, pitestAlias)
    junit5PluginVersion = CatalogUtils.versionOf(libs, pitestJunit5PluginAlias)
    targetClasses = setOf(mutatedClasses)
    threads = mutationThreads
    timeoutConstInMillis = mutationTimeoutMilliseconds
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    jvmArgs = provider { TestRuntimeProfile.forMutation(tasks.test.get()) + javaAgentArguments.get() }

    val testJavaHome: Provider<String> = providers.gradleProperty(testJavaHomeProperty)
    if (testJavaHome.isPresent) {
        jvmPath = file(HostPlatform.current().javaExecutable(testJavaHome.get()))
    }
}
