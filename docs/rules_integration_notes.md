# Rules engine integration notes

How the FDA tables and the verification rules were brought into the app, and what was decided.
Branch: `integration/rules` (off `main`, independent of the model PR). The model-side log is
`docs/integration_notes.md` on `integration/model`.

## What the engine does

After the user confirms the product (from the photo model or a list) and the printed codes,
`verify(scan, tables, today)` returns a `Verdict`: a level, every reason that fired (most severe
first) and the product checked. The photo model never decides the verdict.

| Level | App wording (`VerdictLevel.headline`) |
|---|---|
| RED | Matches an FDA counterfeit advisory |
| AMBER | Couldn't fully verify |
| GREEN | No match with FDA counterfeit records (never "safe" or "authentic") |

The reasons are word for word the same as `rules/surecure_rules.py`. Only the headline differs
from Python's `Verdict.message` (which had longer red and amber text), as decided on 2026-10-24.

## What was added

| Path | What |
|---|---|
| `App/src/main/assets/fda/` | `registrations.csv` (66 rows), `counterfeit_lots.csv` (327 rows), about 49 KB |
| `App/src/main/java/com/mlbb/surecure/rules/SureCureRules.kt` | The engine: `normalizeLot`, `editDistance`, `ean13Valid`, tables, `verify`. Pure Kotlin |
| `.../rules/PackDate.kt` | Date type for the engine, plus a parser for printed pack dates |
| `.../rules/Csv.kt` | Small CSV reader (quoted fields, CRLF) |
| `.../rules/FdaTablesLoader.kt` | Reads the tables from assets once and caches them |
| `App/src/test/java/com/mlbb/surecure/rules/` | `SureCureRulesTest` (the 21 Python tests), `RulesRealDataTest`, `RulesParityTest`, `PackDateTest` |
| `App/src/test/resources/rules/` | Python verdicts for the parity test |
| `rules/surecure_rules.py`, `rules/tests/test_rules.py` | Python reference engine and its tests (the specification) |
| `rules/tests/make_parity_cases.py` | Writes the parity cases from the Python engine |
| `data/fda/` | Raw scraped files, cleaned tables, README |
| `scripts/build_fda_tables.py` | Builds the cleaned tables from `data/fda/raw/` |

No existing files were changed.

## How to use it

```kotlin
val tables = FdaTablesLoader.load(context)        // once, off the main thread
val verdict = verify(
    ScanInput(
        stage1Product = "Biogesic",                // the product the user confirmed
        barcode = barcodeOrNull,
        lot = lotOrNull,
        regNo = regNoOrNull,
        mfgDate = PackDate.parsePrinted(mfgText, endOfMonth = false),
        expDate = PackDate.parsePrinted(expText, endOfMonth = true),
    ),
    tables,
)
// verdict.level.headline, verdict.reasons, verdict.productChecked
```

## Decisions

- **Barcodes are optional.** Python's `from_csv` requires `barcodes.csv`; the Kotlin loader
  doesn't, because real EAN-13s aren't collected yet. With no table, any valid scanned barcode
  gives amber "not in the barcode table". The sample `rules/sample_data/barcodes.csv` is not
  bundled: its one row is a made-up EAN that would match a real scan.
