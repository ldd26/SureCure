package com.mlbb.surecure.rules

import com.mlbb.surecure.rules.VerdictLevel.AMBER
import com.mlbb.surecure.rules.VerdictLevel.GREEN
import com.mlbb.surecure.rules.VerdictLevel.RED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit port of rules/tests/test_rules.py: the same 21 tests, fixtures and assertions.
 * The tables are TEST FIXTURES with made-up registration numbers and barcodes, except the
 * Biogesic lot 23651568 (expiry MAR 2028), named in FDA Advisory No. 2025-0507.
 */
class SureCureRulesTest {

    private val today = PackDate(2026, 10, 20)

    /** Appends the correct EAN-13 check digit (for building fixtures). */
    private fun ean(first12: String): String {
        val total = first12.withIndex().sumOf { (i, c) -> (c - '0') * (if (i % 2 == 1) 3 else 1) }
        return first12 + ((10 - total % 10) % 10)
    }

    private val bioEan = ean("480000000001")
    private val tempraEan = ean("480000000002")
    private val unknownEan = ean("480000000099")
    private val badBioEan = bioEan.dropLast(1) + (((bioEan.last() - '0') + 1) % 10)

    /** Fresh tables per test, like the pytest fixture; extra rows for tests that append. */
    private fun tables(
        extraRegistrations: List<Registration> = emptyList(),
        extraLots: List<CounterfeitLot> = emptyList(),
    ) = FdaTables(
        registrations = listOf(
            Registration("DR-TEST-0001", "Biogesic", PackDate(2020, 1, 1), PackDate(2030, 1, 1)),
            Registration("DR-TEST-0002", "Tempra", PackDate(2020, 1, 1), PackDate(2030, 1, 1)),
            Registration("DR-TEST-0003", "Bioflu", PackDate(2015, 1, 1), PackDate(2019, 12, 31)),
        ).plus(extraRegistrations).associateBy { it.regNo },
        barcodes = mapOf(bioEan to "Biogesic", tempraEan to "Tempra"),
        counterfeitLots = listOf(CounterfeitLot("Biogesic", "23651568", "MAR 2028", "2025-0507")) + extraLots,
    )

    private fun genuine(
        stage1Product: String? = "Biogesic",
        barcode: String? = bioEan,
        lot: String? = "24A0912",
        regNo: String? = "DR-TEST-0001",
        mfgDate: PackDate? = PackDate(2025, 3, 1),
        expDate: PackDate? = PackDate(2028, 3, 1),
    ) = ScanInput(stage1Product, barcode, lot, regNo, mfgDate, expDate)

    // --- helpers

    @Test
    fun ean13CheckDigit() {
        assertTrue(ean13Valid(bioEan))
        assertFalse(ean13Valid(badBioEan))
        assertFalse(ean13Valid("12345"))
    }

    @Test
    fun lotNormalisationAndDistance() {
        assertEquals("23651568", normalizeLot(" 2365-1568 "))
        assertEquals(1, editDistance("23651568", "23651563"))
    }

    // --- scenarios

    @Test
    fun genuineIsGreen() {
        val v = verify(genuine(), tables(), today)
        assertTrue(v.level == GREEN && "No match" in v.message)
    }

    @Test
    fun listedCounterfeitLotIsRed() {
        val v = verify(genuine(lot = "23651568"), tables(), today)
        assertTrue(v.level == RED && "2025-0507" in v.reasons[0])
    }

    @Test
    fun lotPrintedWithSeparatorsStillMatches() {
        assertEquals(RED, verify(genuine(lot = "2365-1568"), tables(), today).level)
    }

    @Test
    fun lookalikeLotVariantIsAmber() {
        // I for 1: a misread of the listed lot, so the user is asked to check it again.
        val v = verify(genuine(lot = "2365I568"), tables(), today)
        assertTrue(v.level == AMBER && "one look-alike character" in v.reasons[0])
        assertFalse(v.counterfeitLotMatch)
    }

    @Test
    fun neighbouringLotIsNotFlagged() {
        // Lots are sequential: a genuine neighbour of a listed lot must not be called counterfeit.
        assertEquals(GREEN, verify(genuine(lot = "23651563"), tables(), today).level)
        assertEquals(GREEN, verify(genuine(lot = "2365156"), tables(), today).level)
    }

