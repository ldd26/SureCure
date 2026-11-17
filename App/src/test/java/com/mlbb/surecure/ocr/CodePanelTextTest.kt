package com.mlbb.surecure.ocr

import com.mlbb.surecure.rules.PackDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code-panel text as ML Kit returns it (one string per recognized line), including common misreads. */
class CodePanelTextTest {

    private fun read(vararg lines: String) = CodePanelText.read(lines.toList())

    @Test
    fun cleanPanelGivesEveryField() {
        val r = read(
            "Bioflu",
            "Phenylephrine HCl + Chlorphenamine Maleate + Paracetamol",
            "Reg. No.: DR-XY37402",
            "LOT NO.: U066300",
            "MFG. DATE: MAR 2025",
            "EXP. DATE: MAR 2028",
            "4 807788 526762",
        )
        assertEquals(
            CodePanelReading(
                barcode = "4807788526762",
                lot = "U066300",
                regNo = "DR-XY37402",
                expiry = "MAR 2028",
                manufactured = "MAR 2025",
            ),
            r,
        )
        // The suggestions are what PackDate.parsePrinted reads, with the usual month-only rules.
        assertEquals(PackDate(2028, 3, 31), PackDate.parsePrinted(r.expiry, endOfMonth = true))
        assertEquals(PackDate(2025, 3, 1), PackDate.parsePrinted(r.manufactured, endOfMonth = false))
    }

    @Test
    fun emptyOrUnrelatedTextGivesNothing() {
        assertEquals(CodePanelReading(), read())
        assertEquals(CodePanelReading(), read("Biogesic", "Paracetamol 500 mg", "LOTION 60 mL", "Store below 30C"))
    }

    // ------------------------------------------------------------------ barcode

    @Test
    fun barcodeInPrintedGroupsOrMerged() {
        assertEquals("4807788526762", read("4 807788 526762").barcode)
        assertEquals("4807788526762", read("4807788526762").barcode)
        assertEquals("4807788526762", read("4807788 526762").barcode)
        assertEquals("4807788526762", read("4 807788526762").barcode)
    }

    @Test
    fun barcodeFirstDigitOnItsOwnLine() {
        assertEquals("4807788526762", read("4", "807788 526762").barcode)
    }

    @Test
    fun barcodeLookalikeLettersAreFixedWhenTheCheckDigitAgrees() {
        // O for 0, S for 5, I for 1, B for 8.
        assertEquals("4807788526762", read("48O7788S26762").barcode)
        assertEquals("4807788561688", read("4 8O7788 S6I688").barcode)
    }

    @Test
    fun barcodeWithAWrongDigitIsNotSuggested() {
        assertNull(read("4 807788 526763").barcode)
        assertNull(read("4 807788 52676").barcode) // 12 digits
    }

    @Test
    fun twoDifferentBarcodesSuggestNeither() {
        assertNull(read("4807788526762", "4807788561688").barcode)
        assertEquals("4807788526762", read("4807788526762", "4 807788 526762").barcode)
    }

    @Test
    fun labelledNumbersAreNeverReadAsBarcodes() {
        val r = read("LOT 4807788526762", "EXP 03/2028")
        assertNull(r.barcode)
        assertEquals("4807788526762", r.lot)
    }

    // ------------------------------------------------------------------ registration number

    @Test
    fun registrationNumbersInTheTableFormat() {
        assertEquals("DR-XY45182", read("DR-XY 45182").regNo)
        assertEquals("DR-XY45182", read("DR XY45182").regNo)
        assertEquals("DRP-13914", read("FDA Reg. No. DRP-13914").regNo)
        assertEquals("DRHR-1167", read("Reg No: DRHR 1167").regNo)
        assertEquals("DR-X7096", read("DR-X7096").regNo)
        assertEquals("DR-4184", read("DR-4184").regNo)
        assertEquals("HMR-157", read("HMR-157").regNo)
        assertEquals("HMRP-52", read("HMRP-52").regNo)
        assertEquals("FR-4000007831450", read("FR-4000007831450").regNo)
    }

    @Test
    fun registrationLookalikesInTheNumberAreFixed() {
        assertEquals("DR-XY12345", read("DR-XYI2345").regNo)
        assertEquals("DR-XY54021", read("Reg. No. DR-XYS4O21").regNo)
    }

    @Test
    fun registrationNotGuessed() {
        assertNull(read("DR-XY45182", "DRP-13914").regNo)
        assertNull(read("Dr. Reddy's Laboratories").regNo)
    }

    // ------------------------------------------------------------------ lot

    @Test
    fun lotAfterItsLabel() {
        assertEquals("23651568", read("LOT: 23651568").lot)
        assertEquals("2504A", read("B.N. 2504A").lot)
        assertEquals("B12345", read("Lot/Batch No.: B12345").lot)
        assertEquals("23651568", read("Batch No.", "23651568").lot)
    }

    @Test
    fun lotIsKeptAsReadAndFlaggedForLookalikes() {
        // O and 0 both occur in real lots, so a misread is shown for the user to check, not corrected.
        val lot = read("LOT NO. U0663OO").lot
        assertEquals("U0663OO", lot)
        assertTrue(CodePanelText.lotHasLookalikes(lot!!))
        assertFalse(CodePanelText.lotHasLookalikes("23651568"))
    }

    // ------------------------------------------------------------------ dates

    @Test
    fun expiryLookalikesAreFixedInNumbersOnly() {
        assertEquals("MAR 2028", read("EXP. MAR 2O28").expiry)
        assertEquals("05/2028", read("EXP 0S/2O28").expiry)
        assertEquals("OCT 2027", read("EXP OCT 2O27").expiry)
        assertEquals("03/2028", read("EXP: 03 2028").expiry)
        assertEquals("15 MAR 2028", read("Expiry Date: 15 MAR 2028").expiry)
    }

    @Test
    fun dateOnTheLineAfterItsLabel() {
        assertEquals("DEC 2027", read("Expiry Date:", "DEC 2027").expiry)
    }

    @Test
    fun bothDatesOnOneLine() {
        val r = read("MFG 03/2025 EXP 03/2028")
        assertEquals("03/2025", r.manufactured)
        assertEquals("03/2028", r.expiry)
    }

    @Test
    fun trailingTextAfterTheDateIsDropped() {
        assertEquals("MAR 2028", read("EXP MAR 2028 SRP 25.00").expiry)
    }

    @Test
    fun ambiguousDatesAreLeftForTheUser() {
        assertNull(read("MFG: 03/15/2025").manufactured)
        // Must not be cut down to "03 15" (March 2015).
        assertNull(read("EXP 03 15 2028").expiry)
    }
}
