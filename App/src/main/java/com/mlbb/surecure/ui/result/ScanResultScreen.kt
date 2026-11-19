package com.mlbb.surecure.ui.result

import android.net.Uri
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.mlbb.surecure.data.repository.ScanHistoryRepository
import com.mlbb.surecure.ui.dashboard.ScanHistoryEntry
import com.mlbb.surecure.ui.dashboard.ScanSafety
import java.time.Instant
import java.util.UUID
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mlbb.surecure.ml.ClassifierResult
import com.mlbb.surecure.ocr.CodePanelReader
import com.mlbb.surecure.ocr.CodePanelScan
import com.mlbb.surecure.ocr.CodePanelText
import com.mlbb.surecure.rules.FdaTables
import com.mlbb.surecure.rules.PackDate
import com.mlbb.surecure.rules.Verdict
import com.mlbb.surecure.rules.VerdictLevel
import com.mlbb.surecure.rules.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Same values as the dashboard's brand colors (private there).
private val SureCureGreen = Color(0xFF14AE5C)
private val SureCureDarkGreen = Color(0xFF02542D)
private val SureCureRed = Color(0xFFC00F0C)
private val SureCureAmber = Color(0xFFB26A00)
private val GreenBackground = Color(0xFFDEFFE8)
private val RedBackground = Color(0xFFFFE8E8)
private val AmberBackground = Color(0xFFFFF3DC)

private sealed interface Phase {
    data object Loading : Phase
    data class Barcode(val error: String?) : Phase
    data class Product(val step: ScanFlow.ProductStep, val fromBarcode: Boolean = false) : Phase
    data class Codes(val product: String?) : Phase
    data class Result(val verdict: Verdict, val product: String?, val expiry: ScanFlow.ExpiryStatus? = null) : Phase
    data class Nearby(val result: Result) : Phase
}

/** On-device reading of the code-panel photo; only ever pre-fills fields for the user to confirm. */
private sealed interface OcrState {
    data object Reading : OcrState
    data class Done(val scan: CodePanelScan) : OcrState
    /** [reason] is shown to the user, so testers can report why reading failed. */
    data class Failed(val reason: String) : OcrState
}

/**
 * Scan result flow after photo review (Figure 1):
 * Stage 1 product identification + manual selection, Stage 2 code entry (pre-filled from the
 * code-panel photo by on-device OCR, confirmed or typed by the user), Stage 3 verification rules
 * and verdict.
 */
