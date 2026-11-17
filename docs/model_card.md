# Model card: SureCure product identification (Stage 1)

Fill from `metrics.json`, `calibration.json` and `guard_eval.json` of the frozen run.
(Filled 2026-10-24 from the eval-only run `run_20261023_2238`, version 7 weights, plus `guard_compare.json`.)

| Item | Value |
|---|---|
| Run name | Weights: Kaggle version 7 (`best.pt`, 10 epochs, LR 3e-4, WD 1e-4). Evaluation: `run_20261023_2238` (eval-only) |
| Bundled package | `run_20261024_0546` (`run_name` in `App/src/main/assets/model/meta.json`); not confirmed here to be the same weights as the evaluation run above |
| Architecture | ResNet-18, ImageNet-pretrained, 20 classes |
| Training data | Capsure synthetic composites only (36,000 train / 4,000 validation) |
| real_test top-1 (frame / burst, Wilson 95% CI) | 48.4% / 54.6% (48.9 to 60.2), 295 bursts |
| Packaging photos only (box + blister), burst, mean-probability vote | 78.0% (71.5 to 83.3), top-3 89.8% (84.6 to 93.4), 186 bursts |
| Box / blister / loose pill (burst) | 92.8% / 56.0% / 18.3% |
| Macro-F1 | 0.498 |
| Lighting-only baseline (burst) | 14.1% (11.0 to 18.0), chance 5% |
| Synthetic validation | 98.5% |
| Temperature / confidence threshold | 1.11 / 0.704 (accepts 37.5% of packaging photos at 95.2% accuracy) |
| Guard verdict, AUROC per source | Shipped guard: **energy score**. Class-holdout AUROC 0.811 (folds 0.81, 0.76, 0.83, 0.84). External case study `ultralytics/medical-pills` (115 frames, one video): AUROC 0.903, 61% caught at the threshold. Rejected alternatives: Mahalanobis on synthetic features (holdout 0.786, external 0.336, verdict NOT EFFECTIVE), max-softmax (0.763), Mahalanobis on real photos (0.604) |
| False rejection at threshold (Wilson 95% CI) | Energy: 6.4% (4.2 to 9.8), α = 0.05, n_cal = 80 |
| Known limits | Synthetic-to-real gap; single-day real photos; guard calibrated on Capsure-like photos only |

## Additional known limits

- All real Capsure photos come from one day and one photographer, and each product was shot in its own time window. Accuracy on other phones and lighting may be lower.
- Loose pills are close to unidentifiable (18.3%); the app asks for the box or strip.
- Weakest products on packaging photos: Bonamine 43%, Midol 56%, Lagundi 60% (7 to 10 bursts each, wide intervals).
- The class-holdout AUROC is optimistic: the network was trained on the hidden products. The external test is one scene. The 0.90 target is not met.
- The guard is not a counterfeit detector. A fake pack of a supported product looks like that product by design; the verdict comes from the code checks.
- The app pipeline (direct resize to 224) agrees with the notebook pipeline on 93.3% of test frames for top-1.

## Intended use

Product identification to pre-fill the product in a counterfeit screening flow. The user confirms the product. The red/amber/green verdict is decided by the rules engine from printed codes and FDA records, never by this model alone.
