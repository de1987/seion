package org.seion.art

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android compiles java.util.regex with ICU, which rejects the JVM-only UNICODE_CHARACTER_CLASS flag `(?U)`: a pattern
 * using it compiles in these JVM tests and throws PatternSyntaxException on the device (where the
 * cover chain and the identify worker would fail at CoverMatch's class init). ICU's \w, \d and \b are Unicode already.
 */
class IcuRegexCompatTest {
    @Test fun `no source pattern uses the JVM-only U flag`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val flag = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]""")
        val offenders =
            root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
                f.readLines().withIndex().filter { flag.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" }
            }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
