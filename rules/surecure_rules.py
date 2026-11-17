"""SureCure Stage 3: verification rules (reference implementation).

Turns the codes a user confirmed on a pack (barcode, lot, dates, registration number) and the
Stage 1 product guess into a red / amber / green verdict, using the FDA reference tables (D2).

This file is the specification for the Kotlin app: the app must give the same verdict as
`verify()` for every case in tests/test_rules.py.

A green verdict means "no match with FDA counterfeit records", never "authentic".
"""
from __future__ import annotations

import csv
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Optional

RED, AMBER, GREEN = "red", "amber", "green"
_RANK = {GREEN: 0, AMBER: 1, RED: 2}
# Lots shorter than this only match exactly. A look-alike slip on "015" or "260" would match far too many packs.
MIN_FUZZY_LOT_LEN = 6


# --------------------------------------------------------------------------- helpers
def ean13_valid(code: str) -> bool:
    """True if `code` is 13 digits with a correct EAN-13 check digit."""
    if not (isinstance(code, str) and len(code) == 13 and code.isdigit()):
        return False
    digits = [int(c) for c in code]
    total = sum(d * (3 if i % 2 else 1) for i, d in enumerate(digits[:12]))
    return (10 - total % 10) % 10 == digits[12]


def normalize_lot(lot: str) -> str:
    """Upper-case and drop spaces, dashes, dots and slashes, which packs print inconsistently."""
    return "".join(ch for ch in (lot or "").upper() if ch.isalnum())


def edit_distance(a: str, b: str) -> int:
    """Levenshtein distance (insertions, deletions, substitutions)."""
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


# Characters that are misread or mistyped for each other on a printed lot.
LOOKALIKE_PAIRS = frozenset(frozenset(p) for p in ("0O", "1I", "1L", "5S", "8B", "2Z", "6G"))


def lookalike_slip(a: str, b: str) -> bool:
    """True if `a` and `b` have the same length and differ in exactly one look-alike character."""
    if len(a) != len(b):
        return False
    diffs = [(x, y) for x, y in zip(a, b) if x != y]
    return len(diffs) == 1 and frozenset(diffs[0]) in LOOKALIKE_PAIRS


# --------------------------------------------------------------------------- data
@dataclass
class Registration:
    reg_no: str
    product: str
    valid_from: Optional[date] = None   # unknown in the scraped table; the date check is skipped when missing
    valid_to: Optional[date] = None


@dataclass
class CounterfeitLot:
    product: str
    lot: str
    expiry: str
    advisory: str


