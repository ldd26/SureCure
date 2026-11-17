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
    fun ordinaryTextIsNotARegistrationNumber() {
        // Words made of look-alike letters must not become digits.
        assertNull(read("DR. BOSS PHARMA").regNo)
        assertNull(read("FR. ISIS").regNo)
        // "DR" / "FR" followed by a number need the hyphen or a registration label.
        assertNull(read("DR 50 mg tablet").regNo)
        assertNull(read("FR 12 tablets").regNo)
        assertEquals("DR-4184", read("DR-4184").regNo)
        assertEquals("DR-4184", read("Reg. No. DR 4184").regNo)
        assertEquals("FR-4000007831450", read("FDA Registration No.: FR 4000007831450").regNo)
        // Unambiguous prefixes still read without a hyphen.
        assertEquals("DRP-13914", read("DRP 13914").regNo)
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

    // ------------------------------------------------------------------ layouts and misreads

    @Test
    fun combinedLabelWithSlashSeparatedValues() {
        val r = read("Lot No./Exp. Date: 23651568 / MAR 2028")
        assertEquals("23651568", r.lot)
        assertEquals("MAR 2028", r.expiry)
    }

    @Test
    fun combinedLabelWithSpaceSeparatedValues() {
        val r = read("LOT/EXP: 23651568 MAR 2028")
        assertEquals("23651568", r.lot)
        assertEquals("MAR 2028", r.expiry)
    }

    @Test
    fun labelsStackedAboveTheirValues() {
        val r = read("EXP. DATE", "LOT NO.", "MAR 2028", "U066300")
        assertEquals("MAR 2028", r.expiry)
        assertEquals("U066300", r.lot)
        val s = read("LOT", "EXP", "MFG", "U066300", "MAR 2028", "MAR 2025")
        assertEquals("U066300", s.lot)
        assertEquals("MAR 2028", s.expiry)
        assertEquals("MAR 2025", s.manufactured)
    }

    @Test
    fun stackedRegistrationLabelKeepsItsOwnValue() {
        // The registration number must not be taken as the lot.
        val r = read("REG. NO.", "LOT NO.", "DR-XY45182", "U066300")
        assertEquals("DR-XY45182", r.regNo)
        assertEquals("U066300", r.lot)
    }

    @Test
    fun misreadLabelsAreRecognised() {
        assertEquals("23651568", read("LOT N0. 23651568").lot)
        assertEquals("23651568", read("L0T 23651568").lot)
    }

    @Test
    fun strayMarkBesideTheBarcodeIsIgnored() {
        assertEquals("4807788526762", read("4 807788 526762 >").barcode)
        assertEquals("4807788526762", read("4 807788 526762>").barcode)
    }

    @Test
    fun monthNamesWithDigitMisreads() {
        assertEquals("OCT 2027", read("EXP 0CT 2027").expiry)
        assertEquals("NOV 2027", read("EXP N0V 2027").expiry)
        assertEquals("AUG 2027", read("EXP AU6 2027").expiry)
    }

    @Test
    fun datesPrintedWithoutSpaces() {
        assertEquals("15 MAR 2028", read("EXP 15MAR2O28").expiry)
        assertEquals("MAR 2028", read("EXP MAR2028").expiry)
        assertEquals("03/2028", read("EXP032028").expiry)
        assertEquals("03/2025", read("MFG032025").manufactured)
    }

    @Test
    fun twoDifferentExpiryDatesSuggestNeither() {
        assertNull(read("EXP 12/2027", "EXP 01/2029").expiry)
        // The same date printed twice in two forms is fine.
        assertEquals("MAR 2028", read("EXP MAR 2028", "EXP 03/2028").expiry)
    }

    @Test
    fun twoDifferentLotsSuggestNeither() {
        assertNull(read("LOT 23651568", "LOT 23651569").lot)
        assertEquals("23651568", read("LOT 23651568", "Batch No. 23651568").lot)
    }

    @Test
    fun aLabelWithoutAValueDoesNotStealUnrelatedText() {
        // "Do not use after expiry date." is a label with nothing after it; the next line isn't a date.
        assertNull(read("Do not use after expiry date.", "Store below 30C").expiry)
        assertNull(read("MFG. BY: UNILAB, INC. 66 UNITED ST").manufactured)
    }

    // ------------------------------------------------------------------ bars

    @Test
    fun barcodeFromTheBarsWinsOverTheDigits() {
        // The printed digits were misread into another valid code; the bars decide.
        val s = CodePanelText.scan(listOf("4 807788 561688", "LOT 23651568"), bars = listOf("4807788526762"))
        assertEquals("4807788526762", s.reading.barcode)
        assertTrue(s.barcodeFromBars)
        assertEquals("23651568", s.reading.lot)
    }

    @Test
    fun barsThatFailTheCheckDigitAreIgnored() {
        val s = CodePanelText.scan(listOf("4 807788 526762"), bars = listOf("4807788526763"))
        assertEquals("4807788526762", s.reading.barcode)
        assertFalse(s.barcodeFromBars)
    }

    @Test
    fun twoDifferentBarcodesFromTheBarsSuggestNeither() {
        val s = CodePanelText.scan(listOf("4 807788 526762"), bars = listOf("4807788526762", "4807788561688"))
        assertNull(s.reading.barcode)
        assertFalse(s.barcodeFromBars)
        // The same code decoded twice is one code.
        assertTrue(CodePanelText.scan(emptyList(), listOf("4807788526762", "4807788526762")).barcodeFromBars)
    }

    @Test
    fun noBarsKeepsTheTextReadingAndTheRawLines() {
        val lines = listOf("LOT NO.: U066300", "EXP. MAR 2O28")
        val s = CodePanelText.scan(lines, bars = emptyList())
        assertEquals(CodePanelText.read(lines), s.reading)
        assertEquals(lines, s.lines)
    }
}
