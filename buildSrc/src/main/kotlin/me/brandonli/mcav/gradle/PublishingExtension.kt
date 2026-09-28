package me.brandonli.mcav.gradle

import org.gradle.api.provider.Property
import org.gradle.api.tasks.bundling.AbstractArchiveTask

/**
 * The settings of `mcav.publishing` a module can change. By default a module publishes its Java component, the jar with
 * its sources and Javadoc, with a POM and Gradle module metadata that list its dependencies.
 */
abstract class McavPublishingExtension {

    /**
     * A jar that bundles the module's dependencies, such as a shadow jar, published with the sources and the Javadoc in
     * place of the Java component, so the POM lists no dependencies.
     */
    abstract val bundledJar: Property<AbstractArchiveTask>

    /** Whether Gradle module metadata is published beside the POM, true by default. */
    abstract val gradleModuleMetadata: Property<Boolean>
}
