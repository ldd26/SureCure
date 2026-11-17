"""System metrics from a real-pack field test (see docs/field_test.md).

Reads the results sheet (one row per pack scanned) and prints, with 95% Wilson intervals:
- per code field: read correctly, pre-filled wrong, left empty (out of packs where the code is printed);
- expiry: expired packs the app flagged, and non-expired packs it wrongly called expired;
- product identification: shown product equals the real product.

Only rows with photo_ok = yes count; the others are listed as excluded.

Usage: python scripts/field_test_metrics.py data/field_test/results.csv
"""
import csv
import math
import re
import sys

MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """95% Wilson score interval for k successes out of n."""
    if n == 0:
        return (float("nan"), float("nan"))
    p = k / n
    centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return (max(0.0, centre - half), min(1.0, centre + half))


def rate(k: int, n: int) -> str:
    if n == 0:
        return "n/a (0 packs)"
    lo, hi = wilson(k, n)
    return f"{k}/{n} = {100 * k / n:.0f}% (95% CI {100 * lo:.0f} to {100 * hi:.0f})"


def norm_code(s: str) -> str:
    """Upper-case, no spaces, dashes, dots or slashes: how the rules engine compares lots."""
    return "".join(ch for ch in (s or "").upper() if ch.isalnum())


def month_year(s: str):
    """(year, month) of a printed date ("MAR 2028", "03/2028", "03/28", "15 MAR 2028", "2028-03-15"), or None."""
    t = (s or "").strip().upper()
    if not t:
        return None
    m = re.fullmatch(r"(\d{4})-(\d{1,2})(?:-\d{1,2})?", t)
    if m:
        return int(m[1]), int(m[2])
    m = re.fullmatch(r"(?:\d{1,2}[\s./-]*)?([A-Z]{3})[A-Z]*\.?[\s./-]*(\d{4}|\d{2})", t)
    if m and m[1] in MONTHS:
        y = int(m[2])
        return (2000 + y if y < 100 else y), MONTHS.index(m[1]) + 1
    m = re.fullmatch(r"(\d{1,2})[./-](\d{4}|\d{2})", t)
    if m:
        y = int(m[2])
        return (2000 + y if y < 100 else y), int(m[1])
    return None


def yes(v: str) -> bool:
    return (v or "").strip().lower() in {"yes", "y", "1", "true"}


def main(path: str) -> None:
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    ok = [r for r in rows if yes(r.get("photo_ok", ""))]
    excluded = [r.get("pack_id", "?") for r in rows if not yes(r.get("photo_ok", ""))]
    print(f"Packs: {len(rows)} scanned, {len(ok)} counted, {len(excluded)} excluded (photo_ok not yes): {excluded}\n")

    print("Code fields (out of packs where the code is printed)")
    for field, same in [
        ("barcode", lambda a, b: norm_code(a) == norm_code(b)),
        ("lot", lambda a, b: norm_code(a) == norm_code(b)),
        ("reg", lambda a, b: norm_code(a) == norm_code(b)),
        ("expiry", lambda a, b: month_year(a) is not None and month_year(a) == month_year(b)),
    ]:
        printed = [r for r in ok if (r.get(f"printed_{field}") or "").strip()]
        n = len(printed)
        right = sum(1 for r in printed if (r.get(f"read_{field}") or "").strip()
                    and same(r[f"read_{field}"], r[f"printed_{field}"]))
        empty = sum(1 for r in printed if not (r.get(f"read_{field}") or "").strip())
        wrong = n - right - empty
        print(f"  {field:8s} read correctly {rate(right, n)}")
        print(f"  {'':8s} pre-filled wrong {rate(wrong, n)}")
        print(f"  {'':8s} left empty {rate(empty, n)}")

    print("\nExpiry check")
    expired = [r for r in ok if yes(r.get("expired", ""))]
    fresh = [r for r in ok if (r.get("expired") or "").strip() and not yes(r.get("expired", ""))]
    print(f"  expired packs flagged as expired: {rate(sum(yes(r.get('app_said_expired', '')) for r in expired), len(expired))}")
    print(f"  non-expired packs wrongly called expired: {rate(sum(yes(r.get('app_said_expired', '')) for r in fresh), len(fresh))}")

    print("\nProduct identification")
    named = [r for r in ok if (r.get("product") or "").strip()]
    right = sum(1 for r in named if norm_code(r.get("product_shown", "")) == norm_code(r["product"]))
    print(f"  shown product is the real product: {rate(right, len(named))}")

    print("\nVerdicts")
    for level in ["red", "amber", "green"]:
        print(f"  {level}: {sum(1 for r in ok if (r.get('verdict') or '').strip().lower() == level)}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
