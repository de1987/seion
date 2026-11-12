package org.seion.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.seion.ui.theme.Roboto
import org.seion.ui.theme.SeionType

/** The type scale: Roboto weights and sizes per text style. */
class TypographyTest {
    private val all =
        with(SeionType) { listOf(NpTitle, NpSecondary, NpTime, BarTitle, RowTitle, RowSub, RowMeta, Section, Body) }

    @Test fun `every style names a family`() {
        all.forEach {
            assertNotNull(it.fontFamily)
            assertEquals(Roboto, it.fontFamily)
        }
    }

    @Test fun `measured weights and sizes`() {
        assertEquals(FontWeight.Medium, SeionType.NpTime.fontWeight)
        assertEquals(14.8.sp, SeionType.NpTime.fontSize)
        assertEquals(FontWeight.Light, SeionType.NpTitle.fontWeight)
        assertEquals(FontWeight.Light, SeionType.NpSecondary.fontWeight)
        assertEquals(16.9.sp, SeionType.NpSecondary.fontSize)
        assertEquals(23.66.sp, SeionType.NpSecondary.lineHeight)
        assertEquals(27.sp, SeionType.BarTitle.lineHeight)
    }
}
