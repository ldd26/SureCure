package com.mlbb.surecure.ui.result

import com.mlbb.surecure.ml.ClassifierResult
import com.mlbb.surecure.ml.ProductScore
import com.mlbb.surecure.ml.Recognition
import com.mlbb.surecure.ocr.CodePanelReading
import com.mlbb.surecure.ocr.CodePanelText
import com.mlbb.surecure.rules.FdaTables
import com.mlbb.surecure.rules.PackDate
import com.mlbb.surecure.rules.VerdictLevel
import com.mlbb.surecure.rules.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScanFlowTest {

    private val labels = listOf(
        "Ascozin", "Bioflu", "Biogesic", "Bonamine", "Buscopan", "DayZinc", "Decolgen", "Flanax",
        "Imodium", "Lactezin", "Lagundi", "Midol", "Myra_E", "Neurogen_E", "Omeprazole", "Rinityn",
        "Rogin_E", "Sinecod", "Tempra", "Tuseran_Forte"
    )
    private val tables = FdaTables.fromCsv(
        File("src/main/assets/fda/registrations.csv").readText(),
        File("src/main/assets/fda/counterfeit_lots.csv").readText(),
    )
    private val today = PackDate(2026, 10, 24)

    private fun result(status: Recognition) = ClassifierResult(
        status,
        listOf(ProductScore("Biogesic", 0.6f), ProductScore("Bioflu", 0.2f), ProductScore("Tempra", 0.1f)),
        energy = -4.5f,
        logits = FloatArray(20),
        runName = "test",
    )

    @Test
    fun stage1FollowsFigure1() {
        assertEquals(ScanFlow.ProductStep.Confirm("Biogesic"), ScanFlow.productStep(result(Recognition.ACCEPTED)))
        assertEquals(
            ScanFlow.ProductStep.Pick(listOf("Biogesic", "Bioflu", "Tempra")),
            ScanFlow.productStep(result(Recognition.LOW_CONFIDENCE))
        )
        assertEquals(ScanFlow.ProductStep.ChooseFromList, ScanFlow.productStep(result(Recognition.NOT_RECOGNIZED)))
        assertEquals(ScanFlow.ProductStep.ChooseFromList, ScanFlow.productStep(null))
    }

    @Test
    fun manualSelectionOffersModelProductsThenOtherBrands() {
        val choices = ScanFlow.productChoices(labels, tables)
        assertEquals(labels, choices.take(20))
        assertTrue("Alaxan FR" in choices)
        assertEquals(choices.size, choices.toSet().size)
    }

    @Test
    fun displayNames() {
        assertEquals("Myra E", ScanFlow.displayName("Myra_E"))
        assertEquals("Tuseran Forte", ScanFlow.displayName("Tuseran_Forte"))
    }

    @Test
    fun blankFieldsAreNotEnteredAndDatesParse() {
        val parsed = ScanFlow.parse("Biogesic", ScanFlow.CodeEntry(lot = " 23651568 ", expiry = "MAR 2028"))
        parsed as ScanFlow.Parsed.Ok
        assertEquals("23651568", parsed.scan.lot)
        assertNull(parsed.scan.regNo)
        assertNull(parsed.scan.mfgDate)
        assertEquals(PackDate(2028, 3, 31), parsed.scan.expDate)
    }

    @Test
    fun unreadableDateIsAnErrorNotIgnored() {
        val parsed = ScanFlow.parse("Biogesic", ScanFlow.CodeEntry(expiry = "03/15/2028"))
        parsed as ScanFlow.Parsed.Errors
        assertEquals(setOf("expiry"), parsed.fields.keys)
    }

    @Test
    fun redHeadlineSaysAdvisoryOnlyForLotMatches() {
        val lot = verify(scanFor("Biogesic", ScanFlow.CodeEntry(lot = "23651568")), tables, today)
        assertEquals(VerdictLevel.RED, lot.level)
        assertEquals("Matches an FDA counterfeit advisory", ScanFlow.headline(lot))

        val mismatch = verify(
            scanFor("Biogesic", ScanFlow.CodeEntry(lot = "ZZ99999", regNo = "DR-XY34482")), tables, today
        )
        assertEquals(VerdictLevel.RED, mismatch.level)
        assertEquals(ScanFlow.RED_MISMATCH_HEADLINE, ScanFlow.headline(mismatch))
    }

    @Test
    fun amberAndGreenHeadlines() {
        val amber = verify(scanFor("Myra_E", ScanFlow.CodeEntry(lot = "ZZ99999")), tables, today)
        assertEquals("Couldn't fully verify", ScanFlow.headline(amber))
        val green = verify(scanFor("Biogesic", ScanFlow.CodeEntry(lot = "ZZ99999", regNo = "DR-153")), tables, today)
        assertEquals("No match with FDA counterfeit records", ScanFlow.headline(green))
    }

    private val tablesWithBarcodes = FdaTables.fromCsv(
        File("src/main/assets/fda/registrations.csv").readText(),
        File("src/main/assets/fda/counterfeit_lots.csv").readText(),
        File("src/main/assets/fda/barcodes.csv").readText(),
    )

    @Test
    fun typedBarcodeReachesTheVerdict() {
        // Printed in groups on the pack; spaces are removed, nothing else is changed.
        val scan = scanFor("Biogesic", ScanFlow.CodeEntry(barcode = "4 807788 526762", lot = "ZZ99999"))
        assertEquals("4807788526762", scan.barcode)
        val v = verify(scan, tablesWithBarcodes, today)
        assertEquals(VerdictLevel.RED, v.level)
        assertEquals("Barcode belongs to Bioflu, not Biogesic.", v.reasons[0])
        assertEquals(ScanFlow.RED_MISMATCH_HEADLINE, ScanFlow.headline(v))

        val own = verify(
            scanFor("Bioflu", ScanFlow.CodeEntry(barcode = "4807788526762", lot = "ZZ99999", regNo = "DR-XY34482")),
            tablesWithBarcodes, today
        )
        assertEquals(VerdictLevel.GREEN, own.level)

        // A digit short: passed on as typed, the engine says amber (check digit), not red.
        val short = scanFor("Biogesic", ScanFlow.CodeEntry(barcode = "480778852676", lot = "ZZ99999"))
        assertEquals("480778852676", short.barcode)
        assertEquals(VerdictLevel.AMBER, verify(short, tablesWithBarcodes, today).level)
        assertNull(scanFor("Biogesic", ScanFlow.CodeEntry(barcode = "   ")).barcode)
    }

    @Test
    fun barcodeFirstFollowsTheDecisionOrder() {
        // In the table: skip the photo model, the user confirms the product.
        assertEquals(
            ScanFlow.BarcodeFirst.Found("Bioflu", "4807788526762"),
            ScanFlow.barcodeFirst("4 807788 526762", tablesWithBarcodes)
        )
        assertEquals(
            ScanFlow.ProductStep.Confirm("Bioflu"),
            (ScanFlow.barcodeFirst("4807788526762", tablesWithBarcodes) as ScanFlow.BarcodeFirst.Found)
                .let { ScanFlow.ProductStep.Confirm(it.label) }
        )
        // Valid but not listed: photo model as usual; the barcode stays in the scan (amber later).
        assertEquals(
            ScanFlow.BarcodeFirst.NotInTable("4807788000019"),
            ScanFlow.barcodeFirst("4807788000019", tablesWithBarcodes)
        )
        // Typos: wrong check digit or too short.
        assertEquals(
            ScanFlow.BarcodeFirst.Unreadable("4807788526763"),
            ScanFlow.barcodeFirst("4807788526763", tablesWithBarcodes)
        )
        assertEquals(
            ScanFlow.BarcodeFirst.Unreadable("480778852676"),
            ScanFlow.barcodeFirst("480778852676", tablesWithBarcodes)
        )
        // Without a barcode table every valid code is "not in table", never a product.
        assertEquals(
            ScanFlow.BarcodeFirst.NotInTable("4807788526762"),
            ScanFlow.barcodeFirst("4807788526762", tables)
        )
    }

    @Test
    fun nearbyDrugstoresOnlyOnAmberAndRed() {
        val red = verify(scanFor("Biogesic", ScanFlow.CodeEntry(lot = "23651568")), tables, today)
        val amber = verify(scanFor("Myra_E", ScanFlow.CodeEntry(lot = "ZZ99999")), tables, today)
        val green = verify(scanFor("Biogesic", ScanFlow.CodeEntry(lot = "ZZ99999", regNo = "DR-153")), tables, today)
        assertTrue(ScanFlow.offersNearbyDrugstores(red))
        assertTrue(ScanFlow.offersNearbyDrugstores(amber))
        assertTrue(!ScanFlow.offersNearbyDrugstores(green))
    }

    @Test
    fun distanceFormatting() {
        assertEquals("430 m", ScanFlow.formatDistance(0.425))
        assertEquals("10 m", ScanFlow.formatDistance(0.001))
        assertEquals("1.6 km", ScanFlow.formatDistance(1.573))
        assertEquals("12 km", ScanFlow.formatDistance(12.34))
    }

    @Test
    fun photoReadingFillsOnlyBlankFields() {
        val typed = ScanFlow.CodeEntry(lot = "U066338")
        val reading = CodePanelReading(
            barcode = "4807788526762", lot = "U066300", regNo = "DR-XY37402", expiry = "MAR 2028",
        )
        val p = ScanFlow.prefill(typed, reading)
        // The typed lot stays; the manufacture date wasn't found, so it stays blank.
        assertEquals(
            ScanFlow.CodeEntry(
                barcode = "4807788526762", lot = "U066338", regNo = "DR-XY37402", expiry = "MAR 2028",
            ),
            p.entry,
        )
        assertEquals(
            setOf(ScanFlow.FIELD_BARCODE, ScanFlow.FIELD_REG_NO, ScanFlow.FIELD_EXPIRY),
            p.fromPhoto,
        )
        // Nothing found: nothing changes and nothing needs confirming.
        assertEquals(ScanFlow.Prefilled(typed, emptySet()), ScanFlow.prefill(typed, CodePanelReading()))
    }

    @Test
    fun photoReadingsMustBeConfirmedBeforeChecking() {
        assertTrue(ScanFlow.canCheck(emptySet(), userConfirmed = false))
        assertFalse(ScanFlow.canCheck(setOf(ScanFlow.FIELD_LOT), userConfirmed = false))
        assertTrue(ScanFlow.canCheck(setOf(ScanFlow.FIELD_LOT), userConfirmed = true))
    }

    @Test
    fun confirmedPhotoReadingBuildsTheSameScanInputAsTyping() {
        val reading = CodePanelText.read(
            listOf("Reg. No.: DR-XY37402", "LOT NO.: U066300", "EXP. MAR 2O28", "4 807788 526762")
        )
        val prefilled = ScanFlow.prefill(ScanFlow.CodeEntry(), reading).entry
        val typed = ScanFlow.CodeEntry(
            barcode = "4 807788 526762", lot = "U066300", regNo = "DR-XY37402", expiry = "MAR 2028",
        )
        assertEquals(scanFor("Bioflu", typed), scanFor("Bioflu", prefilled))
    }

    private fun scanFor(product: String, entry: ScanFlow.CodeEntry) =
        (ScanFlow.parse(product, entry) as ScanFlow.Parsed.Ok).scan
}
