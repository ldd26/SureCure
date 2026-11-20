package com.mlbb.surecure.rules

import java.util.Locale

/*
 * SureCure verification rules: Kotlin port of rules/surecure_rules.py (the specification).
 *
 * Turns the codes a user confirmed on a pack (barcode, lot, dates, registration number) and the
 * confirmed product into a red / amber / green verdict, using the FDA tables in assets/fda/.
 * Must give the same verdict and reasons as the Python verify() for every case; see
 * SureCureRulesTest (the 21 Python scenarios) and RulesParityTest (cases checked against Python).
 *
 * A green verdict means "no match with FDA counterfeit records", never "safe" or "authentic".
 * Pure Kotlin (no Android imports) so it runs in JVM unit tests.
 */

/** Lots shorter than this only match exactly; a look-alike slip on "015" or "260" would match far too many packs. */
const val MIN_FUZZY_LOT_LEN = 6

/** Verdict levels, least to most severe. [headline] is the wording the app shows. */
enum class VerdictLevel(val headline: String) {
    GREEN("No match with FDA counterfeit records"),
    AMBER("Couldn't fully verify"),
    RED("Matches an FDA counterfeit advisory"),
}

// --------------------------------------------------------------------------- helpers

/** True if [code] is 13 digits with a correct EAN-13 check digit. */
fun ean13Valid(code: String?): Boolean {
    if (code == null || code.length != 13 || !code.all { it in '0'..'9' }) return false
    val digits = code.map { it - '0' }
    val total = (0 until 12).sumOf { i -> digits[i] * (if (i % 2 == 1) 3 else 1) }
    return (10 - total % 10) % 10 == digits[12]
}

/** Upper-case and drop spaces, dashes, dots and slashes, which packs print inconsistently. */
fun normalizeLot(lot: String?): String =
    (lot ?: "").uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** Levenshtein distance (insertions, deletions, substitutions). */
fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] != b[j - 1]) 1 else 0
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}

/** Characters that are misread or mistyped for each other on a printed lot. */
private val LOOKALIKE_PAIRS = setOf("0O", "1I", "1L", "5S", "8B", "2Z", "6G").map { it.toSet() }.toSet()

/** True if [a] and [b] have the same length and differ in exactly one look-alike character. */
fun lookalikeSlip(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    val diffs = a.indices.filter { a[it] != b[it] }
    return diffs.size == 1 && setOf(a[diffs[0]], b[diffs[0]]) in LOOKALIKE_PAIRS
}

// --------------------------------------------------------------------------- data

data class Registration(
    val regNo: String,
    val product: String,
    /** Unknown in the scraped table; the date check is skipped when missing. */
    val validFrom: PackDate? = null,
    val validTo: PackDate? = null,
)

data class CounterfeitLot(
    val product: String,
    val lot: String,
    val expiry: String,
    val advisory: String,
)

data class FdaTables(
    /** Upper-case reg_no -> record. */
    val registrations: Map<String, Registration> = emptyMap(),
    /** EAN-13 -> product. */
    val barcodes: Map<String, String> = emptyMap(),
    val counterfeitLots: List<CounterfeitLot> = emptyList(),
) {
    /** Every product name the tables know (model labels plus other brands), sorted. */
    val knownProducts: List<String> by lazy {
        (registrations.values.map { it.product } + barcodes.values + counterfeitLots.map { it.product })
            .toSortedSet().toList()
    }

    companion object {
        /**
         * Same as Python FdaTables.from_csv, except barcodes are optional: the barcode table
         * hasn't been collected yet, and with none every scanned barcode is "not in the table".
         */
        fun fromCsv(registrationsCsv: String, counterfeitLotsCsv: String, barcodesCsv: String? = null): FdaTables {
            val registrations = LinkedHashMap<String, Registration>()
            for (r in Csv.readDicts(registrationsCsv)) {
                val vf = r["valid_from"].orEmpty().trim()
                val vt = r["valid_to"].orEmpty().trim()
                val regNo = r.getValue("reg_no").trim().uppercase(Locale.ROOT)
                registrations[regNo] = Registration(
                    regNo,
                    r.getValue("product").trim(),
                    if (vf.isNotEmpty()) PackDate.parseIso(vf) else null,
                    if (vt.isNotEmpty()) PackDate.parseIso(vt) else null,
                )
            }
            val barcodes = LinkedHashMap<String, String>()
            if (barcodesCsv != null) {
                for (r in Csv.readDicts(barcodesCsv)) {
                    barcodes[r.getValue("ean13").trim()] = r.getValue("product").trim()
                }
            }
            val lots = Csv.readDicts(counterfeitLotsCsv).map { r ->
                CounterfeitLot(
                    r.getValue("product").trim(),
                    r.getValue("lot").trim(),
                    r["expiry"].orEmpty().trim(),
                    r["advisory"].orEmpty().trim(),
                )
            }
            return FdaTables(registrations, barcodes, lots)
        }
    }
}

/** What the app knows once the user has confirmed the product and the printed codes. */
data class ScanInput(
    /** Product the user confirmed (a labels.json name); null if none was picked. */
    val stage1Product: String? = null,
    val barcode: String? = null,
    val lot: String? = null,
    val regNo: String? = null,
    val mfgDate: PackDate? = null,
    val expDate: PackDate? = null,
    val frontPhotoUri: String? = null,
)

