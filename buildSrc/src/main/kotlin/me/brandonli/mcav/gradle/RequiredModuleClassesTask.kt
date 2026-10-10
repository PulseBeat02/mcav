package me.brandonli.mcav.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class RequiredModuleClassesTask : DefaultTask() {

    companion object {
        const val RESOURCE = "META-INF/mcav/required-classes.txt"
    }

    @get:Classpath
    abstract val pluginClasses: ConfigurableFileCollection

    @get:Classpath
    abstract val modules: ConfigurableFileCollection

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
