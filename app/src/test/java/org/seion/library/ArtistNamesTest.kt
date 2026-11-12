package org.seion.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** Splitting and formatting artist tags, so a long or many-artist tag is not simply cut with "…". */
class ArtistNamesTest {
    private fun split(raw: String?) = ArtistNames.split(raw)

    private val en: (String, String) -> String = { a, b -> "$a and $b" }

    // ---------------------------------------------------------------- one artist, kept whole

    @Test fun `band names with separators inside stay one artist`() {
        for (band in listOf(
            "Simon & Garfunkel",
            "Earth, Wind & Fire",
            "Crosby, Stills, Nash & Young",
            "AC/DC",
            "Florence + the Machine",
            "Tyler, the Creator",
            "Hall & Oates",
            "Mumford & Sons",
            "Of Monsters and Men",
            "Peter, Paul and Mary",
            "Emerson, Lake & Palmer",
            "Blood, Sweat & Tears",
            "Sly & the Family Stone",
            "Kool & the Gang",
            "Belle and Sebastian",
            "Zezé Di Camargo e Luciano",
            "Chitãozinho e Xororó",
            "Little Feat",
            "Unknown artist"
        )) {
            assertEquals(band, listOf(band), split(band))
        }
    }

    @Test fun `two names joined by a weak separator stay one artist`() {
        // Ambiguous with duos ("Hall & Oates"): conservative, shown as written.
        assertEquals(listOf("Ana Lima & Bruno Souza"), split("Ana Lima & Bruno Souza"))
        assertEquals(listOf("Ana Lima x Bruno Souza"), split("Ana Lima x Bruno Souza"))
        assertEquals(listOf("Ana Lima, Bruno Souza"), split("Ana Lima, Bruno Souza"))
    }

    @Test fun `a bare slash splits only between multi-word names`() {
        assertEquals(listOf("AC/DC"), split("AC/DC"))
        assertEquals(listOf("AC/DC Tribute Band"), split("AC/DC Tribute Band"))
        assertEquals(listOf("Love/Hate"), split("Love/Hate"))
        assertEquals(listOf("Artist A", "Artist B"), split("Artist A/Artist B"))
        assertEquals(listOf("Ana Lima", "Bruno"), split("Ana Lima/Bruno"))
    }

    // ---------------------------------------------------------------- strong separators always split

    @Test fun `featuring splits`() {
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk feat. Pharrell Williams"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk ft. Pharrell Williams"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk Feat. Pharrell Williams"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk featuring Pharrell Williams"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk feat Pharrell Williams"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk (feat. Pharrell Williams)"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk [ft. Pharrell Williams]"))
        assertEquals(listOf("Little Feat", "Ana Lima"), split("Little Feat feat. Ana Lima"))
    }

    @Test fun `semicolons, spaced slashes and multi-value nulls split`() {
        assertEquals(listOf("A", "B", "C", "D"), split("A; B; C; D"))
        assertEquals(listOf("A", "B", "C", "D"), split("A;B;C;D"))
        assertEquals(listOf("Artist A", "Artist B"), split("Artist A / Artist B"))
        assertEquals(listOf("Ana Lima", "Bruno Souza"), split("Ana Lima\u0000Bruno Souza"))
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias", "Davi Rocha"), split("Ana Lima; Bruno Souza; Carla Dias; Davi Rocha"))
    }

    @Test fun `a band inside a strong list stays whole`() {
        assertEquals(listOf("Simon & Garfunkel", "Ana Lima"), split("Simon & Garfunkel feat. Ana Lima"))
        assertEquals(listOf("Crosby, Stills, Nash & Young", "Ana Lima"), split("Crosby, Stills, Nash & Young; Ana Lima"))
        assertEquals(listOf("Earth, Wind & Fire", "AC/DC"), split("Earth, Wind & Fire / AC/DC"))
    }

    // ---------------------------------------------------------------- weak separators: three or more names

    @Test fun `three or more names with a multi-word one split on commas, ampersands and and`() {
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima, Bruno Souza & Carla Dias"))
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima, Bruno Souza and Carla Dias"))
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima e Bruno Souza e Carla Dias"))
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima x Bruno Souza x Carla Dias"))
        assertEquals(listOf("Ana Lima", "Bruno", "Carla"), split("Ana Lima, Bruno, Carla"))
    }

