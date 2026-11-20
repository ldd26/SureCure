package com.mlbb.surecure.ui.result

import com.mlbb.surecure.ml.ClassifierResult
import com.mlbb.surecure.ml.Recognition
import com.mlbb.surecure.ocr.CodePanelReading
import com.mlbb.surecure.rules.FdaTables
import com.mlbb.surecure.rules.PackDate
import com.mlbb.surecure.rules.ScanInput
import com.mlbb.surecure.rules.Verdict
import com.mlbb.surecure.rules.VerdictLevel
import com.mlbb.surecure.rules.ean13Valid
import com.mlbb.surecure.rules.productForBarcode
import com.mlbb.surecure.ui.dashboard.ScanSafety

/**
 * Logic behind the scan-result flow (Figure 1, stages 1 to 3), kept free of Compose so it can
 * be unit tested. The screen is ScanResultScreen.kt.
 */
object ScanFlow {

    /** Stage 1: what to ask the user after the photo model runs. */
    sealed interface ProductStep {
        /** In scope and confident: show the product with "Yes, that's it" and "Not this?". */
        data class Confirm(val label: String) : ProductStep

        /** In scope but not confident: "Which one is it?" with up to three choices + "None of these". */
        data class Pick(val labels: List<String>) : ProductStep

        /** Not recognized (or the model failed): manual selection from every known product. */
        data object ChooseFromList : ProductStep
    }

    /** Result of the barcode-first check (decision order step 1 in meta.json). */
    sealed interface BarcodeFirst {
        /** In the barcode table: confirm this product and skip the photo model. */
        data class Found(val label: String, val barcode: String) : BarcodeFirst

        /** A valid EAN-13 that isn't in the table yet: use the photo model; the verdict will say amber. */
        data class NotInTable(val barcode: String) : BarcodeFirst

        /** Not 13 digits or the check digit fails (likely a typo): ask again or use the photo. */
        data class Unreadable(val barcode: String) : BarcodeFirst
    }

    /** Step 1: a typed barcode (spaces removed) looked up in the barcode table. */
    fun barcodeFirst(typed: String, tables: FdaTables): BarcodeFirst {
        val code = typed.filterNot { it.isWhitespace() }
        if (!ean13Valid(code)) return BarcodeFirst.Unreadable(code)
        return tables.productForBarcode(code)
            ?.let { BarcodeFirst.Found(it, code) }
            ?: BarcodeFirst.NotInTable(code)
    }

    fun productStep(result: ClassifierResult?): ProductStep = when (result?.status) {
        Recognition.ACCEPTED -> ProductStep.Confirm(result.top1.label)
        Recognition.LOW_CONFIDENCE -> ProductStep.Pick(result.top3.map { it.label })
        Recognition.NOT_RECOGNIZED, null -> ProductStep.ChooseFromList
    }

    /**
     * Products for manual selection: the photo model's products first (labels.json order), then
     * every other product in the FDA tables, so the counterfeit lot check also runs for brands
     * the model doesn't know (Alaxan FR, Neozep Forte, ...).
     */
    fun productChoices(modelLabels: List<String>, tables: FdaTables): List<String> =
        modelLabels + tables.knownProducts.filter { it !in modelLabels }

    /** "Myra_E" -> "Myra E", "Tuseran_Forte" -> "Tuseran Forte". The label itself is what the engine uses. */
    fun displayName(label: String): String = label.replace('_', ' ')

    /** Stage 2 input as typed by the user, before parsing. */
    data class CodeEntry(
        /** The 13 digits under the barcode, as printed. */
        val barcode: String = "",
        val lot: String = "",
        val regNo: String = "",
        val expiry: String = "",
        val manufactured: String = "",
    )

    /** Field keys, shared by code-entry errors and fields pre-filled from the photo. */
    const val FIELD_BARCODE = "barcode"
    const val FIELD_LOT = "lot"
    const val FIELD_REG_NO = "regNo"
    const val FIELD_EXPIRY = "expiry"
    const val FIELD_MANUFACTURED = "manufactured"

