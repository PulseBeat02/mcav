package me.brandonli.mcav.gradle

import java.io.File
import java.util.SortedSet
import java.util.zip.ZipFile

/**
 * Finds the classes of mcav's modules that compiled code uses. A class file names every class it uses in its constant
 * pool, in internal form (`me/brandonli/mcav/...`) or inside descriptors and signatures, which this reads as text.
 */
object ModuleClasses {

    private const val CLASS_SUFFIX = ".class"

    private val NAME = Regex("me/brandonli/mcav/[A-Za-z0-9_$/]+")

    /**
     * The internal names in a class file that start like a class of mcav's modules; a few may be no class at all.
     *
     * @param classFile the bytes of the class file
     * @return the names
     */
    fun referenced(classFile: ByteArray): Set<String> =
        NAME.findAll(String(classFile, Charsets.ISO_8859_1)).map { it.value }.toSet()

    /**
     * The classes of mcav's modules in folders of class files and in jars, by internal name.
     *
     * @param roots the folders and jars
     * @return the names
     */
    fun available(roots: Iterable<File>): Set<String> {
        val names = mutableSetOf<String>()
        roots.filter { it.exists() }.forEach { root ->
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.name.endsWith(CLASS_SUFFIX) }.forEach { file ->
                    names.add(file.relativeTo(root).invariantSeparatorsPath.removeSuffix(CLASS_SUFFIX))
                }
            } else {
                ZipFile(root).use { jar ->
                    jar.entries().asSequence().map { it.name }.filter { it.endsWith(CLASS_SUFFIX) }.forEach {
                        names.add(it.removeSuffix(CLASS_SUFFIX))
                    }
                }
            }
        }
        return names.filter { NAME.matches(it) }.toSet()
    }

    /**
     * The classes of the modules a server downloads that the classes of a plugin use.
     *
     * @param pluginClasses the plugin's compiled classes: folders of class files
     * @param modules       the modules the server downloads: their jars or folders of class files
     * @return the classes, by internal name, sorted
     */
    fun required(pluginClasses: Iterable<File>, modules: Iterable<File>): SortedSet<String> {
        val inModules = available(modules)
        val used = sortedSetOf<String>()
        pluginClasses.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(CLASS_SUFFIX) }.forEach { file ->
                used.addAll(referenced(file.readBytes()).filter { it in inModules })
            }
        }
        return used
    }
}
