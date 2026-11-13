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

plugins {
    id("mcav.module")
    id("mcav.publishing")
    id("mcav.script-testing")
}

val cefTestGroup: String = "cef"
val offscreenBrowserClasses: String = "org.cef.browser.MCAVOffscreenBrowser*"

val helperCoveragePath: String = "jacoco/helper.exec"

pitest {
    excludedGroups = setOf(cefTestGroup)
    targetClasses.add(offscreenBrowserClasses)
}

val helperCoverage: Provider<RegularFile> = layout.buildDirectory.file(helperCoveragePath)

tasks.test {
    val coverage: Provider<RegularFile> = helperCoverage
    this.outputs.file(coverage).withPropertyName("helperCoverage")
    this.doFirst {
        coverage.get().asFile.delete()
    }
}

tasks.jacocoTestReport {
    this.executionData(helperCoverage)
}
