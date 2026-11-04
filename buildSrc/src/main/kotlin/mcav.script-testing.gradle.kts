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

import com.github.gradle.node.NodeExtension
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.NodeLayout

plugins {
    id("mcav.mutation")
}

val testNodeProperty: String = "mcav.test_node"

val node: NodeExtension = rootProject.extensions.getByType<NodeExtension>()
val nodeSetup: TaskProvider<Task> = rootProject.tasks.named("nodeSetup")
val host: HostPlatform = HostPlatform.current()
val testNode: Provider<File> = node.resolvedNodeDir.map { NodeLayout.executable(it, host).asFile }

tasks.withType<Test>().configureEach {
    this.dependsOn(nodeSetup)
    this.jvmArgs("-D$testNodeProperty=${testNode.get().absolutePath}")
}

tasks.named("pitest") {
    this.dependsOn(nodeSetup)
}