    /** [entry] with OCR suggestions filled in, and the keys of the fields that came from the photo. */
    data class Prefilled(val entry: CodeEntry, val fromPhoto: Set<String>)

    /**
     * Fills only blank fields, so nothing the user typed is overwritten. The user must still check
     * every pre-filled field ([canCheck]); OCR never decides a verdict.
     */
    fun prefill(entry: CodeEntry, reading: CodePanelReading): Prefilled {
        val filled = mutableSetOf<String>()
        fun pick(key: String, current: String, suggested: String?): String =
            if (current.isBlank() && !suggested.isNullOrBlank()) suggested.also { filled += key } else current
        val next = CodeEntry(
            barcode = pick(FIELD_BARCODE, entry.barcode, reading.barcode),
            lot = pick(FIELD_LOT, entry.lot, reading.lot),
            regNo = pick(FIELD_REG_NO, entry.regNo, reading.regNo),
            expiry = pick(FIELD_EXPIRY, entry.expiry, reading.expiry),
            manufactured = pick(FIELD_MANUFACTURED, entry.manufactured, reading.manufactured),
        )
        return Prefilled(next, filled)
    }

    /** "Check against FDA records" needs the user to confirm any field still holding a photo reading. */
    fun canCheck(fromPhoto: Set<String>, userConfirmed: Boolean): Boolean = fromPhoto.isEmpty() || userConfirmed

    sealed interface Parsed {
        data class Ok(val scan: ScanInput) : Parsed

        /** Field name -> message, for dates that were typed but couldn't be read. */
        data class Errors(val fields: Map<String, String>) : Parsed
    }

    /** Builds the engine input. Blank fields count as "not entered"; unreadable dates are errors. */
    fun parse(product: String?, entry: CodeEntry): Parsed {
        val errors = mutableMapOf<String, String>()
        val exp = entry.expiry.trim().takeIf { it.isNotEmpty() }?.let {
            PackDate.parsePrinted(it, endOfMonth = true).also { d ->
                if (d == null) errors["expiry"] = DATE_HELP
            }
        }
        val mfg = entry.manufactured.trim().takeIf { it.isNotEmpty() }?.let {
            PackDate.parsePrinted(it, endOfMonth = false).also { d ->
                if (d == null) errors["manufactured"] = DATE_HELP
            }
        }
        if (errors.isNotEmpty()) return Parsed.Errors(errors)
        return Parsed.Ok(
            ScanInput(
                stage1Product = product,
                // Packs print the digits in groups ("4 807788 526762"); the engine expects 13 digits.
                // Anything else is passed on as typed, so a wrong digit count gets the engine's
                // amber "could not be read reliably", never a guess.
                barcode = entry.barcode.filterNot { it.isWhitespace() }.takeIf { it.isNotEmpty() },
                lot = entry.lot.trim().takeIf { it.isNotEmpty() },
                regNo = entry.regNo.trim().takeIf { it.isNotEmpty() },
                mfgDate = mfg,
                expDate = exp,
            )
        )
    }

    const val DATE_HELP = "Couldn't read this date. Type it as printed, e.g. MAR 2028 or 03/2028."

    /**
     * Stage 3 headline. Red has two causes: a lot listed in an FDA counterfeit advisory, or codes
     * that belong to a different product. Only the first may say it matches an advisory.
     */
    fun headline(verdict: Verdict, expiry: ExpiryStatus? = null): String = when {
        verdict.level == VerdictLevel.RED && !verdict.counterfeitLotMatch -> RED_MISMATCH_HEADLINE
        verdict.level == VerdictLevel.RED -> verdict.level.headline
        // Expired is amber in the rules engine; the screen says it plainly instead of "Couldn't fully verify".
        expiry is ExpiryStatus.Expired -> EXPIRED_HEADLINE
        else -> verdict.level.headline
    }