@dataclass
class FdaTables:
    registrations: dict[str, Registration] = field(default_factory=dict)   # reg_no -> record
    barcodes: dict[str, str] = field(default_factory=dict)                 # EAN-13 -> product
    counterfeit_lots: list[CounterfeitLot] = field(default_factory=list)

    @classmethod
    def from_csv(cls, folder: str | Path) -> "FdaTables":
        folder = Path(folder)
        t = cls()
        with open(folder / "registrations.csv", newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                vf, vt = (r.get("valid_from") or "").strip(), (r.get("valid_to") or "").strip()
                t.registrations[r["reg_no"].strip().upper()] = Registration(
                    r["reg_no"].strip().upper(), r["product"].strip(),
                    date.fromisoformat(vf) if vf else None, date.fromisoformat(vt) if vt else None)
        with open(folder / "barcodes.csv", newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                t.barcodes[r["ean13"].strip()] = r["product"].strip()
        with open(folder / "counterfeit_lots.csv", newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                t.counterfeit_lots.append(CounterfeitLot(r["product"].strip(), r["lot"].strip(),
                                                         (r.get("expiry") or "").strip(),
                                                         (r.get("advisory") or "").strip()))
        return t


@dataclass
class ScanInput:
    """What the app knows after Stages 1 and 2 (text fields already confirmed by the user)."""
    stage1_product: Optional[str] = None      # None if the out-of-scope guard fired and no product was picked
    barcode: Optional[str] = None
    lot: Optional[str] = None
    reg_no: Optional[str] = None
    mfg_date: Optional[date] = None
    exp_date: Optional[date] = None


@dataclass
class Verdict:
    level: str
    reasons: list[str]          # every rule that fired, most severe first
    product_checked: Optional[str]

    @property
    def message(self) -> str:
        return {
            RED: "Matches an FDA counterfeit record or has mismatched codes. Do not use; report to FDA.",
            AMBER: "Could not be fully checked. Verify on the FDA Verification Portal before use.",
            GREEN: "No match with FDA counterfeit records.",
        }[self.level]


# --------------------------------------------------------------------------- rules
def verify(scan: ScanInput, tables: FdaTables, today: Optional[date] = None) -> Verdict:
    today = today or date.today()
    hits: list[tuple[str, str]] = []                     # (level, reason)

    def hit(level: str, reason: str) -> None:
        hits.append((level, reason))

    # Which product do the codes point to?
    reg = tables.registrations.get((scan.reg_no or "").strip().upper()) if scan.reg_no else None
    bc_product = None
    if scan.barcode:
        if not ean13_valid(scan.barcode):
            hit(AMBER, "Barcode could not be read reliably (EAN-13 check digit fails).")
        else:
            bc_product = tables.barcodes.get(scan.barcode)

    code_product = reg.product if reg else bc_product
    product = code_product or scan.stage1_product      # the product all checks refer to

    # Red: codes disagree with each other
    if reg and bc_product and reg.product != bc_product:
        hit(RED, f"Barcode belongs to {bc_product} but registration {reg.reg_no} belongs to {reg.product}.")

    # Red: the codes belong to a different product than the one the user is holding (Stage 1 / picked)
    if scan.stage1_product and reg and reg.product != scan.stage1_product:
        hit(RED, f"Registration {reg.reg_no} belongs to {reg.product}, not {scan.stage1_product}.")
    elif scan.stage1_product and bc_product and bc_product != scan.stage1_product:
        hit(RED, f"Barcode belongs to {bc_product}, not {scan.stage1_product}.")

    # Lot listed in an FDA counterfeit advisory.
    #   Red: exact match for this product.
    #   Amber: one look-alike character (O/0, I/1, ...) from a listed lot of this product. Lots are
    #          sequential, so any other one-character difference is usually a genuine neighbour.
    #   Amber: exact match for a different product (the confirmed product may be wrong).
    if scan.lot:
        lot = normalize_lot(scan.lot)
        for c in tables.counterfeit_lots:
            listed = normalize_lot(c.lot)
            src = f"FDA Advisory {c.advisory}" if c.advisory else "an FDA counterfeit advisory"
            if product and c.product == product:
                if lot == listed:
                    hit(RED, f"Lot {scan.lot} is listed as counterfeit in {src}.")
                elif min(len(lot), len(listed)) >= MIN_FUZZY_LOT_LEN and lookalike_slip(lot, listed):
                    hit(AMBER, f"Lot {scan.lot} differs from counterfeit lot {c.lot} by one look-alike "
                               f"character ({src}). Check the lot number again.")
            elif lot and lot == listed:
                hit(AMBER, f"Lot {scan.lot} is listed as counterfeit for {c.product} in {src}. "
                           f"Check that the product is right.")

    # Amber: missing or unknown codes
    if not scan.reg_no:
        hit(AMBER, "No registration number entered.")
    elif reg is None:
        hit(AMBER, f"Registration number {scan.reg_no} is not in the FDA table.")
    if scan.barcode and ean13_valid(scan.barcode) and bc_product is None:
        hit(AMBER, f"Barcode {scan.barcode} is not in the barcode table.")
    if not scan.lot:
        hit(AMBER, "No lot number entered, so the counterfeit lot check could not run.")
    if product is None:
        hit(AMBER, "Product could not be identified from the photo or the codes.")

    # Amber: dates
    if reg and scan.mfg_date and reg.valid_from and reg.valid_to and not (reg.valid_from <= scan.mfg_date <= reg.valid_to):
        hit(AMBER, f"Manufactured {scan.mfg_date} while registration {reg.reg_no} was not valid "
                   f"({reg.valid_from} to {reg.valid_to}).")
    if scan.exp_date and scan.exp_date < today:
        hit(AMBER, f"Expired on {scan.exp_date}.")
    if scan.mfg_date and scan.exp_date and scan.exp_date <= scan.mfg_date:
        hit(AMBER, "Expiry date is not after the manufacturing date.")

    if not hits:
        return Verdict(GREEN, ["All checks passed."], product)
    hits.sort(key=lambda h: -_RANK[h[0]])
    level = hits[0][0]
    return Verdict(level, [r for _, r in hits], product)