    @Test fun `a lower-case word after a comma belongs to the name before it`() {
        assertEquals(
            listOf("Tyler, the Creator", "Ana Lima", "Bruno Souza"),
            split("Tyler, the Creator, Ana Lima & Bruno Souza")
        )
    }

    @Test fun `the guest list after featuring follows the same weak rule`() {
        assertEquals(
            listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers", "Ana Lima"),
            split("Daft Punk feat. Pharrell Williams, Nile Rodgers & Ana Lima")
        )
        // Two guests: kept as written (could be a duo).
        assertEquals(listOf("Daft Punk", "Pharrell Williams & Nile Rodgers"), split("Daft Punk feat. Pharrell Williams & Nile Rodgers"))
    }

    // ---------------------------------------------------------------- clean-up

    @Test fun `empty, blank and unknown give no artist`() {
        assertEquals(emptyList<String>(), split(null))
        assertEquals(emptyList<String>(), split(""))
        assertEquals(emptyList<String>(), split("   "))
        assertEquals(emptyList<String>(), split(" ; ; "))
        assertEquals(emptyList<String>(), split("<unknown>"))
    }

    @Test fun `names are trimmed, de-duplicated ignoring case, and keep their order`() {
        assertEquals(listOf("A", "B", "C"), split("  A ;B;  a ; C ;; b "))
        assertEquals(listOf("Ana Lima", "Bruno Souza"), split("Ana Lima feat. Bruno Souza; ana lima"))
    }

    @Test fun `ten artists`() {
        val names = (1..10).map { "Artist $it" }
        assertEquals(names, split(names.joinToString("; ")))
    }

    // ---------------------------------------------------------------- edge cases

    @Test fun `pt-BR duos stay whole inside a list`() {
        assertEquals(listOf("Marília Mendonça", "Maiara e Maraisa"), split("Marília Mendonça, Maiara e Maraisa"))
        assertEquals(listOf("Matheus e Kauan", "Zé Neto e Cristiano"), split("Matheus e Kauan, Zé Neto e Cristiano"))
        assertEquals(listOf("Henrique e Juliano", "Marília Mendonça"), split("Henrique e Juliano, Marília Mendonça"))
        assertEquals(listOf("Jorge e Mateus", "Henrique e Juliano"), split("Jorge e Mateus, Henrique e Juliano"))
        assertEquals(listOf("Simon & Garfunkel", "Hall & Oates"), split("Simon & Garfunkel, Hall & Oates"))
        assertEquals(listOf("Of Monsters and Men", "Mumford & Sons"), split("Of Monsters and Men, Mumford & Sons"))
        // Two multi-word names around a joiner are still two people.
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima, Bruno Souza e Carla Dias"))
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima & Bruno Souza & Carla Dias"))
    }

    @Test fun `X and The Y after a joiner is one band`() {
        assertEquals(listOf("Nick Cave & The Bad Seeds"), split("Nick Cave & The Bad Seeds"))
        assertEquals(listOf("Bob Dylan, Tom Petty & the Heartbreakers"), split("Bob Dylan, Tom Petty & the Heartbreakers"))
    }

    @Test fun `only a function word glues a lower-case piece to the name before it`() {
        assertEquals(listOf("Ana Lima", "dj khaled", "Bruno Souza"), split("Ana Lima, dj khaled, Bruno Souza"))
        assertEquals(listOf("Ana Lima", "émilie simon", "Bruno Souza"), split("Ana Lima, émilie simon, Bruno Souza"))
        assertEquals(listOf("Ana Lima", "iamamiwhoami", "Bruno Souza"), split("Ana Lima, iamamiwhoami, Bruno Souza"))
        assertEquals(listOf("Tyler, the Creator"), split("Tyler, the Creator"))
        assertEquals(listOf("Sly & the Family Stone"), split("Sly & the Family Stone"))
    }

