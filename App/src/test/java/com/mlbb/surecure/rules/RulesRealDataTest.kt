package com.mlbb.surecure.rules

import com.mlbb.surecure.rules.VerdictLevel.AMBER
import com.mlbb.surecure.rules.VerdictLevel.GREEN
import com.mlbb.surecure.rules.VerdictLevel.RED
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The engine on the tables the app actually bundles (src/main/assets/fda/). */
class RulesRealDataTest {

    private val today = PackDate(2026, 10, 24)
    private val unlistedLot = "ZZ99999"

    private val tables = FdaTables.fromCsv(
        File("src/main/assets/fda/registrations.csv").readText(),
        File("src/main/assets/fda/counterfeit_lots.csv").readText(),
    )

    private fun check(scan: ScanInput) = verify(scan, tables, today)

    @Test
    fun bundledTablesAreTheBuiltTables() {
        // assets/fda must be a copy of data/fda (built by scripts/build_fda_tables.py), not a hand-edited fork.
        for (name in listOf("registrations.csv", "counterfeit_lots.csv", "barcodes.csv")) {
            assertArrayEquals(
                "$name in assets differs from data/fda; copy it again after rebuilding",
                File("../data/fda/$name").readBytes(),
                File("src/main/assets/fda/$name").readBytes()
            )
        }
    }

    @Test
    fun tableSizes() {
        assertEquals(66, tables.registrations.size)
        assertEquals(327, tables.counterfeitLots.size)
        assertTrue(tables.barcodes.isEmpty())
        assertTrue("other brands are kept for the manual check", "Alaxan FR" in tables.knownProducts)
    }

    @Test
    fun biogesicListedLotIsRed() {
        assertEquals(RED, check(ScanInput("Biogesic", lot = "23651568")).level)
    }

    @Test
    fun biogesicLotWithOcrSlipIsAmber() {
        val v = check(ScanInput("Biogesic", lot = "2365I568"))
        assertEquals(AMBER, v.level)
        assertTrue(v.reasons.any { it.contains("differs from counterfeit lot 23651568 by one look-alike character") })
    }

    @Test
    fun biogesicWithOwnRegistrationAndUnlistedLotIsGreen() {
        val v = check(ScanInput("Biogesic", regNo = "DR-153", lot = unlistedLot))
        assertEquals(GREEN, v.level)
        assertEquals("No match with FDA counterfeit records", v.message)
    }

    @Test
    fun biofluListedLotIsRed() {
        assertEquals(RED, check(ScanInput("Bioflu", lot = "U066300")).level)
    }

    @Test
    fun flanaxShortLotOneOffIsNotRed() {
        // Listed: 260. Short lots only match exactly.
        assertNotEquals(RED, check(ScanInput("Flanax", lot = "261")).level)
        assertEquals(RED, check(ScanInput("Flanax", lot = "260")).level)
    }

    @Test
    fun biogesicWithBiofluRegistrationIsRed() {
        val v = check(ScanInput("Biogesic", regNo = "DR-XY34482", lot = unlistedLot))
        assertEquals(RED, v.level)
        assertEquals("Registration DR-XY34482 belongs to Bioflu, not Biogesic.", v.reasons[0])
    }

    @Test
    fun productWithoutRegistrationStillGetsLotCheck() {
        // Myra_E has no registration but 4 listed counterfeit lots.
        assertEquals(RED, check(ScanInput("Myra_E", lot = "0326 TO")).level)
        assertEquals(AMBER, check(ScanInput("Myra_E", regNo = null, lot = unlistedLot)).level)
    }

    @Test
    fun rinitynWithItsScrapedRegistrationIsGreen() {
        // Flagged for the team: the data person thought Rinityn had no registration,
        // but the scrape has DRP-6907, so the engine can give green. Left as the engine decides.
        assertEquals(GREEN, check(ScanInput("Rinityn", regNo = "DRP-6907", lot = unlistedLot)).level)
    }
}
