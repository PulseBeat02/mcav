package me.brandonli.mcav.gradle

/** A fully qualified type name written in Java code, at its 1-based line. */
data class QualifiedName(val line: Int, val name: String)

/**
 * Finds fully qualified type names in Java code: names such as `java.util.Arrays` or `org.bukkit.Bukkit` that a
 * source file uses instead of importing them. Package and import declarations, comments (Javadoc included), string,
 * character and text block literals are not code and are never reported. A line that must keep a qualified name,
 * because two types of that simple name meet in one file, is marked with a comment `// fqn: <why>`.
 */
object QualifiedNames {

    private val ROOTS = listOf("java", "javax", "jakarta", "jdk", "sun", "com", "org", "net", "io", "me", "uk", "de", "xyz", "it", "dev", "info")

    private val NAME = Regex("(?<![\\w.$])((?:${ROOTS.joinToString("|")})\\.(?:[a-z_][a-z0-9_]*\\.)+[A-Z][\\w$]*)")

    private val MARKER = Regex("//\\s*fqn:\\s*\\S")

    private val DECLARATION = Regex("^\\s*(package|import)\\s")

    /**
     * Finds the qualified type names of a Java source file.
     *
     * @param source the text of the file
     * @return every qualified name in code, in order, one entry per occurrence
     */
    fun find(source: String): List<QualifiedName> {
        val lines = source.split('\n')
        val code = JavaSource.mask(source).split('\n')
        val found = mutableListOf<QualifiedName>()
        code.forEachIndexed { index, line ->
            if (DECLARATION.containsMatchIn(line) || MARKER.containsMatchIn(lines[index])) {
                return@forEachIndexed
            }
            NAME.findAll(line).forEach { match -> found += QualifiedName(index + 1, match.groupValues[1]) }
        }
        return found
    }
}
