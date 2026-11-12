package org.seion.library

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the library scan last finished (Settings › Rescan now: "Last scan today 09:12 · 5,052 songs"). A small
 * preferences file of its own rather than the settings DataStore: a write there re-emits the settings to everything
 * that watches them.
 */
class ScanStamp(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("seion_library", Context.MODE_PRIVATE)
    private val _lastScanAt = MutableStateFlow(prefs.getLong(KEY, 0L).takeIf { it > 0 })

    /** Epoch milliseconds of the last finished scan; null before the first. */
    val lastScanAt: StateFlow<Long?> = _lastScanAt.asStateFlow()

    fun mark(at: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY, at) }
        _lastScanAt.value = at
    }

    private companion object {
        const val KEY = "last_scan_at"
    }
}

/** Which day a time falls on, seen from now (the "today 09:12" / "yesterday 21:40" / "8 Nov 2026" choice). */
enum class ScanDay { TODAY, YESTERDAY, EARLIER }

object ScanTime {
    fun day(at: Long, now: Long, zone: ZoneId): ScanDay {
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (d) {
            today -> ScanDay.TODAY
            today.minusDays(1) -> ScanDay.YESTERDAY
            else -> ScanDay.EARLIER
        }
    }
}
