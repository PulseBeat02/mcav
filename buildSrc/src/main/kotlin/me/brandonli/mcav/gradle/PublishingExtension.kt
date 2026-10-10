package me.brandonli.mcav.gradle

import org.gradle.api.provider.Property
import org.gradle.api.tasks.bundling.AbstractArchiveTask

abstract class McavPublishingExtension {

    abstract val bundledJar: Property<AbstractArchiveTask>

    abstract val gradleModuleMetadata: Property<Boolean>
}
