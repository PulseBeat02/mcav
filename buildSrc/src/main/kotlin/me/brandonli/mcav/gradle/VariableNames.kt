package me.brandonli.mcav.gradle

import com.sun.source.tree.BindingPatternTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.EnhancedForLoopTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.TryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import java.io.File
import java.net.URI
import javax.lang.model.element.Modifier
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/**
 * A variable whose name says nothing: one letter, or one of the short forms a word says better.
 *
 * @property line the line of the declaration
 * @property name the name
 * @property kind what is declared: a field, record component, parameter, lambda or catch parameter, loop variable,
 *   resource, pattern variable or local variable
 * @property member the declaration as `package.Type#name`, how a published name is listed to keep it
 */
data class UndescriptiveName(val line: Long, val name: String, val kind: String, val member: String)

/**
 * Finds the variables of Java code named by one letter, or by a short form the naming rules spell out (`buf` for
 * buffer, `idx` for index...), in every kind of declaration. The code is parsed with the JDK's own compiler, so every
 * declaration is seen as the compiler sees it; generic type parameters are types, not variables, and `_` names
 * nothing.
 */
object VariableNames {

    /** Short forms a word says better: the naming rules' list, as whole names. */
    private val ABBREVIATIONS = setOf("buf", "tmp", "cfg", "idx", "cnt", "val", "res", "sb", "ctx", "mgr", "str", "arr", "len", "num")

    private const val UNNAMED = "_"

    /** The undescriptive names of one source, for tests. */
    fun find(source: String): List<UndescriptiveName> {
        val file = object : SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        return find(listOf(file)).values.single()
    }

    /** The undescriptive names of source files, by file. */
    fun findInFiles(files: List<File>): Map<File, List<UndescriptiveName>> {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val fileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
        fileManager.use {
            val objects = it.getJavaFileObjectsFromFiles(files).toList()
            val byUri = files.associateBy { file -> file.toPath().toUri() }
            return find(objects).mapKeys { (uri, _) -> byUri[uri] ?: File(uri) }
        }
    }

    private fun find(files: List<JavaFileObject>): Map<URI, List<UndescriptiveName>> {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val task = compiler.getTask(null, null, { }, listOf("-proc:none"), null, files) as JavacTask
        val positions = Trees.instance(task).sourcePositions
        return task.parse().associate { unit -> unit.sourceFile.toUri() to Scanner(unit, positions).names() }
    }

    private class Scanner(
        private val unit: CompilationUnitTree,
        private val positions: SourcePositions,
    ) : TreePathScanner<Unit, Unit>() {

        private val found = mutableListOf<UndescriptiveName>()

        fun names(): List<UndescriptiveName> {
            scan(unit, Unit)
            return found
        }

        override fun visitVariable(node: VariableTree, unused: Unit?): Unit? {
            val name = node.name.toString()
            if (isUndescriptive(name)) {
                val line = unit.lineMap.getLineNumber(positions.getStartPosition(unit, node))
                found += UndescriptiveName(line, name, kind(node), "${owner()}#$name")
            }
            return super.visitVariable(node, unused)
        }

        private fun isUndescriptive(name: String): Boolean =
            name != UNNAMED && name.isNotEmpty() && (name.length == 1 || name in ABBREVIATIONS)

        private fun kind(node: VariableTree): String {
            val parent = currentPath.parentPath.leaf
            return when {
                parent is ClassTree && parent.kind == Tree.Kind.RECORD && Modifier.STATIC !in node.modifiers.flags -> "record component"
                parent is ClassTree -> "field"
                parent is MethodTree -> "parameter"
                parent is LambdaExpressionTree -> "lambda parameter"
                parent is CatchTree -> "catch parameter"
                parent is ForLoopTree || parent is EnhancedForLoopTree -> "loop variable"
                parent is TryTree -> "resource"
                parent is BindingPatternTree -> "pattern variable"
                else -> "local variable"
            }
        }

        private fun owner(): String {
            val types = generateSequence(currentPath) { it.parentPath }
                .map { it.leaf }
                .filterIsInstance<ClassTree>()
                .map { it.simpleName.toString().ifEmpty { "<anonymous>" } }
                .toList()
                .asReversed()
            val packageName = unit.packageName?.toString()
            return (listOfNotNull(packageName) + types).joinToString(".")
        }
    }
}
