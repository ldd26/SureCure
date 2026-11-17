package com.mlbb.surecure.ocr

import com.mlbb.surecure.rules.PackDate
import com.mlbb.surecure.rules.ean13Valid
import java.util.Locale

/**
 * Codes found in the text of a code-panel photo. Each field is a suggestion for the user to check,
 * in the form the code-entry screen expects, or null if it wasn't found reliably. OCR never
 * decides a verdict: these only pre-fill fields the user then confirms or edits.
 */
data class CodePanelReading(
    val barcode: String? = null,
    val lot: String? = null,
    val regNo: String? = null,
    /** As printed and cleaned, e.g. "MAR 2028" or "03/2028"; always readable by PackDate.parsePrinted. */
    val expiry: String? = null,
    val manufactured: String? = null,
)

/**
 * Pulls the barcode, lot, FDA registration number and dates out of recognized text lines.
 * Pure Kotlin (no Android imports) so it runs in JVM unit tests; CodePanelReader feeds it.
 */
object CodePanelText {

    /** Letters OCR commonly reads in place of digits. Applied only where a digit is expected. */
    private val DIGIT_LOOKALIKES = mapOf(
        'O' to '0', 'o' to '0', 'I' to '1', 'l' to '1', '|' to '1', 'S' to '5', 's' to '5',
        'B' to '8', 'Z' to '2', 'z' to '2',
    )

    fun read(lines: List<String>): CodePanelReading {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        return CodePanelReading(
            barcode = barcode(clean),
            lot = lot(clean),
            regNo = regNo(clean),
            expiry = date(clean, EXPIRY_LABEL, endOfMonth = true),
            manufactured = date(clean, MFG_LABEL, endOfMonth = false),
        )
    }

    /**
     * True if a lot contains letters that OCR confuses with digits (O/0, I/1, S/5, B/8, Z/2).
     * Lots are not corrected automatically because both forms occur in real lots.
     */
    fun lotHasLookalikes(lot: String): Boolean = lot.any { it.uppercaseChar() in "OISBZ" }

    // ------------------------------------------------------------------ helpers

    /** A token that is meant to be digits: only digits and look-alikes, and mostly real digits. */
    private fun digitLike(token: String): Boolean =
        token.isNotEmpty() &&
            token.all { it.isDigit() || it in DIGIT_LOOKALIKES } &&
            token.count { it.isDigit() } * 2 >= token.length

    private fun fixDigits(token: String): String = token.map { DIGIT_LOOKALIKES[it] ?: it }.joinToString("")

    private fun tokens(line: String): List<String> = line.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun hasLabel(line: String): Boolean =
        ANY_LABEL.containsMatchIn(line.uppercase(Locale.ROOT))

    // ------------------------------------------------------------------ barcode

    /** Group lengths: "4807788526762", "4 807788 526762", "4807788 526762", "4 807788526762". */
    private val BARCODE_SHAPES = listOf(listOf(13), listOf(1, 6, 6), listOf(7, 6), listOf(1, 12))

    /**
     * EAN-13 printed under the bars, often in groups ("4 807788 526762") and sometimes with the
     * first digit on its own line. Only codes with a correct check digit count; if two different
     * valid codes are found, neither is suggested.
     */
    private fun barcode(lines: List<String>): String? {
        // Lines made only of digit groups, outside labelled lines (lots and dates are digits too).
        val digitLines = lines.map { line ->
            val t = tokens(line)
            if (!hasLabel(line) && t.isNotEmpty() && t.all(::digitLike)) t.map(::fixDigits) else null
        }
        val found = linkedSetOf<String>()
        fun tryGroups(groups: List<String>) {
            // Adjacent groups in the shapes an EAN-13 is printed in, or OCR merges of them.
            for (start in groups.indices) {
                for (shape in BARCODE_SHAPES) {
                    val run = groups.subList(start, minOf(groups.size, start + shape.size))
                    if (run.map { it.length } != shape) continue
                    val joined = run.joinToString("")
                    if (ean13Valid(joined)) found += joined
                }
            }
        }
        for (i in digitLines.indices) {
            val here = digitLines[i] ?: continue
            tryGroups(here)
            digitLines.getOrNull(i + 1)?.let { tryGroups(here + it) }
        }
        return found.singleOrNull()
    }

    // ------------------------------------------------------------------ registration number

    /** Prefixes as they appear in registrations.csv, longest first, with their canonical form. */
    private val REG_PREFIXES = listOf(
        """DR\s*[-.]?\s*X\s*Y""" to "DR-XY",
        """DRHR""" to "DRHR-",
        """DRP""" to "DRP-",
        """DR\s*[-.]?\s*X""" to "DR-X",
        """HMRP""" to "HMRP-",
        """HMR""" to "HMR-",
        """FR""" to "FR-",
        """DR""" to "DR-",
    )
    private val REG = Regex(
        "(?<![A-Z0-9])(" + REG_PREFIXES.joinToString("|") { "(${it.first})" } + ")" +
            """\s*[-.:]?\s*([0-9OISBZ]{2,13})(?![A-Z0-9])"""
    )

