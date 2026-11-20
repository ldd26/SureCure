# Model integration notes

Running log of how the photo model was brought into the app, what differed between the
model package and the app, and what was decided. Branch: `integration/model`.

## Model package

- Run `run_20261024_0546` (energy guard). `surecure.onnx`, `meta.json` and `labels.json`
  all come from this run; `expected.json` and the golden tensors match it.
- Do not mix files from different runs. The guard threshold, temperature and confidence
  threshold in `meta.json` are calibrated per run. `ProductClassifier.create()` refuses a
  `meta.json` without an energy `guard` block, and checks the ONNX outputs against it.
- `meta.guard_verdict` says "NOT EFFECTIVE". Per `docs/model_card.md` that refers to the
  rejected Mahalanobis guard; the app ignores the field. `ood_z` is ignored
  (`ood_z_enabled: false`).

## What was added

| Path | What |
|---|---|
| `App/src/main/assets/model/` | `surecure.onnx` (45.8 MB), `meta.json`, `labels.json` |
| `App/src/main/java/com/mlbb/surecure/ml/ImagePreprocessor.kt` | EXIF orientation, letterbox, resize, tensor layout. Pure Kotlin |
| `App/src/main/java/com/mlbb/surecure/ml/ProductClassifier.kt` | Loads the model once, runs it, applies the decision rules, returns `ClassifierResult` |
| `App/src/test/.../ml/ImagePreprocessorTest.kt` + `src/test/resources/preprocess/` | JVM test against outputs made by PIL |
| `App/src/androidTest/.../ml/ProductClassifierGoldenTest.kt` + `androidTest/assets/golden/` | Golden acceptance test on device |
| `scripts/make_preprocess_fixtures.py` | Regenerates the PIL fixtures (`python scripts/make_preprocess_fixtures.py App/src/test/resources/preprocess`) |
| `scripts/verify_golden.py` | Desktop check of a model package against its golden files (needs `onnxruntime numpy pillow`) |

Existing files touched:

- `gradle/libs.versions.toml`: added `onnxruntime = "1.31.0"` and the
  `onnxruntime-android` library entry.
- `App/build.gradle.kts`: one line, `implementation(libs.onnxruntime.android)`.

No app screens, data models or navigation were changed.

## How to use the classifier

```kotlin
// Once, off the main thread (reads a ~46 MB file):
val classifier = ProductClassifier.create(context)

// Per photo, off the main thread:
val result = classifier.classify(context, photoUri)   // or classify(bitmap, exifOrientation)
when (result.status) {
    Recognition.ACCEPTED -> /* show result.top1.label with a "Not this?" link */
    Recognition.LOW_CONFIDENCE -> /* "Which one is it?" with result.top3 + "None of these" */
    Recognition.NOT_RECOGNIZED -> /* product list + manual code entry */
}
```

- Labels are the exact `labels.json` strings (`Myra_E`, `Neurogen_E`, `Rogin_E`,
  `Tuseran_Forte`, `DayZinc`, ...). Map display names from these; never use an index.
- Step 1 of `meta.decision_order` (a decoded barcode found in the product table skips the
  photo model) is the caller's job; it isn't in the classifier.
- The user always confirms the product before the rules engine runs.
- Pass the photo `Uri` (or the encoded bytes) rather than a pre-rotated, pre-scaled bitmap,
  so the classifier applies EXIF orientation and its own resize.

## Preprocessing decisions

- The contract's resize is PIL `Image.resize(BILINEAR)`. When shrinking, PIL widens the
  filter to average over the source pixels; Android's `createScaledBitmap` doesn't, so a
  12 MP photo would come out sharper than the calibration images. `ImagePreprocessor`
  reimplements Pillow's resampling (same coefficients, same 22-bit fixed-point rounding,
  horizontal pass first) and is byte-identical to PIL on the test fixtures.
- EXIF orientation is applied by index remapping (all 8 values), matching
  `ImageOps.exif_transpose`; no `Matrix` rotation, so no extra filtering.
- The letterbox square is never allocated: pixels outside the photo read as `fill_rgb`.
  This keeps a 12 MP photo at the size of its own bitmap, not a 4000 x 4000 canvas.
- Full-resolution decode is kept (no `inSampleSize`), because downsampling at decode time
  would change the pixels the model sees.