@Composable
fun ScanResultScreen(
    frontPhoto: Uri,
    codePanelPhoto: Uri,
    onDone: () -> Unit,
    /** The dashboard's "Do you have the medicine's barcode?" answer: yes starts with the barcode. */
    hasBarcode: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One history entry per scan: re-checking after "Edit the codes" replaces it.
    val historyId = remember { UUID.randomUUID().toString() }
    // The pending history save; "Done" waits for it so the dashboard shows this scan.
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Loading) }
    var tables by remember { mutableStateOf<FdaTables?>(null) }
    var modelLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf(ScanFlow.CodeEntry()) }
    var ocr by remember { mutableStateOf<OcrState>(OcrState.Reading) }
    // The reading is applied once, when the code step first opens; later visits keep the user's edits.
    var ocrApplied by remember { mutableStateOf(false) }
    var fromPhoto by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Every field the photo filled, kept after the user edits one, so a cleared field isn't "not found".
    var foundInPhoto by remember { mutableStateOf<Set<String>>(emptySet()) }
    var codesConfirmed by remember { mutableStateOf(false) }
    // Barcode-first step: where a pre-filled barcode came from ("bars" or "digits"), until the user
    // edits it or confirms it with "Check the barcode". Null when nothing was pre-filled.
    var barcodeFromPhoto by remember { mutableStateOf<String?>(null) }
    var barcodeStageFilled by remember { mutableStateOf(false) }

    // Stage 1 by photo: the photo model, or manual selection if it can't decide.
    fun identifyByPhoto() {
        phase = Phase.Loading
        scope.launch {
            var result: ClassifierResult? = null
            try {
                val classifier = ScanEngines.classifier(context)
                modelLabels = classifier.meta.labels
                result = withContext(Dispatchers.Default) { classifier.classify(context, frontPhoto) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "The photo couldn't be checked (${e.localizedMessage ?: "error"}). Choose the product below."
            }
            phase = Phase.Product(ScanFlow.productStep(result))
        }
    }

    LaunchedEffect(frontPhoto) {
        try {
            tables = ScanEngines.tables(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = "The FDA tables couldn't be loaded (${e.localizedMessage ?: "error"})."
        }
        // Decision order step 1 (meta.json): barcode first when the user has one.
        if (hasBarcode && tables != null) {
            phase = Phase.Barcode(null)
            // Load the photo model meanwhile: needed for "Not this?" and if the barcode isn't listed.
            try {
                modelLabels = ScanEngines.classifier(context).meta.labels
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // identifyByPhoto() reports it if the photo is needed.
            }
        } else {
            identifyByPhoto()
        }
    }

    // Read the code panel while the product is identified, so it is usually ready at the code step.
    LaunchedEffect(codePanelPhoto) {
        ocr = try {
            OcrState.Done(CodePanelReader.read(context, codePanelPhoto))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SureCure", "Code-panel reading failed", e)
            OcrState.Failed("${e.javaClass.simpleName}: ${e.localizedMessage ?: "no details"}")
        } catch (e: OutOfMemoryError) {
            // A very large photo on a low-memory phone: fall back to typing instead of crashing.
            Log.w("SureCure", "Code-panel reading ran out of memory", e)
            OcrState.Failed("out of memory")
        }
    }
    // Barcode-first step: pre-fill the barcode once, for the user to check before "Check the barcode".
    val atBarcodeStep = phase is Phase.Barcode
    LaunchedEffect(atBarcodeStep, ocr) {
        val o = ocr
        val read = (o as? OcrState.Done)?.scan
        if (atBarcodeStep && !barcodeStageFilled && read != null) {
            barcodeStageFilled = true
            val code = read.reading.barcode
            if (code != null && entry.barcode.isBlank()) {
                entry = entry.copy(barcode = code)
                barcodeFromPhoto = if (read.barcodeFromBars) "bars" else "digits"
            }
        }
    }
    val atCodeStep = phase is Phase.Codes
    LaunchedEffect(atCodeStep, ocr) {
        val o = ocr
        if (atCodeStep && !ocrApplied && o is OcrState.Done) {
            val p = ScanFlow.prefill(entry, o.scan.reading)
            entry = p.entry
            fromPhoto = p.fromPhoto
            foundInPhoto = p.fromPhoto
            ocrApplied = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Check this medicine", style = MaterialTheme.typography.headlineSmall)
        notice?.let { Text(it, color = SureCureRed, style = MaterialTheme.typography.bodyMedium) }

        when (val p = phase) {
            Phase.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.padding(start = 12.dp))
                Text("Identifying the product...")
            }

            is Phase.Barcode -> BarcodeStage(
                codePanelPhoto = codePanelPhoto,
                value = entry.barcode,
                error = p.error,
                fromPhoto = barcodeFromPhoto,
                onValueChange = { entry = entry.copy(barcode = it); barcodeFromPhoto = null },
                onCheck = {
                    // Pressing "Check the barcode" is the user's confirmation of a pre-filled value.
                    barcodeFromPhoto = null
                    val t = tables
                    if (t == null) {
                        identifyByPhoto()
                    } else when (val r = ScanFlow.barcodeFirst(entry.barcode, t)) {
                        is ScanFlow.BarcodeFirst.Found -> {
                            entry = entry.copy(barcode = r.barcode)
                            phase = Phase.Product(ScanFlow.ProductStep.Confirm(r.label), fromBarcode = true)
                        }
                        is ScanFlow.BarcodeFirst.NotInTable -> {
                            entry = entry.copy(barcode = r.barcode)
                            notice = "Barcode ${r.barcode} isn't in our table yet, so the photo is used instead."
                            identifyByPhoto()
                        }
                        is ScanFlow.BarcodeFirst.Unreadable -> phase = Phase.Barcode(
                            "That doesn't look like a complete barcode (13 digits with a valid last digit). " +
                                "Check it, or use the photo instead."
                        )
                    }
                },
                onUsePhoto = {
                    // An unconfirmed pre-filled barcode goes back to the code step, where it must be confirmed.
                    if (barcodeFromPhoto != null) entry = entry.copy(barcode = "")
                    barcodeFromPhoto = null
                    identifyByPhoto()
                },
            )

            is Phase.Product -> ProductStage(
                step = p.step,
                fromBarcode = p.fromBarcode,
                choices = tables?.let { ScanFlow.productChoices(modelLabels, it) } ?: modelLabels,
                onChosen = { phase = Phase.Codes(it) },
                onShowList = { phase = Phase.Product(ScanFlow.ProductStep.ChooseFromList) },
            )

            is Phase.Codes -> CodeStage(
                product = p.product,
                codePanelPhoto = codePanelPhoto,
                entry = entry,
                ocr = ocr,
                scan = (ocr as? OcrState.Done)?.scan,
                photoApplied = ocrApplied,
                foundInPhoto = foundInPhoto,
                fromPhoto = fromPhoto,
                confirmed = codesConfirmed,
                onConfirmedChange = { codesConfirmed = it },
                onEntryChange = { e, key ->
                    entry = e
                    fromPhoto = fromPhoto - key
                },
                onChangeProduct = { phase = Phase.Product(ScanFlow.ProductStep.ChooseFromList) },
                onCheck = { scan ->
                    val t = tables
                    if (t != null) {
                        val verdict = verify(scan, t)
                        phase = Phase.Result(
                            verdict, p.product, ScanFlow.expiryStatus(scan.expDate, PackDate.today())
                        )
                        // NonCancellable: leaving the screen right away must not drop the save.
                        saveJob = scope.launch {
                            withContext(NonCancellable) { saveToHistory(context, historyId, verdict) }
                        }
                    }
                    else notice = "The FDA tables couldn't be loaded."
                },
            )

            is Phase.Result -> VerdictStage(
                verdict = p.verdict,
                expiry = p.expiry,
                onEditCodes = { phase = Phase.Codes(p.product) },
                onFindDrugstore = if (ScanFlow.offersNearbyDrugstores(p.verdict)) {
                    { phase = Phase.Nearby(p) }
                } else null,
                onDone = { scope.launch { saveJob?.join(); onDone() } },
            )

            is Phase.Nearby -> NearbyDrugstores(onBack = { phase = p.result })
        }
    }
}

