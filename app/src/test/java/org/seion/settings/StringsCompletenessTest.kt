package org.seion.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The resource files themselves: every translatable English string has a pt-BR entry, and the product names are
 * untranslatable and absent from pt-BR.
 */
class StringsCompletenessTest {
    private data class Entry(val name: String, val translatable: Boolean, val text: String)

    private fun entries(path: String): Map<String, Entry> {
        val file = listOf(File(path), File("app/$path")).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val out = LinkedHashMap<String, Entry>()
        for (tag in listOf("string", "plurals", "string-array")) {
            val nodes = root.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                out[e.getAttribute("name")] = Entry(e.getAttribute("name"), e.getAttribute("translatable") != "false", e.textContent.trim())
            }
        }
        return out
    }

    private val en = entries("src/main/res/values/strings.xml")
    private val pt = entries("src/main/res/values-pt-rBR/strings.xml")

    @Test fun `every translatable English string has a pt-BR entry`() {
        val missing = en.values.filter { it.translatable && it.name !in pt }.map { it.name }
        assertEquals(emptyList<String>(), missing)
        val extra = pt.keys.filter { it !in en }
        assertEquals(emptyList<String>(), extra)
    }

    @Test fun `brand names are untranslatable and absent from pt-BR`() {
        for ((name, text) in listOf(
            "background_ambient" to "Seion Infinite",
            "crystalx_name" to "CrystalX+",
            "renovapure_name" to "Seion RenovaPure"
        )) {
            val e = en.getValue(name)
            assertEquals(text, e.text)
            assertTrue("$name must be translatable=\"false\"", !e.translatable)
            assertTrue("$name must not be in values-pt-rBR", name !in pt)
        }
    }

    @Test fun `the sound copy makes no distortion-free claims in either language`() {
        val all = en.values.map { "en ${it.name}: ${it.text}" } + pt.values.map { "pt ${it.name}: ${it.text}" }
        val sound = all.filter { it.substringAfter(' ').let { n -> n.startsWith("crystalx") || n.startsWith("renovapure") } }
        assertTrue("no sound copy found", sound.size >= 8)
        // No "zero distortion" claim: the exciter adds harmonics on purpose. "Sem distorção por saturação" names
        // the clipping the limiter prevents and is allowed.
        val claims =
            Regex("""(?i)zero distortion|distortion-free|no distortion|never distorts|distorção zero|sem distorção(?! por saturação)""")
        assertEquals(emptyList<String>(), sound.filter { claims.containsMatchIn(it) })
    }

    @Test fun `untranslatable strings are not translated`() {
        val translated = en.values.filter { !it.translatable && it.name in pt }.map { it.name }
        assertEquals(emptyList<String>(), translated)
    }
}
