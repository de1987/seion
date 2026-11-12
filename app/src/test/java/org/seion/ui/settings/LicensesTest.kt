package org.seion.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.R

/** The licences screen credits every bundled component and ships the full text of each licence it names. */
class LicensesTest {
    private fun raw(name: String): File =
        listOf(File("src/main/res/raw/$name.txt"), File("app/src/main/res/raw/$name.txt")).first { it.isFile }

    @Test fun `the FFmpeg decoder is listed only when it is built in`() {
        assertTrue(licenses(hasFfmpeg = true).any { it.first == R.string.license_ffmpeg && it.second == R.string.license_gpl3 })
        assertFalse(licenses(hasFfmpeg = false).any { it.first == R.string.license_ffmpeg })
    }

    @Test fun `the GPL text always ships, since it is the app's own licence`() {
        val with = licenseTexts(hasFfmpeg = true).single { it.raw == R.raw.gpl_3_0 }
        val without = licenseTexts(hasFfmpeg = false).single { it.raw == R.raw.gpl_3_0 }
        assertEquals(R.string.license_gpl3_users_with_ffmpeg, with.users)
        assertEquals(R.string.license_gpl3_users, without.users)
    }

    @Test fun `the repository licence is the same GPL text the app ships`() {
        val license = listOf(File("../LICENSE"), File("LICENSE")).first { it.isFile }
        assertEquals(raw("gpl_3_0").readText(), license.readText())
    }

    @Test fun `every licence named in the list has its full text`() {
        for (ffmpeg in listOf(true, false)) {
            val named = licenses(ffmpeg).map { it.second }.toSet()
            val texts = licenseTexts(ffmpeg).map { it.title }.toSet()
            // Seion's own GPL-3.0 text is there even when no bundled component uses it.
            assertEquals(named + R.string.license_gpl3, texts)
        }
    }

    @Test fun `the licence texts are the full documents`() {
        assertTrue(raw("apache_2_0").readText().contains("END OF TERMS AND CONDITIONS"))
        assertTrue(raw("gpl_3_0").readText().contains("END OF TERMS AND CONDITIONS"))
        assertTrue(raw("mit_checker_qual").readText().contains("Permission is hereby granted"))
        assertTrue(raw("ofl_roboto").readText().contains("SIL OPEN FONT LICENSE"))
        val mpl = raw("mpl_2_0").readText()
        assertTrue(mpl.startsWith("Mozilla Public License Version 2.0"))
        assertTrue(mpl.contains("Exhibit B - \"Incompatible With Secondary Licenses\" Notice"))
        val bsd = raw("bsd_3_clause_protobuf").readText()
        assertTrue(bsd.contains("Neither the name of Google Inc. nor the names of its"))
        assertTrue(bsd.contains("EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE"))
    }

    @Test fun `MIT and BSD texts carry the component's own copyright line`() {
        val mit = raw("mit_checker_qual").readText()
        assertTrue(mit.startsWith("Checker Framework qualifiers\nCopyright 2004-present by the Checker Framework developers"))
        assertTrue(raw("bsd_3_clause_protobuf").readText().startsWith("Copyright 2008 Google Inc.  All rights reserved."))
    }

    @Test fun `every licence text shipped is listed, and every listed one exists`() {
        val shipped = File(raw("apache_2_0").parentFile.path).listFiles()!!.map { it.nameWithoutExtension }.toSet()
        val listed = licenseTexts(hasFfmpeg = true).map { it.raw }
        assertEquals("one row per text", listed.size, listed.toSet().size)
        val names = setOf("apache_2_0", "mit_checker_qual", "bsd_3_clause_protobuf", "mpl_2_0", "gpl_3_0", "ofl_roboto")
        assertEquals(names, shipped)
        names.forEach { assertTrue(it, raw(it).length() > 500) }
    }

    @Test fun `the components found in the release classpath audit are credited under their licences`() {
        val credited = licenses(hasFfmpeg = false).toMap()
        assertEquals(R.string.license_bsd3, credited[R.string.license_protobuf])
        assertEquals(R.string.license_mpl2, credited[R.string.license_psl])
        assertEquals(R.string.license_mit, credited[R.string.license_checker])
        assertEquals(R.string.license_apache, credited[R.string.license_annotations])
        assertEquals(R.string.license_apache, credited[R.string.license_flatbuffers])
        val text = strings()
        for (name in listOf("JSR 305", "JSpecify", "Error Prone", "J2ObjC", "JetBrains Java annotations")) {
            assertTrue(name, text.getValue("license_annotations").contains(name))
        }
        assertTrue(text.getValue("license_psl_source").contains("https://publicsuffix.org/list/"))
    }

    @Test fun `the Apache section 4(d) NOTICE of concurrent-futures-ktx is reproduced word for word`() {
        // META-INF/NOTICE.txt of androidx.concurrent:concurrent-futures-ktx:1.2.0, the attribution notice itself.
        assertEquals(listOf(R.string.license_notice_kotlinx_coroutines), apacheNotices)
        assertEquals(
            "kotlinx.coroutines library.\\nCopyright 2016-2024 JetBrains s.r.o and contributors",
            strings().getValue("license_notice_kotlinx_coroutines")
        )
    }

    /** The raw text of each English string, as written in strings.xml. */
    private fun strings(): Map<String, String> {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.isFile }
        val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""")
        return entry.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
