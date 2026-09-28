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

    // the first segment of every package this repository's code imports from, and the other common ones
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
        val code = mask(source).split('\n')
        val found = mutableListOf<QualifiedName>()
        code.forEachIndexed { index, line ->
            if (DECLARATION.containsMatchIn(line) || MARKER.containsMatchIn(lines[index])) {
                return@forEachIndexed
            }
            NAME.findAll(line).forEach { match -> found += QualifiedName(index + 1, match.groupValues[1]) }
        }
        return found
    }

    /** The source with every comment and literal replaced by spaces, keeping each line where it was. */
    fun mask(source: String): String {
        val out = StringBuilder(source.length)
        var state = State.CODE
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '
            when (state) {
                State.CODE -> when {
                    c == '/' && next == '/' -> { state = State.LINE_COMMENT; out.append("  "); i += 2 }
                    c == '/' && next == '*' -> { state = State.BLOCK_COMMENT; out.append("  "); i += 2 }
                    source.startsWith("\"\"\"", i) -> { state = State.TEXT_BLOCK; out.append("   "); i += 3 }
                    c == '"' -> { state = State.STRING; out.append(' '); i++ }
                    c == '\'' -> { state = State.CHARACTER; out.append(' '); i++ }
                    else -> { out.append(c); i++ }
                }
                State.LINE_COMMENT -> {
                    if (c == '\n') state = State.CODE
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                }
                State.BLOCK_COMMENT -> when {
                    c == '*' && next == '/' -> { state = State.CODE; out.append("  "); i += 2 }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
                State.TEXT_BLOCK -> when {
                    c == '\\' -> { out.append(' ').append(if (next == '\n') '\n' else ' '); i += 2 }
                    source.startsWith("\"\"\"", i) -> { state = State.CODE; out.append("   "); i += 3 }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
                State.STRING, State.CHARACTER -> {
                    val quote = if (state == State.STRING) '"' else '\''
                    when {
                        c == '\\' -> { out.append("  "); i += 2 }
                        c == quote || c == '\n' -> { state = State.CODE; out.append(if (c == '\n') '\n' else ' '); i++ }
                        else -> { out.append(' '); i++ }
                    }
                }
            }
        }
        return out.toString()
    }

    private enum class State { CODE, LINE_COMMENT, BLOCK_COMMENT, TEXT_BLOCK, STRING, CHARACTER }
}
