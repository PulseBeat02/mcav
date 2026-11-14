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

import org.gradle.api.tasks.testing.Test

internal object TestRuntimeProfile {

    // a higher compiler tier such as Graal can keep running an inlined original after PIT swaps a mutant in
    private const val C1_ONLY_ARGUMENT: String = "-XX:TieredStopAtLevel=1"

    fun forMutation(test: Test): List<String> {
        return test.jvmArgs +
            test.systemProperties.toSortedMap().map { (name, value) -> "-D$name=$value" } +
            C1_ONLY_ARGUMENT
    }
}