    const val RED_MISMATCH_HEADLINE = "Codes belong to a different product"
    const val EXPIRED_HEADLINE = "Expired: do not take this medicine"

    // ------------------------------------------------------------------ expiry

    /**
     * How the pack's expiry date compares with today. Shown on the code step as soon as the date is
     * known and with the verdict. "Expired" uses the rules engine's test (expiry before today), so
     * the two always agree; the engine itself is unchanged and still gives amber "Expired on ...".
     */
    sealed interface ExpiryStatus {
        val on: PackDate

        data class Expired(override val on: PackDate) : ExpiryStatus
        data class ExpiresSoon(override val on: PackDate, val daysLeft: Int) : ExpiryStatus
        data class Valid(override val on: PackDate) : ExpiryStatus
    }

    /** "Expires soon" when this many days or fewer are left. */
    const val EXPIRES_SOON_DAYS = 90

    fun expiryStatus(expiry: PackDate?, today: PackDate): ExpiryStatus? {
        if (expiry == null) return null
        val daysLeft = (epochDay(expiry) - epochDay(today)).toInt()
        return when {
            expiry < today -> ExpiryStatus.Expired(expiry)
            daysLeft <= EXPIRES_SOON_DAYS -> ExpiryStatus.ExpiresSoon(expiry, daysLeft)
            else -> ExpiryStatus.Valid(expiry)
        }
    }

    /** Status of the expiry field as typed or read from the photo; null while blank or unreadable. */
    fun expiryStatus(printed: String, today: PackDate): ExpiryStatus? =
        expiryStatus(PackDate.parsePrinted(printed.trim().takeIf { it.isNotEmpty() }, endOfMonth = true), today)

    /** One line for the user, e.g. "Expired on 31 Mar 2026." */
    fun expiryNote(status: ExpiryStatus): String = when (status) {
        is ExpiryStatus.Expired -> "Expired on ${formatDate(status.on)}. Expired medicine may not work and should not be taken."
        is ExpiryStatus.ExpiresSoon -> when (status.daysLeft) {
            0 -> "Expires today (${formatDate(status.on)})."
            1 -> "Expires tomorrow (${formatDate(status.on)})."
            else -> "Expires in ${status.daysLeft} days, on ${formatDate(status.on)}."
        }
        is ExpiryStatus.Valid -> "Expires on ${formatDate(status.on)}."
    }

    private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** 2026-03-31 -> "31 Mar 2026". */
    fun formatDate(d: PackDate): String = "${d.day} ${MONTH_NAMES[d.month - 1]} ${d.year}"

    /** Days since 1970-01-01 (proleptic Gregorian), for day counts without java.time. */
    private fun epochDay(d: PackDate): Long {
        val y = if (d.month <= 2) d.year - 1 else d.year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (d.month + 9) % 12
        val doy = (153 * mp + 2) / 5 + d.day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    /** The scan-history level: green SAFE, amber UNVERIFIED, red UNSAFE. */
    fun historySafety(verdict: Verdict): ScanSafety = when (verdict.level) {
        VerdictLevel.GREEN -> ScanSafety.SAFE
        VerdictLevel.AMBER -> ScanSafety.UNVERIFIED
        VerdictLevel.RED -> ScanSafety.UNSAFE
    }

    /** "Find a nearby drugstore" is offered when the result isn't green. */
    fun offersNearbyDrugstores(verdict: Verdict): Boolean = verdict.level != VerdictLevel.GREEN

    /** 0.425 -> "430 m", 1.573 -> "1.6 km", 12.34 -> "12 km". */
    fun formatDistance(km: Double): String = when {
        km < 1.0 -> "${(Math.round(km * 100) * 10).coerceAtLeast(10)} m"
        km < 10.0 -> String.format(java.util.Locale.ROOT, "%.1f km", km)
        else -> "${Math.round(km)} km"
    }
}
