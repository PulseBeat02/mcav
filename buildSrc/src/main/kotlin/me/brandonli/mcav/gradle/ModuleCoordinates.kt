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
import org.gradle.api.artifacts.Configuration

/**
 * Names Maven modules: the coordinates and project paths of the modules of this build, and the groups and names of the
 * external modules that module scripts exclude.
 */
object ModuleCoordinates {

    /**
     * The Maven group of Spring Boot.
     */
    const val SPRING_BOOT_GROUP: String = "org.springframework.boot"

    /**
     * The name of Spring Boot's logging starter.
     */
    const val SPRING_BOOT_LOGGING_MODULE: String = "spring-boot-starter-logging"

    /**
     * The Maven group of Apache Tomcat.
     */
    const val TOMCAT_GROUP: String = "org.apache.tomcat"

    /**
     * The name of Tomcat's annotations API module.
     */
    const val TOMCAT_ANNOTATIONS_MODULE: String = "tomcat-annotations-api"

    private const val PROJECT_PATH_PREFIX: String = ":"

    /**
     * Returns the Maven coordinate of a module of this build, with the group and the version of the root project.
     *
     * @param project    a project of the build
     * @param moduleName the artifact name of the module, such as `mcav-common`
     * @return the coordinate, as `group:name:version`
     */
    fun coordinateOf(project: Project, moduleName: String): String {
        val root: Project = project.rootProject
        return "${root.group}:$moduleName:${root.version}"
    }

    /**
     * Returns the project paths of the dependencies of [configuration] that are modules of this build.
     *
     * A dependency is a module when it has the group of [root] and [root] has a project of its name.
     *
     * @param configuration the configuration whose declared dependencies are checked
     * @param root          the root project of the build
     * @return the project paths, such as `:mcav-common`, in declaration order
     */
    fun modulePathsOf(configuration: Configuration, root: Project): List<String> {
        val group: String = root.group.toString()
        return configuration.dependencies
            .filter { it.group == group && root.findProject("$PROJECT_PATH_PREFIX${it.name}") != null }
            .map { "$PROJECT_PATH_PREFIX${it.name}" }
    }
}