## Test results (2026-10-24)

- `ImagePreprocessorTest` (JVM, `./gradlew :app:testDebugUnitTest`): 3/3 pass, 0 bytes
  differ from PIL (downscale, upscale, no-resize, all 8 EXIF orientations).
- `ProductClassifierGoldenTest` (emulator, API 35 x86_64 ATD image): 3/3 pass.
  - Golden tensors: worst logit difference 2.2e-6 (tolerance 1e-3), same top-1 and same
    accept decision on all 10.
  - Raw photos (EXIF orientation 6) through the Kotlin path: same top-1 on all 10, worst
    logit difference 2.2e-6 (tolerance 0.1).
- Note: 3 of the 10 golden photos have a wrong expected top-1 (Bonamine and DayZinc give
  Lagundi, Imodium gives Sinecod) and only 2 are accepted. That is the reference output,
  so the parity test is right to expect it; it is not an accuracy demo.

How to run the golden test:

```bash
./gradlew :app:connectedDebugAndroidTest --tests 'com.mlbb.surecure.ml.*'
```

## Differences found in the app (not changed; for the app dev)

1. `settings.gradle.kts` has `include(":app")` but the folder is `App/`. Works on Windows
   and macOS, fails on Linux/CI with "Configuring project ':app' without an existing
   directory is not allowed" (confirmed with Gradle 9.6). Suggested fix:
   `project(":app").projectDir = file("App")`, or rename the folder. (Fixed 2026-11-17, #17.)
2. `gradlew` is committed without the execute bit (mode 100644). Linux/macOS need
   `sh gradlew`. Fix: `git update-index --chmod=+x gradlew`. (Fixed 2026-11-17, #17.)
3. `ui/helper/ScanDateFormatter.kt` declares `package com.mlbb.surecure.util`.
4. The sample `scan_history.json` sits in `src/main/java/...`, which isn't packaged; the app
   reads `filesDir/scan_history.json` and starts from `[]`.
5. `java.time` is used behind `@RequiresApi(O)` while `minSdk = 24`; API 24 to 25 would crash
   without core library desugaring. (Fixed 2026-11-17, #17: desugaring enabled.)
6. Verdicts are two levels (`ScanSafety { SAFE, UNSAFE }`, "Marked safe/unsafe"); the
   product needs red / amber / green with "No match with FDA counterfeit records" wording (fixed: the dashboard now shows 'No match with FDA records' / 'Flagged: check with FDA').
   To be proposed separately; not changed here.

## Open questions

- APK size. The debug APK is 195 MB: ONNX Runtime ships native libraries for 4 ABIs
  (x86 40.9 MB, x86_64 40.9 MB, arm64-v8a 34.3 MB, armeabi-v7a 24.3 MB) plus the 45.8 MB
  model. An `abiFilters` setting (for example arm64-v8a + armeabi-v7a for phones) would roughly
  halve it, but changes the Gradle setup, so it needs the app dev's OK. (2026-11-17: the build now
  writes one APK per ABI instead; see the update at the end.)
- Repo size. `surecure.onnx` is 45.8 MB, under GitHub's 50 MB warning and 100 MB limit,
  so it commits normally. Git LFS or a GitHub Release asset is an option if the team
  prefers to keep the repo small.

## Scan-result flow (Figure 1, stages 1 to 3)

Added after the app dev's camera and photo review screens. New files in `ui/result/`:

- `ScanFlow.kt`: the flow logic, no Compose (unit tested in `ScanFlowTest`).
- `ScanResultScreen.kt`: the screen.
- `ScanEngines.kt`: loads the photo model and FDA tables once per app process.

Flow: photo review → "Check this medicine" → `scan-result`:

1. **Stage 1, product.** The front photo goes through `ProductClassifier`.
   - `ACCEPTED`: "This looks like X" with "Yes, that's it" / "Not this?".
   - `LOW_CONFIDENCE`: "Which one is it?" with the top 3 / "None of these".
   - `NOT_RECOGNIZED` (or the model fails): manual selection. The list shows the 20 model
     products first, then every other product in the FDA tables, with a search field and
     "It's not listed / I don't know".
2. **Stage 2, codes.** The code-panel photo is shown above fields for lot, registration number,
   expiry and manufacture date. Since #16 (2026-11-16) on-device OCR pre-fills these fields
   for the user to confirm; see the update at the end. Blank means "not entered". A date that can't be read
   is flagged on the field instead of being ignored.
3. **Stage 3, verdict.** `verify()` on the bundled tables; the headline, the product checked
   and every reason. "Edit the codes" goes back with the typed codes kept.

Existing files touched (glue only):

- `ui/navigation/AppNavHost.kt`: new `scan-result` destination (reads the photo URIs from the
  camera entry, as photo-review does); photo review's continue action now goes there instead
  of straight back to the dashboard.
- `ui/camera/PhotoReviewScreen.kt`: button label "Return to dashboard" → "Check this medicine",
  and the note "Result analysis has not been connected yet" removed.

### Pipeline revisions (proposed, to confirm with the team)

- **Red has two causes.** The engine (and the Python spec) gives red for a listed counterfeit lot
  and for codes that belong to another product. Figure 1 shows the second as amber. The engine
  is unchanged; the screen shows "Matches an FDA counterfeit advisory" only for lot matches and
  "Codes belong to a different product" for the other case (`Verdict.counterfeitLotMatch`, a
  Kotlin-only field; levels and reasons still match Python). Suggested Figure 1 wording for red:
  "Lot matches FDA advisory, or codes belong to another product".
- **Barcode first is deferred.** The model contract's decision order starts with "barcode found
  in the product table → skip the photo model"; Figure 1 reads codes after the photo. With no
  barcode table yet, the app follows Figure 1. The dashboard's "Do you have the barcode?"
  answer is still unused.

### Not done yet

- Stage 0 blur and glare check; Stage 4 visual check and reporting. (Stage 2 ML Kit reading: done in #16.)
- Saving the result to scan history: needs `ScanSafety` to have three levels (app dev's model).
- The screen state is not kept across rotation or process death.
- The screens compile and their logic is unit tested, but have not been run on a device yet.

## Coordination update (2026-10-24, after #5 to #8 merged)

- **Stage 2 barcode field.** The scan-result screen now has a "Barcode number" field (spaces removed,
  nothing else changed), so the barcode table from #6 is used on real scans: another product's barcode
  gives red, an unlisted one amber, a misread one amber.
- **Nearby drugstores.** Amber and red results offer "Find a nearby drugstore"
  (`ui/result/NearbyDrugstores.kt`): one coarse location reading, or a city search. Adds
  `ACCESS_COARSE_LOCATION` to the manifest.
- **README.** The abstract now names the energy-score guard; added status, layout and test sections.
- **Barcode first (decided 2026-10-24).** Follows `meta.decision_order` step 1. When the user answers
  "Yes, I have it" on the dashboard, the scan starts with a typed barcode: if it's in the barcode table,
  the product comes from it ("The barcode says this is ...", user confirms) and the photo model is
  skipped; if it's valid but not listed, the photo model runs and the barcode stays in the scan (amber);
  if it's unreadable, the user retypes it or uses the photo. "No" keeps photo first. The dashboard's
  answer is passed through the dashboard entry's `savedStateHandle` (`AppNavHost.kt`). Figure 1 should
  show the barcode check before product identification. A camera barcode scanner (ML Kit) is still to come.
- Still for the app dev or the team: saving results to scan history (three-level `ScanSafety`), the
  red-for-mismatched-codes wording in Figure 1, and the `:app` / `App/` build issue.

## Update (2026-11-17)

- **Code-panel OCR (#16).** `ocr/CodePanelReader.kt` runs ML Kit Text Recognition v2 (bundled
  Latin model, `com.google.mlkit:text-recognition:16.0.1`, offline) on the code-panel photo;
  `ocr/CodePanelText.kt` (pure Kotlin, JVM-tested in `CodePanelTextTest`) extracts the barcode,
  lot, registration number and dates. `ScanFlow.prefill` fills only blank fields, and "Check
  against FDA records" needs the user to tick "I've checked these codes against the pack" (or edit
  every pre-filled field). OCR never decides a verdict.
- **OCR fixes (branch `fix/ocr-and-apk-size`).** Ordinary words are no longer read as registration
  numbers ("DR. BOSS" gives nothing; plain `DR`/`FR` need a hyphen or a "Reg." label); labels
  stacked above their values and combined labels ("Lot No./Exp. Date: A / B") are paired;
  misread labels and months (`L0T`, `N0.`, `0CT`), compact dates (`15MAR2O28`, `EXP032028`) and a
  stray mark beside the barcode are handled; two different values for one field suggest neither.
  The photo is decoded downsampled (long side at least 1920 px) with its EXIF rotation passed to
  ML Kit, and an out-of-memory error falls back to typing.
- **Fixed (#17).** Android 7.0/7.1 crash (core library desugaring on), `:app` / `App/` build path
  and `gradlew` execute bit, README branch count, model card run name, and the lot rules (see
  `docs/rules_integration_notes.md`).
- **APK size.** One APK per ABI (`splits.abi`: arm64-v8a, armeabi-v7a, x86_64; 32-bit x86
  dropped). Debug APKs: arm64-v8a 107.8 MB, armeabi-v7a 93.5 MB, x86_64 115.0 MB, instead of one
  244 MB APK. Not done, for the team to decide: compressing native libraries
  (`packaging.jniLibs.useLegacyPackaging = true`) would cut the arm64 download by about 28 MB but
  adds about 45 MB of installed size; quantizing the 46 MB model needs the full model route.
- **Expiry first (branch `feature/expiry-first`, from the 2026-11-17 mentor session).** As soon as
  the expiry field has a readable date (from OCR or typed), the code step says "Expired" or
  "Expires in N days" (90 days or fewer). On the verdict, an expired pack gets the headline
  "Expired: do not take this medicine" in red styling, with the date stated under it; a counterfeit
  lot match keeps the red advisory headline. Display only: the rules engine is unchanged (still
  amber "Expired on ..."), so parity holds, and history still saves amber as `UNSAFE`. Logic in
  `ScanFlow.expiryStatus` / `expiryNote` / `headline`, tested in `ScanFlowTest`.
- **Field-test preparation (branch `feature/field-test-prep`).** The code-panel photo is also
  scanned for EAN-13 bars (ML Kit Barcode Scanning 17.3.0, bundled; Google says about 2.4 MB, measured +6.0 MB on the arm64 APK, now 113.9 MB): one valid code
  from the bars replaces the digits read by OCR, two different ones leave the field empty
  (`CodePanelText.scan`). The barcode-first step is now pre-filled too; pressing "Check the
  barcode" is the confirmation, and an unconfirmed value is cleared if the user switches to the
  photo. The code step has "Show what the phone read" with "Copy this text", so testers can
  capture real OCR text. `docs/field_test.md` is the test protocol,
  `data/field_test/results_template.csv` the results sheet and `scripts/field_test_metrics.py`
  the metrics (95% Wilson intervals).

## Update (2026-11-19, first phone test)

- **Every code-panel photo failed to read (fixed).** The tester saw "The photo couldn't be read"
  on every scan. Cause: `CodePanelReader.decode` treated the `null` that
  `BitmapFactory.decodeStream` returns by design for a size-only decode (`inJustDecodeBounds`) as
  "can't open", so it threw before text recognition or barcode scanning ran (bug from #18; unit
  tests can't reach Android decoding and it had never run on a device). The failure message now
  includes the reason and is logged (tag `SureCure`).
- **New device test** `CodePanelReaderTest` (androidTest): draws a code panel (labels and a real
  EAN-13 barcode) into a JPEG and runs the reader on it upright and stored sideways with an EXIF
  rotation tag. It would have caught the bug; run it on any phone with
  `./gradlew :app:connectedDebugAndroidTest`.
- **Codes printed without labels.** If no date is labelled, a line that is only a date is used: one
  date is the expiry; two dates are manufacture (earlier) and expiry (later). If no lot is
  labelled, a line holding a single code with 4+ digits that isn't the barcode, a date or a
  registration number is used, only if it is the only such line.
- **OCR engine.** The tester asked for a better OCR model. The failure was the bug above, not ML
  Kit's accuracy, so ML Kit stays until it is measured on real packs. The models in the linked
  thread (PaddleOCR-VL, GLM-OCR, MinerU, dots.ocr, olmOCR) are 0.9B to 7B-parameter document
  VLMs: hundreds of MB to GBs, built for GPUs, so they conflict with offline phone use and app
  size. The realistic on-device alternative is PP-OCRv5 mobile (about 5M parameters, Apache 2.0,
  ONNX models of a few MB that run on the ONNX Runtime the app already ships). It would need
  detection and recognition pre/post-processing written in Kotlin; candidate after the
  presentation if ML Kit's real-pack read rate is poor.
- **App size, measured 2026-11-19 (arm64).** Debug APK 114.0 MB: model 42.7 MB, ONNX Runtime
  34.2 MB, app code (dex) 17.2 MB, ML Kit text recognition 11.1 MB, ML Kit barcode 5.0 MB, ML Kit
  models 2.2 MB, resources 1.1 MB. The release build (R8 on `androidx`, `kotlin`, `kotlinx`, as
  configured) shrinks the code to 3.7 MB: release APKs are arm64 99.6 MB, armeabi-v7a 83.5 MB,
  x86_64 107.7 MB. Release builds are now signed with the local debug key (`signingConfig =
  signingConfigs.getByName("debug")`) so they can be installed for testing and the demo; verified
  with `apksigner` (v2 scheme, "CN=Android Debug"). Replace with a real release key before
  publishing.
  Larger savings, each needing a team decision or more time: model in fp16 (about -21 MB; needs the
  golden test and a check of the energy threshold), a reduced-operator ONNX Runtime build (about
  -30 MB; long native build), compressed native libraries (smaller download, larger install).
- **Barcode step and small barcodes (second Nov 19 screenshot).** The tester also tried a barcode
  shown small on a laptop screen; the barcode step stayed empty and gave no reason. The step now
  shows "Reading the barcode from the photo...", the failure reason, or "No barcode found in the
  photo ... retake with the barcode filling more of the frame". If the downsampled photo shows no
  valid EAN-13, the reader decodes the centre 60% of the photo at full resolution
  (`BitmapRegionDecoder`, at most 2600 px per side) and scans the bars again, since a barcode
  filling a small part of the frame drops below the about 190 px EAN-13 needs once downsampled.
  Device test `readsASmallBarcodeInALargePhoto`. The camera hint for the second photo now says to
  fill the frame with the code panel and avoid glare.

## Update (2026-11-20, real pack OCR)

- **First real OCR text** (tester, Neozep Forte blister strip, transcribed from the tester's
  screenshot of "Show what the phone read"): saved in `data/field_test/ocr/` and used as a test
  case. Nothing was filled because the strip prints lot and expiry together without labels
  ("26076601 MAY 2029", repeated about five times) and ML Kit misread "MAY" as "NAY" or "MNY".
  A cetirizine pack read its expiry correctly.
- **Extraction changes.** A lot and a date on one unlabelled line are split (either order). A
  three-letter word one letter from exactly one month, just before a year, is read as that month
  ("NAY 2029" and "MNY 22" as MAY; words such as "SRP" are excluded). Repeated values are
  majority-voted: the value read strictly more often than any other wins, a tie still fills nothing
  (so "two different values suggest neither" holds when each is read once). 4 new tests,
  including the tester's text.
- **Accessibility idea from the tester.** "Show what the phone read" also helps people with low
  vision read small print; worth a line in the presentation.

## Update (2026-11-20, demo release)

- **v1.0-demo crashed at launch on every device** ("SureCure keeps stopping"): `IllegalAccessError:
  Class kotlin.collections.i extended by class zy is inaccessible`, raised in `androidx.startup` /
  `ProcessLifecycleInitializer`. With R8 optimization limited to `androidx`, `kotlin` and `kotlinx`
  (`packageScope`), R8 moved a subclass of a package-private Kotlin class into another package. The
  R8 release build had never been opened on a device before it was published.
- **Fix:** R8 is off for release builds (`optimization { enable = false }`), version 1.0.1 (code 2) so
  it installs over 1.0. Release APKs: arm64 108.4 MB, armeabi-v7a 92.4 MB, x86_64 116.6 MB (were
  99.6 / 83.6 / 107.8 MB with R8). The library classes keep their names, as in the debug builds
  already tested on phones. Re-enable R8 only for the whole app, with keep rules, after a device test.
- **Download:** GitHub release `v1.0-demo` on ldd26/SureCure; the QR code in `docs/media/` points at
  `releases/download/v1.0-demo/SureCure.apk`, so replacing the file keeps the QR valid.