data class Verdict(
    val level: VerdictLevel,
    /** Every rule that fired, most severe first. */
    val reasons: List<String>,
    val productChecked: String?,
    /** True if the lot exactly matched a counterfeit lot listed for this product (the red lot rule). Kotlin-only addition. */
    val counterfeitLotMatch: Boolean = false,
    val thumbnailUri: String? = null,
) {
    val message: String get() = level.headline
}

// --------------------------------------------------------------------------- rules

/** Python truthiness for optional strings: null and "" both count as "not given". */
private fun String?.given(): Boolean = !this.isNullOrEmpty()

fun verify(scan: ScanInput, tables: FdaTables, today: PackDate = PackDate.today()): Verdict {
    val hits = mutableListOf<Pair<VerdictLevel, String>>()
    fun hit(level: VerdictLevel, reason: String) {
        hits.add(level to reason)
    }

    // Which product do the codes point to?
    val reg = if (scan.regNo.given()) {
        tables.registrations[scan.regNo!!.trim().uppercase(Locale.ROOT)]
    } else null
    var bcProduct: String? = null
    if (scan.barcode.given()) {
        if (!ean13Valid(scan.barcode)) {
            hit(VerdictLevel.AMBER, "Barcode could not be read reliably (EAN-13 check digit fails).")
        } else {
            bcProduct = tables.barcodes[scan.barcode]
        }
    }

    val codeProduct = reg?.product ?: bcProduct
    val product = if (codeProduct.given()) codeProduct else scan.stage1Product  // the product all checks refer to

    // Red: codes disagree with each other
    if (reg != null && bcProduct.given() && reg.product != bcProduct) {
        hit(VerdictLevel.RED, "Barcode belongs to $bcProduct but registration ${reg.regNo} belongs to ${reg.product}.")
    }

    // Red: the codes belong to a different product than the one the user is holding
    if (scan.stage1Product.given() && reg != null && reg.product != scan.stage1Product) {
        hit(VerdictLevel.RED, "Registration ${reg.regNo} belongs to ${reg.product}, not ${scan.stage1Product}.")
    } else if (scan.stage1Product.given() && bcProduct.given() && bcProduct != scan.stage1Product) {
        hit(VerdictLevel.RED, "Barcode belongs to $bcProduct, not ${scan.stage1Product}.")
    }

    // Lot listed in an FDA counterfeit advisory.
    //   Red: exact match for this product.
    //   Amber: one look-alike character (O/0, I/1, ...) from a listed lot of this product. Lots are
    //          sequential, so any other one-character difference is usually a genuine neighbour.
    //   Amber: exact match for a different product (the confirmed product may be wrong).
    var lotMatch = false
    if (scan.lot.given()) {
        val lot = normalizeLot(scan.lot)
        for (c in tables.counterfeitLots) {
            val listed = normalizeLot(c.lot)
            val src = if (c.advisory.isNotEmpty()) "FDA Advisory ${c.advisory}" else "an FDA counterfeit advisory"
            if (product.given() && c.product == product) {
                if (lot == listed) {
                    hit(VerdictLevel.RED, "Lot ${scan.lot} is listed as counterfeit in $src.")
                    lotMatch = true
                } else if (minOf(lot.length, listed.length) >= MIN_FUZZY_LOT_LEN && lookalikeSlip(lot, listed)) {
                    hit(
                        VerdictLevel.AMBER,
                        "Lot ${scan.lot} differs from counterfeit lot ${c.lot} by one look-alike " +
                            "character ($src). Check the lot number again."
                    )
                }
            } else if (lot.isNotEmpty() && lot == listed) {
                hit(
                    VerdictLevel.AMBER,
                    "Lot ${scan.lot} is listed as counterfeit for ${c.product} in $src. " +
                        "Check that the product is right."
                )
            }
        }
    }

    // Amber: missing or unknown codes
    if (!scan.regNo.given()) {
        hit(VerdictLevel.AMBER, "No registration number entered.")
    } else if (reg == null) {
        hit(VerdictLevel.AMBER, "Registration number ${scan.regNo} is not in the FDA table.")
    }
    if (scan.barcode.given() && ean13Valid(scan.barcode) && bcProduct == null) {
        hit(VerdictLevel.AMBER, "Barcode ${scan.barcode} is not in the barcode table.")
    }
    if (!scan.lot.given()) {
        hit(VerdictLevel.AMBER, "No lot number entered, so the counterfeit lot check could not run.")
    }
    if (product == null) {
        hit(VerdictLevel.AMBER, "Product could not be identified from the photo or the codes.")
    }

    // Amber: dates
    val mfg = scan.mfgDate
    val exp = scan.expDate
    if (reg != null && mfg != null && reg.validFrom != null && reg.validTo != null &&
        !(reg.validFrom <= mfg && mfg <= reg.validTo)
    ) {
        hit(
            VerdictLevel.AMBER,
            "Manufactured $mfg while registration ${reg.regNo} was not valid (${reg.validFrom} to ${reg.validTo})."
        )
    }
    if (exp != null && exp < today) {
        hit(VerdictLevel.AMBER, "Expired on $exp.")
    }
    if (mfg != null && exp != null && exp <= mfg) {
        hit(VerdictLevel.AMBER, "Expiry date is not after the manufacturing date.")
    }

    if (hits.isEmpty()) return Verdict(VerdictLevel.GREEN, listOf("All checks passed."), product)
    val sorted = hits.sortedByDescending { it.first.ordinal }  // stable, like Python's sort
    return Verdict(sorted[0].first, sorted.map { it.second }, product, lotMatch, thumbnailUri = scan.frontPhotoUri)
}