- **Month-only dates.** Packs print "MAR 2028" or "05/2027". Expiry becomes the last day of the
  month (a pack isn't called expired during its final month); manufacture becomes the first day.
  Numeric full dates such as "03/15/2028" are ambiguous (day/month order) and parse to null, so
  the app should ask the user. Python takes full dates only, so this parser is app-side input
  handling, not a change to the rules.
- **No java.time in the engine.** minSdk is 24; `java.time` needs API 26 without desugaring.
  `PackDate` prints dates as `yyyy-MM-dd`, like Python, so reason texts match.
- **Python truthiness kept.** An empty string counts as "not given", the same as `None`, in every
  place Python tests a value with `if x`.
- **Tables are copies.** `assets/fda/*.csv` must equal `data/fda/*.csv`; `RulesRealDataTest`
  fails if they drift. To change data: edit `data/fda/raw/` or the build script, run
  `python scripts/build_fda_tables.py`, copy the two CSVs to `App/src/main/assets/fda/`, then
  run `python rules/tests/make_parity_cases.py` and the tests.
- **Prices are not used.** `med_info.csv` prices look generic-level (different brands share the
  same values), so they stay out of the verdict.

## Test results (2026-10-24)

Run: `./gradlew :app:testDebugUnitTest --tests 'com.mlbb.surecure.rules.*'` (JVM, no emulator).
Python: `pytest -q rules/tests`.

- `SureCureRulesTest`: 21/21 pass (the same 21 tests as `test_rules.py`, which also passes 21/21).
- `RulesRealDataTest`: 10/10 pass. Includes the six cases from the brief: Biogesic + `23651568`
  red, Biogesic + `2365I568` red, Biogesic + `DR-153` + unlisted lot green, Bioflu + `U066300`
  red, Flanax + `261` not red, Biogesic + `DR-XY34482` red.
- `RulesParityTest`: 1,027 cases on the bundled tables (every listed lot plus 700 random
  combinations), identical level, product checked and reasons to Python. Every rule fires in
  the set except the registration-validity date check (no validity dates were scraped; the 21
  scenarios cover it). A deliberate break (fuzzy length 6 to 5) made 4 cases differ, so the
  test does catch changes.
- `PackDateTest`: 6/6 pass.

## Flagged, not decided

- **Rinityn** can get green: the scrape has registration `DRP-6907`, though our data person thought
  it had none, and the first handoff said Rinityn should always be amber. Left as the engine
  decides; `RulesRealDataTest.rinitynWithItsScrapedRegistrationIsGreen` documents it.
- **Lagundi** is mapped from Ascof Forte; needs checking against the Capsure pack.
  **DayZinc**: only DayZincMax (food supplement) was found, so DayZinc has no registration.
- **Products for the manual check.** With no product, the engine skips the lot check. So for the
  29 other brands in `counterfeit_lots.csv` (Alaxan FR, Neozep Forte, ...) to be checkable, the
  "not recognized" product list must offer `FdaTables.knownProducts`, not only the 20 model labels.
  For the UI step.
- **Still missing data:** advisory number and URL per lot (reasons say "an FDA counterfeit
  advisory"), EAN-13 barcodes, registration validity dates.
- **App models.** The app's `ScanSafety { SAFE, UNSAFE }` and "Marked safe/unsafe" text need three
  levels and the wording above (fixed: the dashboard now shows 'No match with FDA records' / 'Flagged: check with FDA'). Not changed here; to be agreed with the app dev.

## Barcode table (2026-10-24)

- `data/fda/barcodes.csv` (source) and `App/src/main/assets/fda/barcodes.csv` (bundled copy; the
  drift test now covers it too): 18 EAN-13 codes for 14 of the 20 products, from online pharmacy
  listings. All pass the check digit, no duplicates, every `product` is a `labels.json` name.
  Collected by hand, not built by `build_fda_tables.py`.
- No engine change was needed: `FdaTablesLoader` already reads `barcodes.csv` when it exists.
  The engine uses only `ean13` and `product`.
- Variant packs map like `registrations.csv` does (Buscopan Plus → Buscopan, Flanax Forte → Flanax,
  Decolgen Forte → Decolgen, Sinecod Forte → Sinecod, Ascof Forte → Lagundi), so a variant's barcode
  and registration don't contradict each other.
- Still without a barcode: Ascozin, Lactezin, Neurogen E, Rinityn, Rogin E, Omeprazole, plus plain
  Buscopan 10 mg and the regular Imodium pack. A valid code that isn't listed gives amber "not in
  the barcode table", never red.
- Barcode first (decision order step 1): `FdaTables.productForBarcode(code)` returns the product for
  a valid, listed code, else null (then run the photo model). Not wired into the screens yet: the
  app has no barcode scanner. The scan-result screen has a typed barcode field (Stage 2), so the barcode
  rules do run on real scans; barcode first waits for a scanner and the team's choice of order.

Tests (`RulesBarcodeTest`, 7/7; expected reasons are the Python engine's output):
Bioflu + `4807788526762` → no barcode reason; Biogesic + `4807788526762` → red "Barcode belongs to
Bioflu, not Biogesic."; valid unknown `4807788000019` → amber "not in the barcode table"; bad check
digit `4807788526763` → amber, never red; Biogesic barcode + Bioflu registration → red; lookup helper.
