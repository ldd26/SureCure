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
   `project(":app").projectDir = file("App")`, or rename the folder.
2. `gradlew` is committed without the execute bit (mode 100644). Linux/macOS need
   `sh gradlew`. Fix: `git update-index --chmod=+x gradlew`.
3. `ui/helper/ScanDateFormatter.kt` declares `package com.mlbb.surecure.util`.
4. The sample `scan_history.json` sits in `src/main/java/...`, which isn't packaged; the app
   reads `filesDir/scan_history.json` and starts from `[]`.
5. `java.time` is used behind `@RequiresApi(O)` while `minSdk = 24`; API 24 to 25 would crash
   without core library desugaring.
6. Verdicts are two levels (`ScanSafety { SAFE, UNSAFE }`, "Marked safe/unsafe"); the
   product needs red / amber / green with "No match with FDA counterfeit records" wording (fixed: the dashboard now shows 'No match with FDA records' / 'Flagged: check with FDA').
   To be proposed separately; not changed here.

## Open questions

- APK size. The debug APK is 195 MB: ONNX Runtime ships native libraries for 4 ABIs
  (x86 40.9 MB, x86_64 40.9 MB, arm64-v8a 34.3 MB, armeabi-v7a 24.3 MB) plus the 45.8 MB
  model. An `abiFilters` setting (for example arm64-v8a + armeabi-v7a for phones) would roughly
  halve it, but changes the Gradle setup, so it needs the app dev's OK.
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
   expiry and manufacture date. Typed by the user for now; ML Kit reading should fill the same
   fields later for the user to confirm. Blank means "not entered". A date that can't be read
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

- Stage 0 blur and glare check; Stage 2 ML Kit reading; Stage 4 visual check and reporting.
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
