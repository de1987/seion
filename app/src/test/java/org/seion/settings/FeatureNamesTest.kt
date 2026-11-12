package org.seion.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.R

/** Feature names are brands and stay untranslated: "Seion Infinite" in every language, and the
 * About brand line "Seion 清音" (only its meaning is translated). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FeatureNamesTest {
    private fun s(id: Int) = ApplicationProvider.getApplicationContext<android.app.Application>().getString(id)

    @Test fun `english names`() {
        assertEquals("Seion Infinite", s(R.string.background_ambient))
        assertEquals("Background motion", s(R.string.ambient_drift))
        assertEquals("The Seion Infinite background drifts slowly while playing", s(R.string.ambient_drift_summary))
        assertEquals("CrystalX+", s(R.string.crystalx_name))
        assertEquals("Seion RenovaPure", s(R.string.renovapure_name))
        assertEquals("Sound", s(R.string.sound))
        assertEquals("Seion 清音", s(R.string.about_brand))
        assertEquals("clear sound", s(R.string.about_brand_meaning))
        assertEquals("Seion", s(R.string.app_name))
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `portuguese keeps the brand`() {
        assertEquals("Seion Infinite", s(R.string.background_ambient))
        assertEquals("Fundo em movimento", s(R.string.ambient_drift))
        assertEquals("O plano de fundo Seion Infinite se move lentamente enquanto a música toca", s(R.string.ambient_drift_summary))
        assertEquals("CrystalX+", s(R.string.crystalx_name))
        assertEquals("Seion RenovaPure", s(R.string.renovapure_name))
        assertEquals("Som", s(R.string.sound))
        assertEquals("Seion 清音", s(R.string.about_brand))
        assertEquals("som claro", s(R.string.about_brand_meaning))
        assertEquals("Seion", s(R.string.app_name))
    }
}
