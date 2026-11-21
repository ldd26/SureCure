"""Scenario suite for Stage 3 (methodology, Evaluation table: 'Verification rules', target 100%).

Run:  pytest -q rules/tests
The tables below are TEST FIXTURES with made-up registration numbers and barcodes, except the
Biogesic lot 23651568 (expiry MAR 2028), which is named in FDA Advisory No. 2025-0507.
"""
import sys
from datetime import date
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from surecure_rules import (AMBER, GREEN, RED, CounterfeitLot, FdaTables, Registration,  # noqa: E402
                            ScanInput, ean13_valid, edit_distance, normalize_lot, verify)

TODAY = date(2026, 10, 20)


def ean(first12: str) -> str:
    """Append the correct EAN-13 check digit (for building fixtures)."""
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(first12))
    return first12 + str((10 - total % 10) % 10)


BIO_EAN, TEMPRA_EAN, UNKNOWN_EAN = ean("480000000001"), ean("480000000002"), ean("480000000099")


@pytest.fixture
def tables():
    return FdaTables(
        registrations={
            "DR-TEST-0001": Registration("DR-TEST-0001", "Biogesic", date(2020, 1, 1), date(2030, 1, 1)),
            "DR-TEST-0002": Registration("DR-TEST-0002", "Tempra", date(2020, 1, 1), date(2030, 1, 1)),
            "DR-TEST-0003": Registration("DR-TEST-0003", "Bioflu", date(2015, 1, 1), date(2019, 12, 31)),
        },
        barcodes={BIO_EAN: "Biogesic", TEMPRA_EAN: "Tempra"},
        counterfeit_lots=[CounterfeitLot("Biogesic", "23651568", "MAR 2028", "2025-0507")],
    )


def genuine(**kw):
    base = dict(stage1_product="Biogesic", barcode=BIO_EAN, lot="24A0912", reg_no="DR-TEST-0001",
                mfg_date=date(2025, 3, 1), exp_date=date(2028, 3, 1))
    base.update(kw)
    return ScanInput(**base)


# --- helpers
def test_ean13_check_digit():
    assert ean13_valid(BIO_EAN)
    assert not ean13_valid(BIO_EAN[:-1] + str((int(BIO_EAN[-1]) + 1) % 10))
    assert not ean13_valid("12345")


def test_lot_normalisation_and_distance():
    assert normalize_lot(" 2365-1568 ") == "23651568"
    assert edit_distance("23651568", "23651563") == 1


# --- scenarios
def test_genuine_is_green(tables):
    v = verify(genuine(), tables, TODAY)
    assert v.level == GREEN and "No match" in v.message


def test_listed_counterfeit_lot_is_red(tables):
    v = verify(genuine(lot="23651568"), tables, TODAY)
    assert v.level == RED and "2025-0507" in v.reasons[0]


def test_lot_printed_with_separators_still_matches(tables):
    assert verify(genuine(lot="2365-1568"), tables, TODAY).level == RED


def test_lookalike_lot_variant_is_amber(tables):
    # I for 1: a misread of the listed lot, so the user is asked to check it again.
    v = verify(genuine(lot="2365I568"), tables, TODAY)
    assert v.level == AMBER and "one look-alike character" in v.reasons[0]


def test_neighbouring_lot_is_not_flagged(tables):
    # Lots are sequential: a genuine neighbour of a listed lot must not be called counterfeit.
    assert verify(genuine(lot="23651563"), tables, TODAY).level == GREEN
    assert verify(genuine(lot="2365156"), tables, TODAY).level == GREEN


def test_counterfeit_lot_of_other_product_is_amber(tables):
    # The lot is listed, but for Biogesic: the confirmed product may be wrong.
    v = verify(genuine(stage1_product="Tempra", barcode=TEMPRA_EAN, reg_no="DR-TEST-0002", lot="23651568"),
               tables, TODAY)
    assert v.level == AMBER and "listed as counterfeit for Biogesic" in v.reasons[0]


def test_listed_lot_with_unidentified_product_names_the_product(tables):
    v = verify(ScanInput(stage1_product=None, lot="23651568"), tables, TODAY)
    assert v.level == AMBER and any("listed as counterfeit for Biogesic" in r for r in v.reasons)


def test_registration_of_other_product_is_red(tables):
    assert verify(genuine(reg_no="DR-TEST-0002"), tables, TODAY).level == RED


def test_barcode_of_other_product_is_red(tables):
    assert verify(genuine(barcode=TEMPRA_EAN), tables, TODAY).level == RED


def test_expired_is_amber(tables):
    v = verify(genuine(exp_date=date(2026, 1, 1)), tables, TODAY)
    assert v.level == AMBER and any("Expired" in r for r in v.reasons)


