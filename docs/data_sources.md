# Data sources

| ID | Dataset | Use | License / terms | Location |
|---|---|---|---|---|
| D1 | Capsure: Pharmaceutical Drugs and Vitamins Dataset V2 (Vencer, 2023), Kaggle `vencerlanz09/pharmaceutical-drugs-and-vitamins-dataset-v2` | Stage 1 training (synthetic) and evaluation (real, split by capture burst) | CC BY 4.0 | Downloaded by the notebook |
| D2 | FDA Philippines tables: registrations, counterfeit lots from advisories, EAN-13 barcodes | Stage 3 rules | Public records; each row keeps its source URL | `rules/sample_data/` (schema + examples) |
| D3 | FDA advisory comparison photos; out-of-scope pack photos | Code-reading and guard tests | Public / team-collected | Not in git |
| D4 | Chain drugstore branches (Mercury, Watsons, Generika, Southstar, TGP), collected 2026-10-19 from each chain's store locator | Map points | Published by the chains | `data/drugstores/ph_drugstores.csv` |
| D5 | FDA Verification Portal drugstore list (27,176 records on 2026-10-19) | Licensed-outlet map (Model B) | Public records; owner names dropped | `scripts/fda_drugstore_export.js` (portal returned 404 on 2026-10-20) |
| D6 | Philippine Dengue Cases and Deaths (DOH, via HDX); PJDSC 2026 catalogue | Model A | See HDX page | To download |
| D7 | PSA 2020 Census population, PSGC codes, 2021 municipal poverty estimates | Joins and features for maps | PSA terms | To download |

## Known limits
- D1 real photos were all taken on one day; each product was shot in its own time window, so lighting alone predicts the product above chance (the notebook reports a lighting-only baseline).
- D4 covers five chains only: Southstar has no coordinates, and TGP's locator is capped at 300 stores.
- No public dataset geolocates counterfeit cases; map models measure exposure, not incidence.

## Drugstore and boundary references (APA 7th)

Faeldon, J. (n.d.). *philippines-json-maps* [Data set]. GitHub. Retrieved October 24, 2026, from https://github.com/faeldon/philippines-json-maps

Generika Drugstore. (n.d.). *Store locator*. Retrieved October 24, 2026, from https://www.generika.com.ph/

Mercury Drug Corporation. (n.d.). *Store locator*. Retrieved October 24, 2026, from https://www.mercurydrug.com/

Philippine Statistics Authority. (2023). *Philippine Standard Geographic Code (PSGC)* [Data set]. https://psa.gov.ph/classification/psgc

Southstar Drug. (n.d.). *Store locator*. Retrieved October 24, 2026, from https://www.southstardrug.com.ph/

The Generics Pharmacy. (n.d.). *Store locator*. Retrieved October 24, 2026, from https://www.tgp.com.ph/

Watsons Philippines. (n.d.). *Store locator*. Retrieved October 24, 2026, from https://www.watsons.com.ph/
