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
        val values = labelledValues(clean)
        return CodePanelReading(
            barcode = barcode(clean),
            lot = lot(values[Field.LOT].orEmpty()),
            regNo = regNo(clean),
            expiry = date(values[Field.EXPIRY].orEmpty(), endOfMonth = true),
            manufactured = date(values[Field.MFG].orEmpty(), endOfMonth = false),
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
            // Stray marks printed or read beside the digits (">", "*", "-") are dropped.
            val t = tokens(line).map { tok -> tok.trim { !it.isLetterOrDigit() && it !in DIGIT_LOOKALIKES } }
                .filter { it.isNotEmpty() }
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

    private val AMBIGUOUS_REG_PREFIXES = setOf("DR-", "FR-")
    private val REG_LABEL = Regex("""(?<![A-Z0-9])REG(?:ISTRATION)?\b""")

    /**
     * "DR-XY 45182", "Reg. No. DRP-13914", "DR-XYI2345" -> "DR-XY45182", "DRP-13914", "DR-XY12345".
     * Written the way the FDA table stores it, because the rules engine looks it up exactly.
     */
    private fun regNo(lines: List<String>): String? {
        val found = linkedSetOf<String>()
        for (line in lines) {
            val upper = line.uppercase(Locale.ROOT)
            for (m in REG.findAll(upper)) {
                val index = (0 until REG_PREFIXES.size).first { m.groups[it + 2] != null }
                val raw = m.groupValues[REG_PREFIXES.size + 2]
                // Mostly real digits, so words such as "BOSS" or "ISIS" are never turned into numbers.
                if (!digitLike(raw)) continue
                val prefix = REG_PREFIXES[index].second
                // "DR" and "FR" alone are also ordinary text ("Dr. ...", "DR 50 mg"): they need the
                // printed hyphen ("DR-4184") or a registration label on the line.
                if (prefix in AMBIGUOUS_REG_PREFIXES && '-' !in m.value && !REG_LABEL.containsMatchIn(upper)) continue
                found += prefix + fixDigits(raw)
            }
        }
        return found.singleOrNull()
    }

    // ------------------------------------------------------------------ labels and their values

    // Labels allow common misreads: "L0T", "N0." (zero for O).
    private val LOT_LABEL = Regex(
        """(?<![A-Z0-9])(?:L[O0]T\s*(?:NUMBER|N[O0]\.?|#)?|BATCH\s*(?:NUMBER|N[O0]\.?|#)?|B\s*\.?\s*N\.?|BN)\s*[.:#]*\s*"""
    )
    private val EXPIRY_LABEL = Regex(
        """(?<![A-Z0-9])(?:EXP(?:IRY|IRATION)?\.?(?:\s*DATE)?|USE\s*BY)\s*[.:]*\s*"""
    )
    private val MFG_LABEL = Regex(
        """(?<![A-Z0-9])(?:MFG\.?(?:\s*DATE)?|MFD\.?|MANUFACTURED(?:\s*ON)?|MANUFACTURING\s*DATE|DATE\s*OF\s*MANUFACTURE)\s*[.:]*\s*"""
    )
    private val ANY_LABEL = Regex(
        listOf(EXPIRY_LABEL, MFG_LABEL, LOT_LABEL, REG_LABEL).joinToString("|") { "(?:${it.pattern})" }
    )

    private enum class Field { LOT, EXPIRY, MFG, REG }

    /** The registration label with its "No." ("Reg. No.:"), so a stacked label takes its own value line. */
    private val REG_FIELD_LABEL = Regex(
        """(?<![A-Z0-9])REG(?:ISTRATION)?\.?\s*(?:NUMBER|N[O0]\.?|#)?\s*[.:#]*\s*"""
    )

    private val FIELD_LABELS = listOf(
        Field.LOT to LOT_LABEL, Field.EXPIRY to EXPIRY_LABEL, Field.MFG to MFG_LABEL, Field.REG to REG_FIELD_LABEL,
    )

    private class LabelHit(val field: Field, val start: Int, val end: Int)

    /** Labels on a line, left to right, without overlaps. */
    private fun labelHits(line: String): List<LabelHit> {
        val all = FIELD_LABELS
            .flatMap { (field, re) -> re.findAll(line).map { LabelHit(field, it.range.first, it.range.last + 1) } }
            .sortedWith(compareBy<LabelHit>({ it.start }, { -it.end }))
        val out = mutableListOf<LabelHit>()
        for (h in all) if (out.isEmpty() || h.start >= out.last().end) out += h
        return out
    }

    /** Text between labels that joins them into one combined label: "LOT NO./EXP. DATE:". */
    private fun isJoiner(text: String): Boolean = text.trim().trim('/', '&', ',', '.', ':', '|').isBlank()

    private val COMBINED_VALUE_SPLIT = Regex("""\s+/\s+|\s*\|\s*|\s*;\s*""")

    /**
     * The text printed for each labelled field, in reading order. Handles:
     * - label and value on one line: "LOT NO.: U066300  EXP: MAR 2028";
     * - labels stacked above their values (one or several): "EXP. DATE" / "LOT NO." / "MAR 2028" / "U066300";
     * - combined labels: "Lot No./Exp. Date: 23651568 / MAR 2028".
     */
    private fun labelledValues(lines: List<String>): Map<Field, List<String>> {
        val values = mutableMapOf<Field, MutableList<String>>()
        fun add(field: Field, text: String) {
            if (text.isNotBlank()) values.getOrPut(field) { mutableListOf() } += text.trim()
        }
        // Labels printed without a value, waiting for the next unlabelled lines, in order.
        val pending = ArrayDeque<Field>()
        for (raw in lines) {
            val line = raw.uppercase(Locale.ROOT)
            val hits = labelHits(line)
            if (hits.isEmpty()) {
                pending.removeFirstOrNull()?.let { add(it, line) }
                continue
            }
            val texts = hits.mapIndexed { j, h -> line.substring(h.end, hits.getOrNull(j + 1)?.start ?: line.length) }
            var group = mutableListOf<Field>()
            for ((j, h) in hits.withIndex()) {
                group += h.field
                val text = texts[j]
                val last = j == hits.lastIndex
                if (!last && isJoiner(text)) continue
                when {
                    text.isBlank() || isJoiner(text) -> pending.addAll(group)
                    group.size == 1 -> add(group[0], text)
                    else -> {
                        val parts = text.trim().split(COMBINED_VALUE_SPLIT)
                        if (parts.size == group.size) {
                            group.zip(parts).forEach { (f, v) -> add(f, v) }
                        } else if (group.size == 2) {
                            // "LOT/EXP: 23651568 MAR 2028": the first word is the first field's value.
                            val t = tokens(text)
                            add(group[0], t.first())
                            add(group[1], t.drop(1).joinToString(" "))
                        }
                    }
                }
                group = mutableListOf()
            }
        }
        return values
    }

    // ------------------------------------------------------------------ lot

    private val LOT_VALUE = Regex("""^[A-Z0-9][A-Z0-9\-/]{2,19}$""")

    /** The code printed after a LOT / BATCH / B.N. label. Kept as read; two different lots suggest neither. */
    private fun lot(texts: List<String>): String? {
        val found = linkedSetOf<String>()
        for (text in texts) {
            val value = tokens(text).firstOrNull()?.trim('.', ',', ';', ':') ?: continue
            if (LOT_VALUE.matches(value) && value.any { it.isDigit() } && !hasLabel(value)) found += value
        }
        return found.singleOrNull()
    }

    // ------------------------------------------------------------------ dates

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** Digits OCR reads in place of letters, for month names only ("0CT" -> "OCT", "AU6" -> "AUG"). */
    private val LETTER_LOOKALIKES = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G')

    /** A month name, allowing digit misreads; null if [text] isn't one. */
    private fun monthName(text: String): String? {
        val fixed = text.map { LETTER_LOOKALIKES[it] ?: it }.joinToString("")
        return fixed.takeIf { it.length >= 3 && it.all(Char::isLetter) && it.take(3) in MONTHS }
    }

    /**
     * The expiry or manufacture date: every labelled value is cleaned and kept only if
     * PackDate.parsePrinted reads it. If the panel shows two different dates for the same label,
     * neither is suggested. A date parsePrinted rejects on purpose (the ambiguous "03/15/2028") is
     * left for the user to type.
     */
    private fun date(texts: List<String>, endOfMonth: Boolean): String? {
        val found = texts.mapNotNull { dateIn(it, endOfMonth) }
        return found.firstOrNull()?.takeIf { first -> found.all { it.second == first.second } }?.first
    }

    /** "MAR 2O28 SRP 25.00" -> "MAR 2028" with its parsed date, or null. */
    private fun dateIn(text: String, endOfMonth: Boolean): Pair<String, PackDate>? {
        val parts = tokens(text)
            .map { it.trim('.', ',', ';', ':') }
            .flatMap { tokens(expandCompactDate(it)) }
            .map(::cleanDateToken)
            .filter { it.isNotEmpty() }
        for (k in minOf(parts.size, 4) downTo 1) {
            // Don't cut a date short: "03 15 2028" must not become "03 15".
            val next = parts.getOrNull(k)
            if (next != null && next.first().isDigit()) continue
            val candidate = numericMonthYear(parts.take(k).joinToString(" "))
            PackDate.parsePrinted(candidate, endOfMonth)?.let { return candidate to it }
        }
        return null
    }

    /** "15MAR2O28" -> "15 MAR 2028", "0CT2027" -> "OCT 2027", "032028" -> "03/2028"; anything else unchanged. */
    private fun expandCompactDate(token: String): String {
        if (token.any { !it.isLetterOrDigit() }) return token
        Regex("""^(0[1-9]|1[0-2])(20\d{2})$""").matchEntire(token)?.let {
            return "${it.groupValues[1]}/${it.groupValues[2]}"
        }
        for (dayLen in 0..2) {
            if (token.length < dayLen + 5) break
            val day = token.substring(0, dayLen)
            val month = monthName(token.substring(dayLen, dayLen + 3)) ?: continue
            val year = token.substring(dayLen + 3)
            if ((year.length != 2 && year.length != 4) || !digitLike(year)) continue
            if (day.isNotEmpty() && !digitLike(day)) continue
            return listOf(fixDigits(day), month, fixDigits(year)).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return token
    }

    /**
     * Fix look-alikes in a date token: digits in the number parts ("0S/2O28" -> "05/2028") and
     * letters in month names ("0CT" -> "OCT").
     */
    private fun cleanDateToken(token: String): String =
        token.split(Regex("(?<=[./-])|(?=[./-])")).joinToString("") { part ->
            when {
                digitLike(part) -> fixDigits(part)
                part.any(Char::isDigit) -> monthName(part) ?: part
                else -> part
            }
        }

    /** "03 2028" -> "03/2028", which parsePrinted reads as a month and year. */
    private fun numericMonthYear(text: String): String =
        Regex("""^(\d{1,2})\s+(\d{4}|\d{2})$""").matchEntire(text)
            ?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: text
}