    @Test
    fun counterfeitLotOfOtherProductIsAmber() {
        // The lot is listed, but for Biogesic: the confirmed product may be wrong.
        val v = verify(
            genuine(stage1Product = "Tempra", barcode = tempraEan, regNo = "DR-TEST-0002", lot = "23651568"),
            tables(), today
        )
        assertTrue(v.level == AMBER && "listed as counterfeit for Biogesic" in v.reasons[0])
    }

    @Test
    fun listedLotWithUnidentifiedProductNamesTheProduct() {
        val v = verify(ScanInput(stage1Product = null, lot = "23651568"), tables(), today)
        assertTrue(v.level == AMBER && v.reasons.any { "listed as counterfeit for Biogesic" in it })
    }

    @Test
    fun registrationOfOtherProductIsRed() {
        assertEquals(RED, verify(genuine(regNo = "DR-TEST-0002"), tables(), today).level)
    }

    @Test
    fun barcodeOfOtherProductIsRed() {
        assertEquals(RED, verify(genuine(barcode = tempraEan), tables(), today).level)
    }

    @Test
    fun expiredIsAmber() {
        val v = verify(genuine(expDate = PackDate(2026, 1, 1)), tables(), today)
        assertTrue(v.level == AMBER && v.reasons.any { "Expired" in it })
    }

    @Test
    fun unknownRegistrationIsAmber() {
        assertEquals(AMBER, verify(genuine(regNo = "DR-NOT-IN-TABLE"), tables(), today).level)
    }

    @Test
    fun unknownBarcodeIsAmber() {
        assertEquals(AMBER, verify(genuine(barcode = unknownEan), tables(), today).level)
    }

    @Test
    fun badCheckDigitIsAmberNotRed() {
        assertEquals(AMBER, verify(genuine(barcode = badBioEan), tables(), today).level)
    }

    @Test
    fun registrationLapsedBeforeManufactureIsAmber() {
        val v = verify(
            ScanInput(
                stage1Product = "Bioflu", regNo = "DR-TEST-0003", lot = "X1",
                mfgDate = PackDate(2024, 5, 1), expDate = PackDate(2027, 5, 1)
            ),
            tables(), today
        )
        assertEquals(AMBER, v.level)
    }

    @Test
    fun missingLotIsAmber() {
        assertEquals(AMBER, verify(genuine(lot = null), tables(), today).level)
    }

    @Test
    fun unidentifiedProductWithoutCodesIsAmber() {
        val v = verify(ScanInput(lot = "23651568"), tables(), today)
        assertEquals(AMBER, v.level)
    }

    @Test
    fun codesIdentifyProductWhenPhotoGuardFired() {
        // Out-of-scope guard fired (no product picked) but the registration names Biogesic: the lot check still runs.
        val v = verify(genuine(stage1Product = null, lot = "23651568"), tables(), today)
        assertTrue(v.level == RED && v.productChecked == "Biogesic")
    }

    @Test
    fun redOutranksAmberAndAllReasonsListed() {
        val v = verify(genuine(lot = "23651568", expDate = PackDate(2026, 1, 1)), tables(), today)
        assertTrue(v.level == RED && v.reasons.size >= 2)
    }

    @Test
    fun shortLotMatchesOnlyExactly() {
        val t = tables(extraLots = listOf(CounterfeitLot("Flanax", "260", "05/2027", "")))
        assertEquals(RED, verify(ScanInput("Flanax", lot = "260"), t, today).level)
        val v = verify(ScanInput("Flanax", lot = "261", regNo = null), t, today)
        assertNotEquals(RED, v.level)
    }

    @Test
    fun registrationWithoutValidityDatesSkipsDateCheck() {
        val t = tables(extraRegistrations = listOf(Registration("DR-XY34482", "Bioflu")))
        val v = verify(ScanInput("Bioflu", regNo = "DR-XY34482", lot = "A1", mfgDate = PackDate(2010, 1, 1)), t, today)
        assertFalse(v.reasons.any { "not valid" in it })
    }

    @Test
    fun counterfeitLotWithoutAdvisoryNumberStillRed() {
        val t = tables(extraLots = listOf(CounterfeitLot("Bioflu", "U066300", "10/2023", "")))
        val v = verify(ScanInput("Bioflu", lot = "U066300"), t, today)
        assertTrue(v.level == RED && "an FDA counterfeit advisory" in v.reasons[0])
    }
}
