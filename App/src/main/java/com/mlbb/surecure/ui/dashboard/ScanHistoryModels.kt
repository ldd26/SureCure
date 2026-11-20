package com.mlbb.surecure.ui.dashboard

import androidx.annotation.DrawableRes
import com.mlbb.surecure.R
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Scan-history models for the dashboard. Entries are saved by ScanResultScreen.saveToHistory and read
 * by ScanHistoryRepository from filesDir/scan_history.json.
 */
enum class ScanSafety {
    /** Green: no match with FDA counterfeit records. */
    SAFE,
    /** Red: flagged, check with FDA. */
    UNSAFE,
    /** Amber: couldn't fully verify (amber was saved as UNSAFE before 2026-11-20). */
    NEEDS_REVIEW;

    companion object {
        /**
         * The saved level, tolerant of older entries: before 2026-11-20 amber was stored as UNSAFE with
         * "(amber)" in its details, so those read back as NEEDS_REVIEW. An unknown name reads as UNSAFE.
         */
        fun fromStored(name: String, details: String): ScanSafety {
            val saved = entries.firstOrNull { it.name == name } ?: return UNSAFE
            return if (saved == UNSAFE && "(amber)" in details) NEEDS_REVIEW else saved
        }
    }
}

data class ScanHistoryEntry(
    val id: String,
    val safety: ScanSafety,
    val scannedOn: Instant,
    val scanDetails: String = "",
    val frontPhotoUri: String? = null,
    val codePanelPhotoUri: String? = null
)

/**
 * Helper class that allows the segmenting of scan history previews into time periods.
 * Scan history is separated by major time periods, the same way photos are in gallery apps
 * (This Month, August 2026, July 2026, June 2026, ..., Over A Year Ago, etc.)
 */
data class ScanHistorySection(
    val timePeriod: String,
    val entries: List<ScanHistoryEntry>
)

/**
 * Groups scan history into calendar periods, newest first.
 *
 * Uses the supplied timezone to determine calendar dates.
 * Entries older than one year are grouped together.
 */
fun groupScanHistory(
    entries: List<ScanHistoryEntry>,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<ScanHistorySection> {
    val today: LocalDate = LocalDate.now(zoneId)
    val currentMonth: YearMonth = YearMonth.from(today)

    val sortedEntries: List<ScanHistoryEntry> = entries.sortedByDescending { it.scannedOn }

    val groupedEntries: Map<String, List<ScanHistoryEntry>> = sortedEntries.groupBy { entry ->
        val date = entry.scannedOn
            .atZone(zoneId)
            .toLocalDate()

        when {
            YearMonth.from(date) == currentMonth ->
                "This Month"

            date.isBefore(today.minusYears(1)) ->
                "Over A Year Ago"

            else ->
                YearMonth.from(date).format(
                    DateTimeFormatter.ofPattern(
                        "MMMM yyyy",
                        Locale.getDefault()
                    )
                )
        }
    }

    return groupedEntries.map { (period, periodEntries) ->
        ScanHistorySection(
            timePeriod = period,
            entries = periodEntries
        )
    }
}