def test_unknown_registration_is_amber(tables):
    assert verify(genuine(reg_no="DR-NOT-IN-TABLE"), tables, TODAY).level == AMBER


def test_unknown_barcode_is_amber(tables):
    assert verify(genuine(barcode=UNKNOWN_EAN), tables, TODAY).level == AMBER


def test_bad_check_digit_is_amber_not_red(tables):
    bad = BIO_EAN[:-1] + str((int(BIO_EAN[-1]) + 1) % 10)
    assert verify(genuine(barcode=bad), tables, TODAY).level == AMBER


def test_registration_lapsed_before_manufacture_is_amber(tables):
    v = verify(ScanInput(stage1_product="Bioflu", reg_no="DR-TEST-0003", lot="X1",
                         mfg_date=date(2024, 5, 1), exp_date=date(2027, 5, 1)), tables, TODAY)
    assert v.level == AMBER


def test_missing_lot_is_amber(tables):
    assert verify(genuine(lot=None), tables, TODAY).level == AMBER


def test_unidentified_product_without_codes_is_amber(tables):
    v = verify(ScanInput(lot="23651568"), tables, TODAY)
    assert v.level == AMBER


def test_codes_identify_product_when_photo_guard_fired(tables):
    # Out-of-scope guard fired (stage1_product None) but the registration names Biogesic: the lot check still runs.
    v = verify(genuine(stage1_product=None, lot="23651568"), tables, TODAY)
    assert v.level == RED and v.product_checked == "Biogesic"


def test_red_outranks_amber_and_all_reasons_listed(tables):
    v = verify(genuine(lot="23651568", exp_date=date(2026, 1, 1)), tables, TODAY)
    assert v.level == RED and len(v.reasons) >= 2


def test_short_lot_matches_only_exactly(tables):
    tables.counterfeit_lots.append(CounterfeitLot("Flanax", "260", "05/2027", ""))
    assert verify(ScanInput("Flanax", lot="260"), tables, TODAY).level == RED
    v = verify(ScanInput("Flanax", lot="261", reg_no=None), tables, TODAY)
    assert v.level != RED


def test_registration_without_validity_dates_skips_date_check(tables):
    tables.registrations["DR-XY34482"] = Registration("DR-XY34482", "Bioflu")
    v = verify(ScanInput("Bioflu", reg_no="DR-XY34482", lot="A1", mfg_date=date(2010, 1, 1)), tables, TODAY)
    assert not any("not valid" in r for r in v.reasons)


def test_counterfeit_lot_without_advisory_number_still_red(tables):
    tables.counterfeit_lots.append(CounterfeitLot("Bioflu", "U066300", "10/2023", ""))
    v = verify(ScanInput("Bioflu", lot="U066300"), tables, TODAY)
    assert v.level == RED and "an FDA counterfeit advisory" in v.reasons[0]


# --- "This pack has no registration number" (the user ticked the box)
def test_no_reg_printed_genuine_is_green_with_note(tables):
    v = verify(genuine(reg_no=None, no_reg_printed=True), tables, TODAY)
    assert v.level == GREEN
    assert v.reasons == ["All checks passed.", "No registration number is printed on this pack, so it was not checked."]


def test_no_reg_printed_listed_lot_still_red(tables):
    v = verify(genuine(reg_no=None, no_reg_printed=True, lot="23651568"), tables, TODAY)
    assert v.level == RED


def test_no_reg_printed_without_lot_is_amber_for_the_lot_only(tables):
    v = verify(genuine(reg_no=None, no_reg_printed=True, lot=None), tables, TODAY)
    assert v.level == AMBER
    assert "No lot number entered, so the counterfeit lot check could not run." in v.reasons
    assert "No registration number entered." not in v.reasons


def test_registration_of_other_product_still_red_with_box_unticked(tables):
    v = verify(genuine(reg_no="DR-TEST-0002"), tables, TODAY)
    assert v.level == RED and "belongs to Tempra" in v.reasons[0]


def test_blank_registration_without_the_box_is_still_amber(tables):
    v = verify(genuine(reg_no=None), tables, TODAY)
    assert v.level == AMBER and v.reasons == ["No registration number entered."]


def test_registration_for_product_without_any_on_file_is_not_amber(tables):
    # Flanax has no registration in the fixture table: a typed number can't be checked, so no amber.
    v = verify(genuine(stage1_product="Flanax", barcode=None, reg_no="DR-XY99999"), tables, TODAY)
    assert v.level == GREEN
    assert v.reasons == ["All checks passed.", "We have no FDA registration record for this product, so the registration number was not checked."]
    # Biogesic has registrations: an unknown number is still amber.
    assert verify(genuine(reg_no="DR-XY99999"), tables, TODAY).level == AMBER