    @Test(timeout = 5_000)
    fun `long runs of whitespace are linear`() {
        val start = System.nanoTime()
        assertEquals(listOf("Ana Lima", "Bruno Souza"), split("Ana Lima" + " ".repeat(20_000) + "feat. Bruno Souza"))
        assertEquals(emptyList<String>(), split(" ".repeat(20_000)))
        // No marker after the run: a backtracking pattern would be cubic here (800 spaces would take 1.7 s).
        assertEquals(listOf("Ana Lima"), split("Ana" + " ".repeat(20_000) + "Lima"))
        assertEquals(listOf("Ana", "Bruno"), split("Ana (" + " ".repeat(20_000) + "feat. Bruno)"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assert(ms < 500) { "took $ms ms" }
    }

    @Test fun `unusual spaces count as spaces`() {
        assertEquals(listOf("Ana Lima", "Bruno Souza"), split("Ana Lima\u00A0feat.\u00A0Bruno Souza"))
        assertEquals(
            listOf("Ana Lima", "Bruno Souza", "Carla Dias"),
            split("Ana\u00A0Lima,\u00A0Bruno\u2009Souza,\u3000Carla\tDias")
        )
        assertEquals(listOf("Ana Lima", "Bruno Souza"), split("Ana  Lima; Bruno\nSouza; ANA LIMA"))
    }

    @Test fun `a bracketed featuring group in the middle is its own name`() {
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima (feat. Bruno Souza) & Carla Dias"))
        assertEquals(listOf("Daft Punk", "Pharrell"), split("Daft Punk ( feat. Pharrell )"))
        assertEquals(listOf("Daft Punk", "Pharrell (Live)"), split("Daft Punk (feat. Pharrell (Live))"))
        assertEquals(listOf("Ana Lima (Live)", "Bruno"), split("Ana Lima (Live) feat. Bruno"))
    }

    @Test fun `trailing and leading separators are dropped`() {
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima, Bruno Souza, Carla Dias,"))
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split("Ana Lima, Bruno Souza, Carla Dias &"))
        assertEquals(listOf("Ana Lima", "Bruno Souza", "Carla Dias"), split(", Ana Lima, Bruno Souza, Carla Dias"))
        assertEquals(emptyList<String>(), split("&"))
    }

    @Test fun `capitalised Feat and Ft before a name, and w slash, split`() {
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), split("Daft Punk Feat Pharrell Williams"))
        assertEquals(listOf("Jay-Z", "Kanye"), split("Jay-Z Ft Kanye"))
        assertEquals(listOf("Ana Lima", "Bruno"), split("Ana Lima w/ Bruno"))
        assertEquals(listOf("Little Feat"), split("Little Feat"))
        assertEquals(listOf("feat. Pharrell"), split("feat. Pharrell"))
    }

    @Test fun `a full-width semicolon splits`() {
        assertEquals(listOf("宇多田ヒカル", "椎名林檎"), split("宇多田ヒカル；椎名林檎"))
    }

    // ---------------------------------------------------------------- display

    @Test fun `the shown line joins everyone with commas, without a counter`() {
        assertEquals("", ArtistNames.display(emptyList()))
        assertEquals("A", ArtistNames.display(listOf("A")))
        assertEquals(
            "Ana Lima, Bruno Souza, Carla Dias, Davi Rocha",
            ArtistNames.display(split("Ana Lima; Bruno Souza; Carla Dias; Davi Rocha"))
        )
        // Bands and duos stay whole inside the list.
        assertEquals("Marília Mendonça, Maiara e Maraisa", ArtistNames.display(split("Marília Mendonça feat. Maiara e Maraisa")))
        assertEquals((1..10).joinToString(", ") { "Artist $it" }, ArtistNames.display((1..10).map { "Artist $it" }))
    }

    @Test fun `the spoken list names everyone`() {
        assertEquals("", ArtistNames.spoken(emptyList(), en))
        assertEquals("A", ArtistNames.spoken(listOf("A"), en))
        assertEquals("A and B", ArtistNames.spoken(listOf("A", "B"), en))
        assertEquals("Artist 1, Artist 2, Artist 3 and Artist 4", ArtistNames.spoken((1..4).map { "Artist $it" }, en))
        assertEquals("A, B e C", ArtistNames.spoken(listOf("A", "B", "C")) { a, b -> "$a e $b" })
    }
}
