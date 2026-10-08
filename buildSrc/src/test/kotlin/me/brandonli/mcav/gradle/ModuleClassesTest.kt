package me.brandonli.mcav.gradle

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModuleClassesTest {

    @TempDir
    lateinit var folder: Path

    /** A file shaped like a class file's constant pool: names between other bytes, as the compiler writes them. */
    private fun classBytes(vararg names: String): ByteArray =
        names.joinToString("\u0001\u0000\u0010", prefix = "Êþº¾", postfix = "\u0007") { it }
            .toByteArray(Charsets.ISO_8859_1)

    private fun jar(name: String, vararg classes: String): File {
        val file = folder.resolve(name).toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            classes.forEach {
                zip.putNextEntry(ZipEntry("$it.class"))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun readsTheModuleClassesAClassFileNamesInItsDescriptorsAndSignatures() {
        val bytes = classBytes(
            "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result",
            "(Lme/brandonli/mcav/MCAV;)V",
            "Ljava/util/List<Lme/brandonli/mcav/bukkit/media/mcv2/Mcv2Result\$Statistics;>;",
            "java/lang/String"
        )
        assertEquals(
            setOf(
                "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result",
                "me/brandonli/mcav/MCAV",
                "me/brandonli/mcav/bukkit/media/mcv2/Mcv2Result\$Statistics"
            ),
            ModuleClasses.referenced(bytes)
        )
    }

    @Test
    fun findsTheModuleClassesOfJarsAndFoldersButNoOthers() {
        val classes = folder.resolve("classes").toFile()
        File(classes, "me/brandonli/mcav/vm").mkdirs()
        File(classes, "me/brandonli/mcav/vm/VMPlayer.class").writeBytes(ByteArray(0))
        File(classes, "me/brandonli/mcav/vm/notes.txt").writeText("not a class")
        val jar = jar("mcav-common.jar", "me/brandonli/mcav/MCAV", "com/google/common/base/Preconditions")
        assertEquals(
            setOf("me/brandonli/mcav/vm/VMPlayer", "me/brandonli/mcav/MCAV"),
            ModuleClasses.available(listOf(classes, jar, folder.resolve("missing.jar").toFile()))
        )
    }

    @Test
    fun requiresTheModuleClassesThePluginUsesThatTheDownloadedModulesHold() {
        val plugin = folder.resolve("plugin").toFile()
        File(plugin, "me/brandonli/mcav/sandbox").mkdirs()
        File(plugin, "me/brandonli/mcav/sandbox/MCAVSandbox.class").writeBytes(
            classBytes(
                "me/brandonli/mcav/sandbox/MCAVSandbox",
                "me/brandonli/mcav/MCAV",
                "me/brandonli/mcav/svc/SVCFilter",
                "me/brandonli/mcav/bukkit/media/mcv2/Mcv2PackServer"
            )
        )
        val modules = listOf(jar("mcav-common.jar", "me/brandonli/mcav/MCAV"), jar("mcav-bukkit.jar", "me/brandonli/mcav/bukkit/media/mcv2/Mcv2PackServer"))
        assertEquals(
            listOf("me/brandonli/mcav/MCAV", "me/brandonli/mcav/bukkit/media/mcv2/Mcv2PackServer"),
            ModuleClasses.required(listOf(plugin, folder.resolve("absent").toFile()), modules).toList()
        )
    }
}
