"""Consolidated account statement (CAS) reader (AOS-805, D-79): CAMS / KFintech "Detailed" statements via casparser.

The file and its password are used in memory only (D-37). What comes back is the minimum the API needs to build the
ledger and reconcile it (WEALTH-RESEARCH §1.1, §1.5):

- every number is a decimal string; units are signed (+ in, - out), amounts are gross and never negative;
- folio numbers are only in the separate ``identifiers`` block, so the API encrypts them before anything is written;
- the investor's name, email, phone, address and PAN, nominees, advisor codes and transaction descriptions are
  dropped here and never leave this service.

The reader sits behind a small port (``read_cas``) so a second implementation can replace casparser if a layout
change breaks it; unit reconciliation in the API catches a silent mis-read either way.
"""

from __future__ import annotations

import io
import re
from datetime import date, datetime
from decimal import Decimal
from typing import List, Optional

import casparser
import pypdfium2 as pdfium
from casparser.exceptions import CASParseError, IncorrectPasswordError, ParserException
from casparser.parsers import cams_detailed
from casparser_isin import MFISINDb
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel
from pypdfium2._helpers.misc import PdfiumError

from app.models import ParseError

READER_ID = "casparser"
READER_VERSION = casparser.__version__
MAX_PAGES = 500

# casparser's transaction types → the ledger's vocabulary (domain/wealth InvestmentTxnType).
KINDS = {
    "PURCHASE": "buy",
    "PURCHASE_SIP": "sip_buy",
    "REDEMPTION": "sell",
    "SWITCH_IN": "switch_in",
    "SWITCH_OUT": "switch_out",
    "SWITCH_IN_MERGER": "merger_in",
    "SWITCH_OUT_MERGER": "merger_out",
    "DIVIDEND_PAYOUT": "dividend_payout",
    "DIVIDEND_REINVEST": "dividend_reinvest",
    "STT_TAX": "stt",
    "STAMP_DUTY_TAX": "stamp_duty",
    "TDS_TAX": "tds",
    "SEGREGATION": "segregation",
    "GIFT_IN": "transfer_in",
    "GIFT_OUT": "transfer_out",
    "REVERSAL": "reversal",
    "MISC": "misc",
}

_FULL_ISIN = re.compile(r"^INF[0-9A-Z]{8}\d$")
_SAFE_TEXT = re.compile(r"[^A-Za-z0-9 &().,'/+-]")


class _Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class CasTransaction(_Camel):
    date: date
    kind: str  # the ledger vocabulary, or "unknown" (the API blocks a folio with unknown unit movements)
    amount: Optional[str] = None  # gross, >= 0, 2 places
    units: Optional[str] = None  # signed
    nav: Optional[str] = None
    balance: Optional[str] = None  # the statement's running unit balance after this row


class CasScheme(_Camel):
    name: str
    isin: Optional[str] = None
    amfi_code: Optional[str] = None
    scheme_type: Optional[str] = None  # EQUITY / DEBT (tax type from the ISIN database)
    category: Optional[str] = None  # SEBI category, e.g. "Equity Scheme - Flexi Cap Fund"
    plan: Optional[str] = None  # direct / regular, from the scheme name
    opening_units: str
    closing_units: str
    valuation_date: Optional[date] = None
    nav: Optional[str] = None
    value: Optional[str] = None
    cost: Optional[str] = None
    transactions: List[CasTransaction]


class CasFolio(_Camel):
    ref: str  # links to identifiers.folios[].ref
    amc: str
    schemes: List[CasScheme]


class FolioIdentifier(_Camel):
    ref: str
    value: str


class CasIdentifiers(_Camel):
    folios: List[FolioIdentifier]


class CasParseResult(_Camel):
    reader_id: str = READER_ID
    reader_version: str = READER_VERSION
    issuer: str  # cams / kfintech
    statement_from: Optional[date] = None
    statement_to: Optional[date] = None
    folios: List[CasFolio]
    identifiers: CasIdentifiers
    parse_warnings: int = 0  # casparser's own running-balance checks; counts only (its text names schemes)


def _dec(v) -> Optional[str]:
    if v is None:
        return None
    d = Decimal(str(v))
    return format(d, "f")


def _money(v) -> Optional[str]:
    if v is None:
        return None
    return f"{abs(Decimal(str(v))):.2f}"


def _period(text: str) -> Optional[date]:
    try:
        return datetime.strptime(text.strip(), "%d-%b-%Y").date()
    except (ValueError, AttributeError):
        return None


def _plan(name: str) -> Optional[str]:
    low = name.lower()
    if "direct" in low:
        return "direct"
    if "regular" in low:
        return "regular"
    return None


def _clean(text: str, limit: int) -> str:
    """Public names (schemes, AMCs) as plain text: no control or markup characters, bounded length."""
    return " ".join(_SAFE_TEXT.sub("", text or "").split())[:limit]


def _category(isin: Optional[str]) -> Optional[str]:
    """The SEBI category for an ISIN from casparser's bundled ISIN database (local file, no network)."""
    if not isin:
        return None
    with MFISINDb() as db:
        rows = db.run_query("SELECT sebi_category FROM scheme WHERE isin = :isin ORDER BY id DESC LIMIT 1", {"isin": isin})
    if not rows or not rows[0].get("sebi_category"):
        return None
    return _clean(rows[0]["sebi_category"], 80)


