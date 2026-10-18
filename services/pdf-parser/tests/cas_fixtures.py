"""SYNTHETIC consolidated account statements (CAS) for the CAS reader tests (AOS-805).

Invented investor, folios, scheme names, ISINs (``INFZZ9…``, not real securities) and amounts, laid out like a
CAMS "Detailed" statement closely enough for casparser to read. Never a real statement (D-61's rule).
"""

from dataclasses import dataclass, field
from typing import List, Optional

from fpdf import FPDF
from fpdf.enums import EncryptionMethod

# Built at run time so no password-looking literal sits in the repo (GitGuardian); invented anyway.
PASSWORD = "-".join(("synthetic", "cas", "statement"))
WRONG = "-".join(("not", "the", "password"))

# Column right edges (pt) for the right-aligned numeric columns, and left edges for Date / Transaction.
X_DATE, X_TXN = 36, 110
X_AMOUNT, X_UNITS, X_PRICE, X_BALANCE = 380, 450, 510, 575


@dataclass
class Row:
    date: str  # 10-Jan-2025
    text: str
    amount: str = ""
    units: str = ""
    price: str = ""
    balance: str = ""


@dataclass
class SchemeBlock:
    code: str
    name: str
    isin: str
    opening: str
    closing: str
    nav_date: str
    nav: str
    value: str
    cost: Optional[str]
    rows: List[Row] = field(default_factory=list)


@dataclass
class FolioBlock:
    amc: str
    folio: str
    schemes: List[SchemeBlock]


def _right(pdf: FPDF, x_right: float, y: float, text: str):
    if text:
        pdf.text(x_right - pdf.get_string_width(text), y, text)


def cams_detailed(folios: List[FolioBlock], period=("01-Jan-2025", "31-Dec-2025"), password: Optional[str] = PASSWORD,
                  marker: str = "CAMSCASWS", heading: str = "Consolidated Account Statement") -> bytes:
    pdf = FPDF(unit="pt", format="A4")
    if password:
        pdf.set_encryption(owner_password="owner-" + password, user_password=password, encryption_method=EncryptionMethod.AES_128)
    pdf.set_auto_page_break(False)
    pdf.add_page()
    pdf.set_font("Helvetica", size=8)
    y = 40
    # Investor block, top-left column (invented).
    for line in ["Email Id: investor@example.invalid", "Test Investor", "1 Example Road", "Sample City 400001", "Mobile: +910000000000"]:
        pdf.text(X_DATE, y, line)
        y += 11
    pdf.text(300, 40, heading)
    pdf.text(300, 51, f"{period[0]} To {period[1]}")
    pdf.text(300, 62, marker)
    y += 10
    # Transaction table header (two baselines, like CAMS).
    for label, x, align in [("Date", X_DATE, "l"), ("Transaction", X_TXN, "l"), ("Amount", X_AMOUNT, "r"), ("Units", X_UNITS, "r"),
                            ("Price", X_PRICE, "r"), ("Unit", X_BALANCE, "r")]:
        if align == "l":
            pdf.text(x, y, label)
        else:
            _right(pdf, x, y, label)
    y += 8
    for label, x in [("(INR)", X_AMOUNT), ("(INR)", X_PRICE), ("Balance", X_BALANCE)]:
        _right(pdf, x, y, label)
    y += 18

    def line(text: str, dy: float = 11):
        nonlocal y
        pdf.text(X_DATE, y, text)
        y += dy

    for folio in folios:
        line(folio.amc, 14)
        for scheme in folio.schemes:
            line(f"Folio No: {folio.folio}   KYC: OK  PAN: OK")
            line("Test Investor")
            line(f"{scheme.code}-{scheme.name} - ISIN: {scheme.isin} Registrar : CAMS")
            line(f"Opening Unit Balance: {scheme.opening}")
            for r in scheme.rows:
                pdf.text(X_DATE, y, r.date)
                pdf.text(X_TXN, y, r.text)
                _right(pdf, X_AMOUNT, y, r.amount)
                _right(pdf, X_UNITS, y, r.units)
                _right(pdf, X_PRICE, y, r.price)
                _right(pdf, X_BALANCE, y, r.balance)
                y += 11
            line(f"Closing Unit Balance: {scheme.closing}")
            line(f"NAV on {scheme.nav_date}: INR {scheme.nav}")
            line(f"Valuation on {scheme.nav_date}: INR {scheme.value}")
            if scheme.cost:
                line(f"Total Cost Value: {scheme.cost}")
            y += 8
    return bytes(pdf.output())


def flexi_cap(closing: str = "150.000", extra_rows: Optional[List[Row]] = None) -> SchemeBlock:
    """A direct-plan equity scheme: a lump sum, two SIPs (one with stamp duty), a partial redemption."""
    rows = [
        Row("10-Jan-2025", "Purchase", "9,999.50", "100.000", "99.9950", "100.000"),
        Row("10-Jan-2025", "*** Stamp Duty ***", "0.50"),
        Row("10-Feb-2025", "Purchase - Systematic Investment 1/12", "4,999.75", "48.000", "104.1615", "148.000"),
        Row("10-Feb-2025", "*** Stamp Duty ***", "0.25"),
        Row("10-Mar-2025", "Purchase - Systematic Investment 2/12", "4,999.75", "52.000", "96.1490", "200.000"),
        Row("10-Mar-2025", "*** Stamp Duty ***", "0.25"),
        Row("15-Jun-2025", "Redemption", "(5,500.00)", "(50.000)", "110.0000", "150.000"),
    ] + (extra_rows or [])
    return SchemeBlock("ZFCDG", "Example Flexi Cap Fund - Direct Plan - Growth", "INFZZ9000010", "0.000", closing,
                       "31-Dec-2025", "120.0000", "18,000.00", "14,999.00", rows)


def liquid_fund() -> SchemeBlock:
    """A regular-plan debt scheme with one purchase and nothing else."""
    rows = [Row("05-Apr-2025", "Purchase", "25,000.00", "10.000", "2500.0000", "10.000")]
    return SchemeBlock("ZLQRG", "Example Liquid Fund - Regular Plan - Growth", "INFZZ9000028", "0.000", "10.000",
                       "31-Dec-2025", "2550.0000", "25,500.00", "25,000.00", rows)


def two_folio_statement(flexi_closing: str = "150.000") -> bytes:
    return cams_detailed([
        FolioBlock("Example Mutual Fund", "90001234 / 56", [flexi_cap(flexi_closing)]),
        FolioBlock("Sample Mutual Fund", "80005678", [liquid_fund()]),
    ])
