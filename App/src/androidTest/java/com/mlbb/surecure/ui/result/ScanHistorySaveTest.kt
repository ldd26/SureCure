package com.mlbb.surecure.ui.result

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mlbb.surecure.data.repository.ScanHistoryRepository
import com.mlbb.surecure.rules.FdaTablesLoader
import com.mlbb.surecure.rules.ScanInput
import com.mlbb.surecure.rules.VerdictLevel
import com.mlbb.surecure.rules.verify
import com.mlbb.surecure.ui.dashboard.ScanSafety
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The result screen's history save, end to end through the app's ScanHistoryRepository. */
@RunWith(AndroidJUnit4::class)
class ScanHistorySaveTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearHistory() {
        File(context.filesDir, "scan_history.json").delete()
    }

    @Test
    fun verdictsAreSavedOncePerScanAndReadBack() = runBlocking {
        val tables = FdaTablesLoader.load(context)
        val red = verify(ScanInput("Biogesic", lot = "23651568"), tables)
        val green = verify(ScanInput("Biogesic", regNo = "DR-153", lot = "ZZ99999"), tables)
        val amber = verify(ScanInput("Biogesic", lot = "ZZ99999"), tables)  // no registration number
        assertEquals(VerdictLevel.RED, red.level)
        assertEquals(VerdictLevel.GREEN, green.level)
        assertEquals(VerdictLevel.AMBER, amber.level)

        saveToHistory(context, "scan-1", red)
        saveToHistory(context, "scan-1", red)   // re-check of the same scan: replaced, not duplicated
        saveToHistory(context, "scan-2", green)
        saveToHistory(context, "scan-3", amber)

        val entries = ScanHistoryRepository(context).loadEntries(forceRefresh = true).associateBy { it.id }
        assertEquals(setOf("scan-1", "scan-2", "scan-3"), entries.keys)
        assertEquals(ScanSafety.UNSAFE, entries.getValue("scan-1").safety)
        assertEquals(ScanSafety.SAFE, entries.getValue("scan-2").safety)
        assertEquals(ScanSafety.NEEDS_REVIEW, entries.getValue("scan-3").safety)
        val details = entries.getValue("scan-1").scanDetails
        assertTrue(details, details.startsWith("Matches an FDA counterfeit advisory (red)"))
        assertTrue(details, "Lot 23651568 is listed as counterfeit" in details)
        assertTrue(entries.getValue("scan-2").scanDetails.startsWith("No match with FDA counterfeit records (green)"))
    }
}
