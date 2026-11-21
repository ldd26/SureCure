<div align="center">

<img src="docs/media/logo.svg" alt="SureCure logo" width="88" />

# SureCure

### Snap the box. Check the codes. Know before you swallow.

An offline Android screener for counterfeit over-the-counter medicines in the Philippines.

<p>
  <a href="https://github.com/ldd26/SureCure/releases/tag/v1.0.4-test"><img alt="Release" src="https://img.shields.io/github/v/release/ldd26/SureCure?include_prereleases&label=download&color=14AE5C&style=flat" /></a>
  <img alt="Android 7.0+" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white&style=flat" />
  <img alt="Works offline" src="https://img.shields.io/badge/internet-not%20required-02542D?style=flat" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white&style=flat" />
  <img alt="ONNX Runtime" src="https://img.shields.io/badge/ONNX%20Runtime-on--device-005CED?logo=onnx&logoColor=white&style=flat" />
  <img alt="ML Kit" src="https://img.shields.io/badge/ML%20Kit-text%20%2B%20barcode-4285F4?logo=google&logoColor=white&style=flat" />
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat" /></a>
</p>

**[Download](#download)** · **[How it works](#how-it-works)** · **[Results](#results)** · **[Data](#where-the-data-comes-from)** · **[FAQ](#faq)** · **[Build it](#build-it-yourself)**

<img src="docs/media/demo.gif" alt="A full scan on a tablet: front photo, code-panel photo, product pick, codes read from the photo, amber verdict" width="560" />

<sub>A real scan, sped through: front photo → code panel → pick the product → codes read from the photo → verdict.</sub>

</div>

> [!IMPORTANT]
> SureCure **screens**, it doesn't certify. 🟢 Green means *"no match with FDA counterfeit records"*, never "safe" or "authentic". Check anything 🟡 amber or 🔴 red with the FDA and a pharmacist.

## Download

<table>
<tr>
<td align="center" width="200">
<img src="docs/media/download-qr.svg" alt="QR code: download SureCure.apk" width="170" /><br />
<sub>Scan with your phone's camera</sub>
</td>
<td>

**[⬇ Download SureCure.apk](https://github.com/ldd26/SureCure/releases/download/v1.0.4-test/SureCure.apk)** (about 108 MB, Wi-Fi recommended)<br />
Older or Android Go phones: **[SureCure-older-phones.apk](https://github.com/ldd26/SureCure/releases/download/v1.0.4-test/SureCure-older-phones.apk)**

1. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**.
2. If a SureCure built elsewhere is already installed, uninstall it first.
3. Open SureCure and tap the green camera button.

Android 7.0 and newer · no account · works offline. [Release notes and checksums](https://github.com/ldd26/SureCure/releases/tag/v1.0.4-test)

</td>
</tr>
</table>

## The problem

A 2019 United Nations report named the Philippines a hotspot for counterfeit medicines in Southeast Asia (United Nations Office on Drugs and Crime [UNODC], 2019). The FDA keeps warning about fakes of the brands in every household cabinet: Biogesic, Bioflu, Decolgen Forte, Tuseran Forte, Alaxan FR.

Fakes are made to look right, so looking closely doesn't help much. What gives them away is usually printed on the box: a lot number the FDA has flagged, a registration number that belongs to a different product, a barcode that doesn't match. That evidence is buried in hundreds of advisories ordinary buyers never read.

**Our question:** can a regular Android phone recognize a common OTC medicine from a photo, check its printed codes against FDA records offline, and stay honest about what it can't tell?

## What SureCure does

| | |
|---|---|
| 📸 **Two photos** | The front of the pack, then the panel with the codes |
| 🧠 **Recognizes the product** | A small vision model on the phone knows 20 common Philippine OTC products |
| 🙅 **Knows what it doesn't know** | Unfamiliar packs go to a searchable product list instead of a guess |
| 🔎 **Reads the codes for you** | On-device text recognition and barcode scanning fill in the barcode, lot, registration number and dates; you check them and confirm |
| ⏰ **Checks the expiry at once** | "Expired: do not take this medicine", or a warning when 90 days or fewer are left |
| 🚦 **Clear verdict** | 🔴 red, 🟡 amber or 🟢 green, always with the reasons |
| 📍 **Next step** | Amber and red link to FDA verification and reporting, and to nearby established drugstores |
| 🗒️ **Scan history** | Every verdict is kept on the phone in its colour, grouped by month |

## How it works

```mermaid
flowchart LR
    A[📸 Pack photo] --> B{Barcode<br/>in table?}
    B -- yes --> D[User confirms product]
    B -- no --> C[ResNet-18 on the phone]
    C --> G{Known product?<br/>energy score}
    G -- no --> L[Pick from list]
    G -- yes, unsure --> T[Top 3 + 'None of these']
    G -- yes, confident --> D
    L --> D
    T --> D
    D --> E[Codes read from photo, user confirms]
    E --> R{Rules vs FDA tables}
    R --> V1[🔴 Red]
    R --> V2[🟡 Amber]
    R --> V3[🟢 Green]
```

**The photo identifies the product. The codes decide the verdict.** A good fake looks like the real thing, so the image model never calls anything counterfeit.

| Verdict | When |
|---|---|
| 🔴 **Red** | The lot exactly matches an FDA counterfeit advisory for that product, or the codes belong to a different product |
| 🟡 **Amber** | Something can't be verified: registration number blank (without ticking "no registration number") or unknown, unknown barcode, no lot, expired, the lot is one look-alike character (O/0, I/1, S/5…) from a listed lot, or the lot is listed for a different product |
| 🟢 **Green** | No match with FDA counterfeit records |

<details>
<summary><b>The details</b></summary>

<br />

- **Recognition.** A ResNet-18 (He et al., 2016) pretrained on ImageNet (Deng et al., 2009) and trained with AdamW (Loshchilov & Hutter, 2019), exported to ONNX and run with ONNX Runtime on the phone.
- **Synthetic-only training, on purpose.** In Capsure's real photos each product was shot in its own time slot, so lighting alone predicts the product (14.1% against 5% chance). We train on the synthetic composites and keep real photos for choosing the checkpoint, calibrating and testing.
- **No leakage.** Real photos are split by burst (shots taken seconds apart), never by frame.
- **Out-of-scope guard.** An energy score (Liu et al., 2020) with a conformal threshold (Bates et al., 2023), set so about 5% of supported photos get wrongly rejected. A rejected photo goes to the product list, not a dead end.
- **Calibrated confidence.** Temperature scaling sets how sure the model has to be before showing a single answer; below that, it shows its top 3.
- **Reading the codes.** ML Kit Text Recognition v2 and Barcode Scanning, bundled so they work offline. A barcode must pass its check digit, digits get look-alike fixes only where digits are expected, lots are never auto-corrected, and every field must be confirmed by the user.
- **One source of truth.** `meta.json` carries every number the app needs (temperature, thresholds, padding colour). The app reads it instead of hard-coding values.
- **Variants.** Every product variant (Forte, No-Drowse, …) maps to one label, so a genuine variant is never flagged just for being a variant.

</details>

## Results

Measured on real photos never used for training or any tuning decision, with 95% Wilson intervals (Wilson, 1927).

| Product recognition | Result |
|---|---|
| 📦 **Packaging photos** (what the app asks for) | **78.0%** correct (71.5 to 83.3), per burst |
| 🥉 Right product within the top 3 | **89.8%** (84.6 to 93.4) |
| 🟦 Box / blister strip / loose pill | 92.8% / 56.0% / 18.3% |
| ✅ When the app answers on its own | 37.5% of packaging photos, 95.2% of those correct |
| 🙅 Unknown-product guard | AUROC 0.81 on held-out products; wrongly rejects 6.4% of supported photos |
| 💡 Lighting-only baseline (chance 5%) | 14.1% |
| 📱 Phone vs training preprocessing | Same answer on 93.3% of photos |

| Counterfeit check | Result |
|---|---|
| 🔴 FDA-listed lots, entered with the right product | **327 / 327 red** |
| 🟡 Same lots, product unknown or wrong | 327 / 327 amber ("listed for another product") |
| ⚖️ Kotlin rules vs the Python reference | 1,027 cases, identical verdicts and reasons; 23 scenarios |

**In plain terms:** box photos work very well, loose pills don't, so the app asks for the box. When it isn't sure, the right product is almost always in its top 3. Every listed counterfeit lot is caught when the lot is read correctly. Example: Biogesic lot 23651568 from FDA Advisory No. 2025-0507 (Food and Drug Administration Philippines [FDA], 2025) comes back red; the same lot with one look-alike character misread (2365I568) comes back amber with a prompt to check the lot again.

> [!NOTE]
> Not measured yet: how often the whole system flags real counterfeit packs, and OCR read rates on real packs. The field-test protocol is in [`docs/field_test.md`](docs/field_test.md).

## Where the data comes from

| Source | Used for | Size |
|---|---|---|
| Capsure: Pharmaceutical Drugs and Vitamins Dataset V2 (Vencer, 2023) | Training (synthetic composites) and testing (real photos) | 20 products, ~40k synthetic and ~11k real images |
| FDA Philippines registrations | Registration number check | 66 numbers for our brands |
| FDA counterfeit drug advisories, March 2022 to present | Counterfeit lot check | 327 product and lot pairs, 122 for our products |
| Online pharmacy listings and Open Food Facts | Barcode lookup | 18 check-digit-verified EAN-13 codes |
| Chain drugstore store locators | "Find a nearby drugstore" | 4,252 branches checked against 2023 PSGC boundaries; the app ships the 3,651 with exact coordinates (Faeldon, n.d.; Philippine Statistics Authority [PSA], 2023) |
| Medical Pills dataset (Jocher & Rizwan, 2024) | Testing the out-of-scope guard on unfamiliar pills | 115 images |

Scraped files stay untouched in `data/*/raw/`. Every cleaned table and app asset is rebuilt by a script in `scripts/`, and each data folder has a README listing what was fixed and what's still missing.

## What went wrong, and what we did about it

- **Synthetic to real.** 98.5% on synthetic images, much less on real ones. Stronger augmentation and AdaBN (Li et al., 2018) both made it worse, so we dropped them. Fine-tuning on our own photos of three products made the model guess those three too often; a safeguard we built rejected it automatically.
- **A guard that failed.** Our first unknown-product guard, a Mahalanobis score (Lee et al., 2018) fitted on synthetic features, scored worse than chance on outside pill photos. It was measuring image quality, not product identity. We ran a fair comparison of four options with the winner decided before the run; the energy score won. The failed version stays in the notebook as evidence.
- **Messy scraped data.** Duplicate advisories, ten spellings of one brand, placeholder lot numbers, and about 1 in 7 drugstore coordinates missing or in the wrong city. Scripts clean and log all of it and set aside 66 rows for a person to check.
- **Gaps in FDA records.** No registration was found for Myra E, Neurogen E, Rogin E, Omeprazole (generic) or DayZinc. They still get the counterfeit lot check, but top out at amber.
- **The first phone tests.** Photos were never read because of a decoding bug; the first release build crashed at launch because of how the code shrinker (R8) rewrote a library; a portrait lock turned tablet photos sideways. Each was found by testers on real devices, fixed the same day, and now has a test or a note in [`docs/integration_notes.md`](docs/integration_notes.md).

## Where it goes next

- 📊 **Measure the whole system:** scan real and mock packs for end-to-end detection, false-red rate and OCR read rates.
- 📷 **Real photos from many phones:** fine-tune and recalibrate on drugstore-bought packs; crop the pack before classifying.
- 🔄 **Live FDA updates** with the advisory number and link on every red result.
- 🪶 **Smaller app:** a half-precision model and a trimmed ONNX Runtime could save about 50 MB.
- ➕ **More products:** adding one means synthetic images, a few real photos and its FDA rows.

## FAQ

<details>
<summary><b>Does SureCure detect fake medicine?</b></summary>
<br />
It checks the printed codes against FDA counterfeit records. A fake whose lot was never reported, with correct-looking codes, comes back green. That's why green means "no match", never "authentic".
</details>

<details>
<summary><b>Do I have to type the codes?</b></summary>
<br />
Usually not. The app reads the barcode, lot, registration number and dates from the code-panel photo; you check them, tick "I've checked these codes", and type only what it missed. "Show what the phone read" shows the raw text.
</details>

<details>
<summary><b>Why did it show the full product list instead of three choices?</b></summary>
<br />
The out-of-scope guard decided the photo didn't look like one of the 20 trained products, so it doesn't guess. Pick the product from the list; the counterfeit check still runs on the codes. Photos with the pack filling the frame, in good light, are recognized most often.
</details>

<details>
<summary><b>Why amber when everything looks fine?</b></summary>
<br />
Amber means something couldn't be verified, most often a registration number left blank. If the pack prints none, tick "This pack has no registration number" and it isn't counted against the pack. The reasons are always listed under the verdict.
</details>

<details>
<summary><b>Does it need the internet? What leaves my phone?</b></summary>
<br />
No internet needed. Photos and scan results stay on the phone. Google's bundled ML Kit libraries send anonymous usage and diagnostic data.
</details>

## Build it yourself

You'll need Android Studio (recent stable), an Android 7.0+ phone or emulator, and Python 3 to rebuild data or rerun the model. Open the folder in Android Studio, let Gradle sync, press ▶️. The models and tables are bundled, so the app works offline.

```bash
./gradlew :app:assembleRelease            # APKs in App/build/outputs/apk/release/ (one per CPU type)
./gradlew :app:testDebugUnitTest          # rules, parity with Python, OCR extraction, scan flow, preprocessing
./gradlew :app:connectedDebugAndroidTest  # on a device: golden images, history, photo reading
pytest -q rules/tests                     # Python reference rules
python scripts/build_fda_tables.py        # rebuild FDA tables from data/fda/raw
python scripts/build_drugstore_asset.py   # rebuild the app's drugstore list
python scripts/field_test_metrics.py data/field_test/results.csv   # real-pack field test metrics
```

<details>
<summary><b>APK details</b></summary>

<br />

One APK per CPU type: `app-arm64-v8a-release.apk` for almost every current phone (108.4 MB), `app-armeabi-v7a-release.apk` for older or Android Go phones (92.4 MB), `app-x86_64-release.apk` for the emulator. Release builds are signed with the local debug key for testing and demos; Google Play needs a real release key. Code shrinking (R8) is off: a partial R8 setup crashed the app at launch. Uninstall a build from another computer before installing one from yours.

</details>

<details>
<summary><b>Reproduce the model numbers</b></summary>

<br />

Open `ml/surecure_train.ipynb` on Kaggle or Colab with a T4 GPU and attach the Capsure dataset. Full training takes about 2 hours. To re-score our released weights instead, attach them and run `import os; os.environ["SC_EVAL_WEIGHTS"] = "auto"` before the config cell (about 20 minutes). Results land in `surecure_outputs.zip`.

</details>

<details>
<summary><b>Inside the repo</b></summary>

```
App/                     Android app (Kotlin, Jetpack Compose)
├─ src/main/assets/        model (onnx + meta + labels), FDA tables, drugstore list
├─ src/main/java/…/ml/     preprocessing + on-device classifier
├─ src/main/java/…/ocr/    code-panel reading (ML Kit) + extraction
├─ src/main/java/…/rules/  rules engine, FDA table loading, barcode lookup
├─ src/main/java/…/ui/     camera, result, nearby drugstores, dashboard
└─ src/test, androidTest   unit, parity, OCR and golden-image tests
ml/                      training + evaluation notebook and its generator
rules/                   Python reference rules + test scenarios
data/                    FDA and drugstore data (raw + cleaned), field-test sheet
scripts/                 rebuild tables, assets, fixtures; field-test metrics
docs/                    model card, data sources, integration notes, field test
med-images/              team photos used in the fine-tuning experiment
```

**Dependencies.** App: Kotlin, Jetpack Compose, Navigation Compose, CameraX, ONNX Runtime for Android, ML Kit Text Recognition v2 and Barcode Scanning (pinned in `gradle/libs.versions.toml` and `App/build.gradle.kts`). Notebook: PyTorch, torchvision, scikit-learn, SciPy, pandas, Pillow, onnxruntime. Data scripts: pandas, NumPy, matplotlib. Weights, datasets and run outputs stay out of git.

</details>

## How we built it

<p>
  <img alt="Claude" src="https://img.shields.io/badge/Claude-coding%20assistant-D97757?logo=claude&logoColor=white&style=flat" />
  <img alt="Gemini" src="https://img.shields.io/badge/Gemini-read--only%20reviewer-8E75B2?logo=googlegemini&logoColor=white&style=flat" />
  <img alt="Human review" src="https://img.shields.io/badge/every%20change-human%20reviewed-2E8B57?style=flat" />
</p>

This project used **AI-assisted programming with human review the whole way through.** Claude helped draft code, the notebook, the data-cleaning scripts and these docs. Gemini worked as a second pair of eyes that could inspect the code but never change it. Nothing went in without a team member reading it, running it and checking the result.

What the AI couldn't do for us was the **systems thinking**, and that's where most of our time went. SureCure isn't one model; it's a chain: camera → barcode → model → guard → user confirmation → rules → FDA tables → verdict → next step. A weak link anywhere breaks the whole thing, so we designed each piece around the others:

- 🔗 **Contracts between parts.** The model, the app and the notebook share one `meta.json`, and golden test images prove the phone gives the same answer as the training code.
- 🧯 **Failure paths, not just happy paths.** Every stage has a fallback (unknown pack → product list, unsure → top 3, missing code → amber), so a user never hits a dead end.
- ⚖️ **Who decides what.** The photo only identifies the product; the printed codes decide the verdict. That split came from asking how a counterfeiter would beat each part.
- 🔁 **A loop, not a one-off.** Data scripts rebuild every table, tests check every rule against the Python reference, and confirmed scans can feed future training.

The calls that matter were ours: what data to trust, what counts as red, amber or green, and how to read the results. When the AI got something wrong, we fixed it and kept the record, which is why the failed experiments are still in the notebook.

## References

<details>
<summary>Show all references (APA 7th edition)</summary>

<br />

Bates, S., Candès, E., Lei, L., Romano, Y., & Sesia, M. (2023). Testing for outliers with conformal p-values. *The Annals of Statistics, 51*(1), 149–178. https://doi.org/10.1214/22-AOS2244

Deng, J., Dong, W., Socher, R., Li, L.-J., Li, K., & Fei-Fei, L. (2009). ImageNet: A large-scale hierarchical image database. In *2009 IEEE Conference on Computer Vision and Pattern Recognition* (pp. 248–255). IEEE. https://doi.org/10.1109/CVPR.2009.5206848

Faeldon, J. (n.d.). *philippines-json-maps* [Data set]. GitHub. Retrieved October 24, 2026, from https://github.com/faeldon/philippines-json-maps

Food and Drug Administration Philippines. (2025). *FDA Advisory No. 2025-0507: Public health warning against the purchase and use of the counterfeit drug product Paracetamol (Biogesic) 500 mg tablet*. https://www.fda.gov.ph/fda-advisory-no-2025-0507-public-health-warning-against-the-purchase-and-use-of-the-counterfeit-drug-product-paracetamol-biogesic-500-mg-tablet-lot-no-23651568-expiry-date-mar/

He, K., Zhang, X., Ren, S., & Sun, J. (2016). Deep residual learning for image recognition. In *2016 IEEE Conference on Computer Vision and Pattern Recognition* (pp. 770–778). IEEE. https://doi.org/10.1109/CVPR.2016.90

Jocher, G., & Rizwan, M. (2024). *Ultralytics datasets: Medical-pills detection dataset* (Version 1.0.0) [Data set]. Ultralytics. https://docs.ultralytics.com/datasets/detect/medical-pills/

Lee, K., Lee, K., Lee, H., & Shin, J. (2018). A simple unified framework for detecting out-of-distribution samples and adversarial attacks. *Advances in Neural Information Processing Systems, 31*, 7167–7177. https://proceedings.neurips.cc/paper/2018/hash/abdeb6f575ac5c6676b747bca8d09cc2-Abstract.html

Li, Y., Wang, N., Shi, J., Hou, X., & Liu, J. (2018). Adaptive batch normalization for practical domain adaptation. *Pattern Recognition, 80*, 109–117. https://doi.org/10.1016/j.patcog.2018.03.005

Liu, W., Wang, X., Owens, J. D., & Li, Y. (2020). Energy-based out-of-distribution detection. *Advances in Neural Information Processing Systems, 33*, 21464–21475. https://proceedings.neurips.cc/paper/2020/hash/f5496252609c43eb8a3d147ab9b9c006-Abstract.html

Loshchilov, I., & Hutter, F. (2019). *Decoupled weight decay regularization* [Conference paper]. International Conference on Learning Representations, New Orleans, LA, United States. https://openreview.net/forum?id=Bkg6RiCqY7

Philippine Statistics Authority. (2023). *Philippine Standard Geographic Code (PSGC)* [Data set]. https://psa.gov.ph/classification/psgc

United Nations Office on Drugs and Crime. (2019). *Transnational organized crime in Southeast Asia: Evolution, growth and impact*. https://www.unodc.org/roseap/en/2019/07/transnational-organised-crime-southeast-asia-report-launch/story.html

Vencer, L. (2023). *Pharmaceutical drugs and vitamins dataset V2* [Data set]. Kaggle. https://www.kaggle.com/datasets/vencerlanz09/pharmaceutical-drugs-and-vitamins-dataset-v2

Wilson, E. B. (1927). Probable inference, the law of succession, and statistical inference. *Journal of the American Statistical Association, 22*(158), 209–212. https://doi.org/10.1080/01621459.1927.10502953

Store locators of the five chains and the FDA registration and advisory sources are listed in `docs/data_sources.md`. Method references for the notebook are at the end of `ml/surecure_train.ipynb`.

</details>

---

<div align="center"><sub>MIT licensed · SureCure Team · Not affiliated with the Food and Drug Administration Philippines</sub></div>