    /**
     * "DR-XY 45182", "Reg. No. DRP-13914", "DR-XYI2345" -> "DR-XY45182", "DRP-13914", "DR-XY12345".
     * Written the way the FDA table stores it, because the rules engine looks it up exactly.
     */
    private fun regNo(lines: List<String>): String? {
        val found = linkedSetOf<String>()
        for (line in lines) {
            for (m in REG.findAll(line.uppercase(Locale.ROOT))) {
                val index = (0 until REG_PREFIXES.size).first { m.groups[it + 2] != null }
                val digits = fixDigits(m.groupValues[REG_PREFIXES.size + 2])
                if (digits.count { it.isDigit() } < 2 || !digits.all { it.isDigit() }) continue
                found += REG_PREFIXES[index].second + digits
            }
        }
        return found.singleOrNull()
    }

    // ------------------------------------------------------------------ lot

    private val LOT_LABEL = Regex(
        """(?<![A-Z0-9])(?:LOT\s*(?:NUMBER|NO\.?|#)?|BATCH\s*(?:NUMBER|NO\.?|#)?|B\s*\.?\s*N\.?|BN)\s*[.:#]*\s*"""
    )
    private val LOT_VALUE = Regex("""^[A-Z0-9][A-Z0-9\-/]{2,19}$""")

    /** The code after a LOT / BATCH / B.N. label, on the same line or the next. Kept as read. */
    private fun lot(lines: List<String>): String? {
        for ((i, raw) in lines.withIndex()) {
            val line = raw.uppercase(Locale.ROOT)
            for (m in LOT_LABEL.findAll(line)) {
                val rest = line.substring(m.range.last + 1).trim()
                val sameLine = tokens(rest).firstOrNull()
                val candidate = if (sameLine != null) sameLine
                else lines.getOrNull(i + 1)?.let { tokens(it.uppercase(Locale.ROOT)).firstOrNull() }
                val value = candidate?.trimEnd('.', ',', ';', ':')
                if (value != null && LOT_VALUE.matches(value) && value.any { it.isDigit() } && !hasLabel(value)) {
                    return value
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ dates

    private val EXPIRY_LABEL = Regex(
        """(?<![A-Z0-9])(?:EXP(?:IRY|IRATION)?\.?(?:\s*DATE)?|USE\s*BY)\s*[.:]*\s*"""
    )
    private val MFG_LABEL = Regex(
        """(?<![A-Z0-9])(?:MFG\.?(?:\s*DATE)?|MFD\.?|MANUFACTURED(?:\s*ON)?|MANUFACTURING\s*DATE|DATE\s*OF\s*MANUFACTURE)\s*[.:]*\s*"""
    )
    private val ANY_LABEL = Regex(
        listOf(EXPIRY_LABEL, MFG_LABEL, LOT_LABEL).joinToString("|") { "(?:${it.pattern})" } +
            """|(?<![A-Z0-9])REG(?:ISTRATION)?\b"""
    )

    /**
     * The date after [label], cleaned ("EXP. MAR 2O28" -> "MAR 2028", "EXP 0S/2O28" -> "05/2028",
     * "EXP 03 2028" -> "03/2028") and kept only if PackDate.parsePrinted reads it. A date it rejects
     * on purpose, such as the ambiguous "03/15/2028", is left for the user to type.
     */
    private fun date(lines: List<String>, label: Regex, endOfMonth: Boolean): String? {
        for ((i, raw) in lines.withIndex()) {
            val line = raw.uppercase(Locale.ROOT)
            val m = label.find(line) ?: continue
            var rest = line.substring(m.range.last + 1)
            // Stop at the next label: "EXP 03/2028 MFG 03/2025" on one line.
            ANY_LABEL.find(rest)?.let { rest = rest.substring(0, it.range.first) }
            if (rest.isBlank()) rest = lines.getOrNull(i + 1)?.uppercase(Locale.ROOT) ?: ""
            val parts = tokens(rest).map(::cleanDateToken)
            for (k in minOf(parts.size, 4) downTo 1) {
                // Don't cut a date short: "03 15 2028" must not become "03 15".
                val next = parts.getOrNull(k)
                if (next != null && next.first().isDigit()) continue
                val candidate = numericMonthYear(parts.take(k).joinToString(" "))
                if (PackDate.parsePrinted(candidate, endOfMonth) != null) return candidate
            }
        }
        return null
    }

    /** Fix look-alikes in the number parts of a date token ("0S/2O28" -> "05/2028"); keep month names. */
    private fun cleanDateToken(token: String): String =
        token.trimEnd('.', ',', ';', ':').split(Regex("(?<=[./-])|(?=[./-])")).joinToString("") { part ->
            if (digitLike(part)) fixDigits(part) else part
        }

    /** "03 2028" -> "03/2028", which parsePrinted reads as a month and year. */
    private fun numericMonthYear(text: String): String =
        Regex("""^(\d{1,2})\s+(\d{4}|\d{2})$""").matchEntire(text)
            ?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: text
}
