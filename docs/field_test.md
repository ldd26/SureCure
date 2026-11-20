# Field test: real packs on real phones

The first test of the whole system outside the emulator and the test suite. It gives the system
metrics for the presentation (code read rates, expiry check, product identification) and real
OCR text for `CodePanelTextTest`. Planned for 2026-11-18.

## What you need

- 10 to 20 packs bought from drugstores. Mix boxes and blister strips, the 20 supported products and
  a few others, and if possible some packs that are expired or within 3 months of expiry.
- One or more Android phones. Build with `./gradlew :app:assembleRelease` and install
  `app-arm64-v8a-release.apk` (most phones) or `app-armeabi-v7a-release.apk` (older or Android Go
  phones) from `App/build/outputs/apk/release/`. The release build (about 108 MB) is the one shared with testers and
  runs smoother than the debug one. If an older SureCure is installed from another computer,
  uninstall it first (each computer signs with its own debug key).
- A copy of `data/field_test/results_template.csv` saved as `data/field_test/results.csv`
  (a spreadsheet is fine; keep the column names).

## For each pack

1. Write the codes **as printed on the pack** into the `printed_*` columns before scanning: barcode
   (13 digits under the bars), lot, registration number, expiry. Leave a column blank if the code
   isn't printed. Set `expired` to yes or no from the printed expiry and today's date.
2. Scan the pack in the app: answer the barcode question, take the front photo and the code-panel
   photo. Set `photo_ok` to no if a photo is blurred or cut off (it is then left out of the
   numbers, so retake it as a new row).
3. At the barcode or code step, before editing anything, copy what the app pre-filled into the
   `read_*` columns (blank if a field was empty). Set `app_said_expired` to yes if the code step
   or the verdict said the pack is expired.
4. Tap **Show what the phone read**, then **Copy this text**, and paste it into a text file named
   after the `pack_id` (for example `data/field_test/ocr/P01.txt`). Save the code-panel photo too
   if you can. These become test cases.
5. Record the product the app showed (`product_shown`) and the verdict colour (`verdict`).
6. Note anything odd in `notes`: wrong orientation, slow reading, crashes, wording that confused you.
   If the app says "The photo couldn't be read (...)", copy the text in brackets into `notes`.

Photograph the **real code panel** (the side or flap with the barcode, lot and expiry), filling
most of the frame. A barcode that covers only a small part of the photo, or one shown on a screen,
may not be found. A photo of text typed on another phone's screen tests the screen, not a pack:
screens add glare and moiré, and typed text has no "LOT" / "EXP" labels.

Don't correct the app's pre-filled values before writing them down: the point is to measure them.

## Afterwards

```bash
python scripts/field_test_metrics.py data/field_test/results.csv
```

prints each rate with its sample size and a 95% Wilson interval, the same way as
`docs/model_card.md`. With 10 to 20 packs the intervals are wide; report them, not just the
percentage.

## Privacy

Photos of packs contain no personal data, but check that no receipts, names or addresses are in
frame before committing photos. Commit the CSV and OCR text files; keep large photo sets out of
the repo unless the team agrees.