_original_isin_search = cams_detailed.isin_search


def _isin_search(scheme_name, rta, rta_code, isin=None):
    """Prefer the ISIN printed in the statement over a fuzzy name match from the database.

    casparser looks schemes up by name first and uses the printed ISIN only as a fallback, and returns no ISIN for
    a scheme its bundled database doesn't know (a fund launched after the database was built). The statement's own
    ISIN is the stronger evidence, so a full printed ISIN wins; the database then only adds the AMFI code and type.
    """
    found = _original_isin_search(scheme_name, rta, rta_code, isin=isin)
    if isin and _FULL_ISIN.match(isin) and found[0] != isin:
        with MFISINDb() as db:
            rows = db.direct_isin_lookup(isin)
        if rows:
            return isin, rows[0].get("amfi_code"), rows[0].get("type")
        return isin, None, None
    return found


# Pinned casparser (requirements.txt): the hook point is checked by tests/test_cas.py.
cams_detailed.isin_search = _isin_search


def read_cas(data: bytes, password: Optional[str]) -> CasParseResult:
    if not data.startswith(b"%PDF-"):
        raise ParseError("NOT_A_PDF", "A consolidated account statement is a PDF file")
    if not password:
        raise ParseError("PASSWORD_REQUIRED", "This statement is password-protected. Enter the password you chose when you requested it")
    try:
        doc = pdfium.PdfDocument(data, password=password)
    except PdfiumError as e:
        if "password" in str(e).lower():
            raise ParseError("PASSWORD_INCORRECT", "That password doesn't open this statement") from None
        raise ParseError("UNREADABLE", "The statement couldn't be read") from None
    try:
        if len(doc) > MAX_PAGES:
            raise ParseError("TOO_MANY_PAGES", f"Statements over {MAX_PAGES} pages aren't supported")
    finally:
        doc.close()
    try:
        cas = casparser.read_cas_pdf(io.BytesIO(data), password)
    except IncorrectPasswordError:
        raise ParseError("PASSWORD_INCORRECT", "That password doesn't open this statement") from None
    except (CASParseError, ParserException):
        raise ParseError("NOT_A_CAS", "This doesn't look like a CAMS or KFintech consolidated account statement") from None
    return _to_result(cas)


def _to_result(cas) -> CasParseResult:
    file_type = getattr(cas, "file_type", None)
    if file_type in ("NSDL", "CDSL"):
        raise ParseError("CAS_DEPOSITORY_UNSUPPORTED", "NSDL/CDSL demat statements aren't supported yet; upload the CAMS/KFintech statement")
    if file_type not in ("CAMS", "KFINTECH"):
        raise ParseError("NOT_A_CAS", "This doesn't look like a CAMS or KFintech consolidated account statement")
    if cas.cas_type != "DETAILED":
        raise ParseError("CAS_SUMMARY_UNSUPPORTED", "Request a Detailed statement (with transactions); a Summary has no history to build from")

    folios: List[CasFolio] = []
    identifiers: List[FolioIdentifier] = []
    for i, folio in enumerate(cas.folios, start=1):
        ref = f"f{i}"
        identifiers.append(FolioIdentifier(ref=ref, value=" ".join(folio.folio.split())[:60]))
        schemes = []
        for s in folio.schemes:
            valuation = s.valuation
            val_date = valuation.date if isinstance(valuation.date, date) else _period(str(valuation.date))
            schemes.append(CasScheme(
                name=_clean(s.scheme, 255) or "Unnamed scheme",
                isin=s.isin if s.isin and _FULL_ISIN.match(s.isin) else None,
                amfi_code=s.amfi if s.amfi and str(s.amfi).isdigit() and len(str(s.amfi)) <= 10 else None,
                scheme_type=s.type if s.type in ("EQUITY", "DEBT") else None,
                category=_category(s.isin),
                plan=_plan(s.scheme),
                opening_units=_dec(s.open) or "0",
                closing_units=_dec(s.close) or "0",
                valuation_date=val_date,
                nav=_dec(valuation.nav) if valuation.nav else None,
                value=_money(valuation.value) if valuation.value is not None else None,
                cost=_money(valuation.cost),
                transactions=[CasTransaction(
                    date=t.date if isinstance(t.date, date) else datetime.fromisoformat(str(t.date)).date(),
                    kind=KINDS.get(str(getattr(t.type, "value", t.type)), "unknown"),
                    amount=_money(t.amount),
                    units=_dec(t.units),
                    nav=_dec(t.nav) if t.nav else None,
                    balance=_dec(t.balance),
                ) for t in s.transactions],
            ))
        folios.append(CasFolio(ref=ref, amc=_clean(folio.amc, 120) or "Unknown fund house", schemes=schemes))
    period = cas.statement_period
    return CasParseResult(
        issuer="cams" if file_type == "CAMS" else "kfintech",
        statement_from=_period(period.from_),
        statement_to=_period(period.to),
        folios=folios,
        identifiers=CasIdentifiers(folios=identifiers),
        parse_warnings=len(cas.parse_warnings),
    )
