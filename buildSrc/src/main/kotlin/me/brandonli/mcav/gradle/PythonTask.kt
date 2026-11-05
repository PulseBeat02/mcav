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

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider

/**
 * A task that runs the Python of a [PythonEnvironment].
 *
 * [useEnvironment] connects the task to its environment, so that the task runs again when the requirements, the Python
 * version or the platform change.
 */
abstract class PythonTask : DefaultTask() {

    internal companion object {

        const val PYTHON_EXECUTABLE: String = "python"

        const val NO_USER_SITE_VARIABLE: String = "PYTHONNOUSERSITE"
        const val NO_BYTECODE_VARIABLE: String = "PYTHONDONTWRITEBYTECODE"
        const val HASH_SEED_VARIABLE: String = "PYTHONHASHSEED"
        const val MATPLOTLIB_CONFIGURATION_VARIABLE: String = "MPLCONFIGDIR"

        const val ENABLED: String = "1"
        const val FIXED_HASH_SEED: String = "0"

        const val MATPLOTLIB_CONFIGURATION_DIRECTORY: String = "matplotlib"
    }

    /**
     * The requirements lock of the environment.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val requirements: RegularFileProperty

    /**
     * The Python version of the environment.
     */
    @get:Input
    abstract val pythonVersion: Property<String>

    /**
     * The uv target of the host, so that outputs built on one platform are not reused on another.
     */
    @get:Input
    abstract val platform: Property<String>

    /**
     * The `python` executable of the environment.
     */
    @get:Internal
    abstract val pythonExecutable: RegularFileProperty

    /**
     * Runs this task with the Python of [environment], after the environment is installed.
     *
     * @param environment the task that installs the environment
     */
    fun useEnvironment(environment: TaskProvider<PythonEnvironment>): Unit {
        this.dependsOn(environment)
        this.requirements.set(environment.flatMap { it.requirements })
        this.pythonVersion.set(environment.flatMap { it.pythonVersion })
        this.platform.set(HostPlatform.current().uvTarget)
        this.pythonExecutable.set(environment.flatMap { it.executable(PYTHON_EXECUTABLE) })
    }
}
