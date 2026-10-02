package me.brandonli.mcav.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Lists the classes of the downloaded modules that a plugin uses, in `META-INF/mcav/required-classes.txt`, which the
 * plugin's loader checks the downloaded modules against: modules published before the plugin's code would otherwise
 * let the server start the plugin and fail on the first class they lack.
 */
@CacheableTask
abstract class RequiredModuleClassesTask : DefaultTask() {

    companion object {
        /** Where the list is, in the plugin's resources and jar. */
        const val RESOURCE = "META-INF/mcav/required-classes.txt"
    }

    /** The plugin's compiled classes. */
    @get:Classpath
    abstract val pluginClasses: ConfigurableFileCollection

    /** The modules the server downloads, as this build compiles them. */
    @get:Classpath
    abstract val modules: ConfigurableFileCollection

    /** The folder the list is written into, as a resource folder of the plugin. */
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun list() {
        val classes = ModuleClasses.required(pluginClasses.files, modules.files)
        val file = output.get().file(RESOURCE).asFile
        file.parentFile.mkdirs()
        file.writeText(classes.joinToString("\n", postfix = "\n"))
    }
}