// --------------------------------------------------------------------------- Stage 1, barcode first

@Composable
private fun BarcodeStage(
    codePanelPhoto: Uri,
    value: String,
    error: String?,
    /** "bars" or "digits" if [value] was read from the photo and not yet edited; null otherwise. */
    fromPhoto: String?,
    onValueChange: (String) -> Unit,
    onCheck: () -> Unit,
    onUsePhoto: () -> Unit,
) {
    Text(
        if (fromPhoto != null) "Check the barcode number" else "Type the barcode number",
        style = MaterialTheme.typography.titleMedium
    )
    Photo(codePanelPhoto, "Code panel photo")
    val hint = error ?: fromPhoto?.let {
        "Read from the ${if (it == "bars") "bars" else "printed digits"} in the photo. " +
            "Check it matches the 13 digits under the bars."
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Barcode number (13 digits under the bars)") },
        singleLine = true,
        isError = error != null,
        supportingText = hint?.let { msg -> { Text(msg) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = onCheck,
        enabled = value.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = SureCureGreen)
    ) { Text("Check the barcode") }
    TextButton(onClick = onUsePhoto) { Text("Use the photo instead") }
}

// --------------------------------------------------------------------------- Stage 1

@Composable
private fun ProductStage(
    step: ScanFlow.ProductStep,
    fromBarcode: Boolean,
    choices: List<String>,
    onChosen: (String?) -> Unit,
    onShowList: () -> Unit,
) {
    when (step) {
        is ScanFlow.ProductStep.Confirm -> {
            Text(
                if (fromBarcode) "The barcode says this is" else "This looks like",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                ScanFlow.displayName(step.label),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = SureCureDarkGreen
            )
            Button(
                onClick = { onChosen(step.label) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SureCureGreen)
            ) { Text("Yes, that's it") }
            TextButton(onClick = onShowList) { Text("Not this?") }
        }

        is ScanFlow.ProductStep.Pick -> {
            Text("Which one is it?", style = MaterialTheme.typography.titleMedium)
            step.labels.forEach { label ->
                OutlinedButton(onClick = { onChosen(label) }, modifier = Modifier.fillMaxWidth()) {
                    Text(ScanFlow.displayName(label))
                }
            }
            TextButton(onClick = onShowList) { Text("None of these") }
        }

        ScanFlow.ProductStep.ChooseFromList -> ProductList(choices, onChosen)
    }
}

@Composable
private fun ProductList(choices: List<String>, onChosen: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    Text("Which medicine is it?", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    val shown = choices.filter { ScanFlow.displayName(it).contains(query.trim(), ignoreCase = true) }
    shown.forEach { label ->
        OutlinedButton(onClick = { onChosen(label) }, modifier = Modifier.fillMaxWidth()) {
            Text(ScanFlow.displayName(label))
        }
    }
    TextButton(onClick = { onChosen(null) }) {
        Text("It's not listed / I don't know")
    }
}

// --------------------------------------------------------------------------- Stage 2

@Composable
private fun CodeStage(
    product: String?,
    codePanelPhoto: Uri,
    entry: ScanFlow.CodeEntry,
    ocr: OcrState,
    /** What was read from the photo, for "Show what the phone read"; null until reading finishes. */
    scan: CodePanelScan?,
    /** True once the photo's reading has been applied to the fields. */
    photoApplied: Boolean,
    /** Fields the photo filled, whether or not the user has edited them since. */
    foundInPhoto: Set<String>,
    /** Fields still holding the photo's reading (not edited by the user since). */
    fromPhoto: Set<String>,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    /** The new entry and the key of the field the user edited. */
    onEntryChange: (ScanFlow.CodeEntry, String) -> Unit,
    onChangeProduct: () -> Unit,
    onCheck: (com.mlbb.surecure.rules.ScanInput) -> Unit,
) {
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Text(
        "Product: " + (product?.let { ScanFlow.displayName(it) } ?: "not identified"),
        style = MaterialTheme.typography.titleMedium
    )
    TextButton(onClick = onChangeProduct) { Text("Change product") }

    Text(
        if (fromPhoto.isNotEmpty()) "Check the codes read from the photo" else "Type the codes printed on the pack",
        style = MaterialTheme.typography.titleMedium
    )
    Photo(codePanelPhoto, "Code panel photo")
    when (ocr) {
        OcrState.Reading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Spacer(Modifier.padding(start = 8.dp))
            Text("Reading the code panel...", style = MaterialTheme.typography.bodySmall)
        }
        is OcrState.Failed -> Text(
            "The photo couldn't be read (${ocr.reason}). Type the codes printed on the pack.",
            style = MaterialTheme.typography.bodySmall
        )
        is OcrState.Done -> Unit
    }
    // The raw text, so the user can see why a field was or wasn't filled, and copy it (field tests).
    if (scan != null) {
        val context = LocalContext.current
        var showText by remember { mutableStateOf(false) }
        TextButton(onClick = { showText = !showText }) {
            Text(if (showText) "Hide what the phone read" else "Show what the phone read")
        }
        if (showText) {
            val report = buildString {
                appendLine("Barcode from the bars: ${if (scan.barcodeFromBars) scan.reading.barcode else "none"}")
                appendLine("Text lines:")
                if (scan.lines.isEmpty()) appendLine("(no text found)")
                scan.lines.forEach { appendLine(it) }
            }.trimEnd()
            Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SureCure code panel", report))
            }) { Text("Copy this text") }
        }
    }
    Text(
        "Leave a field blank if it isn't printed or you can't read it.",
        style = MaterialTheme.typography.bodySmall
    )

    /** Error first, then where the value came from, then "not found" once the photo has been read. */
    fun hint(key: String, value: String): String? = errors[key]
        ?: when {
            key in fromPhoto -> "Read from the photo. Check it matches the pack." +
                if (key == ScanFlow.FIELD_LOT && CodePanelText.lotHasLookalikes(value)) {
                    " Look closely at O and 0, I and 1, S and 5."
                } else ""
            photoApplied && key !in foundInPhoto && value.isBlank() -> "Not found in the photo. Type it if it's printed."
            else -> null
        }

    @Composable
    fun field(key: String, value: String, label: String, keyboard: KeyboardType, update: (String) -> ScanFlow.CodeEntry) {
        OutlinedTextField(
            value = value,
            onValueChange = { onEntryChange(update(it), key); errors = errors - key },
            label = { Text(label) },
            singleLine = true,
            isError = key in errors,
            supportingText = hint(key, value)?.let { msg -> { Text(msg) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth()
        )
    }

    field(ScanFlow.FIELD_BARCODE, entry.barcode, "Barcode number (13 digits under the bars)", KeyboardType.Number) {
        entry.copy(barcode = it)
    }
    field(ScanFlow.FIELD_LOT, entry.lot, "Lot / batch number", KeyboardType.Text) { entry.copy(lot = it) }
    field(ScanFlow.FIELD_REG_NO, entry.regNo, "FDA registration number (e.g. DR-XY12345)", KeyboardType.Text) {
        entry.copy(regNo = it)
    }
    field(ScanFlow.FIELD_EXPIRY, entry.expiry, "Expiry date (e.g. MAR 2028)", KeyboardType.Text) {
        entry.copy(expiry = it)
    }
    // Tell the user at once when the pack is expired or about to expire, before the full check.
    ScanFlow.expiryStatus(entry.expiry, PackDate.today())
        ?.takeIf { it !is ScanFlow.ExpiryStatus.Valid }
        ?.let { status ->
            val expired = status is ScanFlow.ExpiryStatus.Expired
            Text(
                (if (expired) "Expired. " else "") + ScanFlow.expiryNote(status),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (expired) SureCureRed else SureCureAmber
            )
        }
    field(ScanFlow.FIELD_MANUFACTURED, entry.manufactured, "Manufacture date (optional)", KeyboardType.Text) {
        entry.copy(manufactured = it)
    }

    // Codes read from the photo are suggestions: the user confirms them before the check runs.
    if (fromPhoto.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = onConfirmedChange)
            Text("I've checked these codes against the pack", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Button(
        onClick = {
            when (val parsed = ScanFlow.parse(product, entry)) {
                is ScanFlow.Parsed.Ok -> onCheck(parsed.scan)
                is ScanFlow.Parsed.Errors -> errors = parsed.fields
            }
        },
        enabled = ScanFlow.canCheck(fromPhoto, confirmed),
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = SureCureGreen)
    ) { Text("Check against FDA records") }
}

// --------------------------------------------------------------------------- Stage 3

@Composable
private fun VerdictStage(
    verdict: Verdict,
    expiry: ScanFlow.ExpiryStatus?,
    onEditCodes: () -> Unit,
    onFindDrugstore: (() -> Unit)?,
    onDone: () -> Unit,
) {
    val expired = expiry is ScanFlow.ExpiryStatus.Expired
    val (color, background) = when {
        // Expired is amber in the rules; shown in red here because the advice is "do not take it".
        verdict.level == VerdictLevel.RED || expired -> SureCureRed to RedBackground
        else -> when (verdict.level) {
            VerdictLevel.RED -> SureCureRed to RedBackground
            VerdictLevel.AMBER -> SureCureAmber to AmberBackground
            VerdictLevel.GREEN -> SureCureGreen to GreenBackground
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            ScanFlow.headline(verdict, expiry),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        // The expiry is stated up front, not only in the reasons below.
        if (expiry != null && expiry !is ScanFlow.ExpiryStatus.Valid) {
            Text(
                ScanFlow.expiryNote(expiry),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (expired) SureCureRed else SureCureAmber
            )
        }
        Text(
            "Checked as: " + (verdict.productChecked?.let { ScanFlow.displayName(it) } ?: "unknown product"),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Text("Why", style = MaterialTheme.typography.titleMedium)
    verdict.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    if (verdict.level == VerdictLevel.GREEN) {
        Text(
            "This means the codes don't match any FDA counterfeit record we have. " +
                "It doesn't prove the medicine is genuine.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (verdict.level != VerdictLevel.GREEN) {
        FdaLink("Check the registration on the FDA verification portal", "https://verification.fda.gov.ph")
        FdaLink("Report to FDA Philippines", "https://www.fda.gov.ph")
    }
    if (onFindDrugstore != null) {
        OutlinedButton(onClick = onFindDrugstore, modifier = Modifier.fillMaxWidth()) {
            Text("Find a nearby drugstore")
        }
    }
    OutlinedButton(onClick = onEditCodes, modifier = Modifier.fillMaxWidth()) { Text("Edit the codes") }
    Button(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = SureCureGreen)
    ) { Text("Done") }
}

@Composable
private fun Photo(uri: Uri, description: String) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageURI(uri)
                contentDescription = description
            }
        },
        update = { it.setImageURI(uri) },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
    )
}

/**
 * Saves the verdict to the dashboard's scan history. The history only has SAFE / UNSAFE, so green is
 * SAFE and red or amber are UNSAFE; the real level, product and reasons go into scanDetails.
 */
internal suspend fun saveToHistory(context: Context, id: String, verdict: Verdict) {
    val details = buildString {
        append(ScanFlow.headline(verdict)).append(" (").append(verdict.level.name.lowercase()).append(")")
        verdict.productChecked?.let { append(". Product: ").append(ScanFlow.displayName(it)) }
        append(". ").append(verdict.reasons.joinToString(" "))
    }
    runCatching {
        ScanHistoryRepository(context).saveEntry(
            ScanHistoryEntry(
                id = id,
                safety = if (verdict.level == VerdictLevel.GREEN) ScanSafety.SAFE else ScanSafety.UNSAFE,
                scannedOn = Instant.now(),
                scanDetails = details,
            )
        )
    }
}

/** A plain text link that opens in the phone's browser (no WebView). */
@Composable
private fun FdaLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }) { Text(label) }
}
