"""Field proposals from a document's text, by deterministic rules (AOS-717). Proposals, never facts: the owner confirms.

Each proposal carries `evidence`, an exact substring of the text it was read from, so a reader (the API, the owner) can
check it. For a number or an issuer the value *is* the evidence; for a date or an amount the value is the evidence
normalised (ISO date, decimal). A rule that isn't sure proposes nothing: a missing field costs a tap, a wrong expiry
date costs a missed renewal.

Fields:
- `expiresOn`: the date after an expiry cue ("Valid till", "Date of Expiry", "Expiry Date"). With a policy period
  ("Period of Insurance … 01/04/2026 to 31/03/2027"), it is the period's later date. Dates are day-first, as in India.
- `number`: the policy, certificate, passport, licence, PAN, Aadhaar, registration or document number after its label.
- `issuer`: a known issuer (insurers, government departments), else "… Insurance Company Limited" on the page.
- `amount`: the premium or fee paid (never the sum insured or the IDV).
"""

import bisect
import re
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Iterator, List, Optional, Tuple

from app.issuers import ISSUERS

FIELDS = ("expiresOn", "number", "issuer", "amount")


@dataclass(frozen=True)
class Proposal:
    field: str
    value: str
    evidence: str
    label: str
    rule: str

    def to_wire(self) -> dict:
        return {"field": self.field, "value": self.value, "evidence": self.evidence, "label": self.label, "rule": self.rule}


# ── Dates ──
_MONTH = (r"(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?"
          r"|nov(?:ember)?|dec(?:ember)?)\b\.?")
_MONTHS = {"jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6, "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12}
_DATE_PATTERNS = [
    # 31/03/2027, 31-03-2027, 31.03.2027 (day first), with the spaces OCR sometimes adds around a separator
    ("dmy", re.compile(r"(?<![\d/.-])(\d{1,2}) ?([./-]) ?(\d{1,2}) ?\2 ?((?:19|20|21)\d{2})(?![\d/-]|\.\d)")),
    ("iso", re.compile(r"(?<![\d/.-])((?:19|20|21)\d{2})-(\d{2})-(\d{2})(?![\d/-]|\.\d)")),
    # 31 Mar 2027, 31-Mar-2027, 31st March, 2027
    ("d_mon_y", re.compile(r"(?<![\d])(\d{1,2})(?:st|nd|rd|th)?[ .\-/]*" + _MONTH + r"[ .,\-/]*((?:19|20|21)\d{2})(?!\d)", re.I)),
    # March 31, 2027
    ("mon_d_y", re.compile(r"\b" + _MONTH + r" (\d{1,2})(?:st|nd|rd|th)?,? ((?:19|20|21)\d{2})(?!\d)", re.I)),
]
MIN_YEAR, MAX_YEAR = 2000, 2100


@dataclass(frozen=True)
class FoundDate:
    value: date
    start: int
    end: int


def _make(y: int, m: int, d: int) -> Optional[date]:
    if not MIN_YEAR <= y <= MAX_YEAR:
        return None
    try:
        return date(y, m, d)
    except ValueError:
        return None


def find_dates(text: str, start: int = 0, end: Optional[int] = None) -> List[FoundDate]:
    """Every date in text[start:end], by position; overlapping matches keep the first pattern's."""
    end = len(text) if end is None else end
    found: List[FoundDate] = []
    for kind, pattern in _DATE_PATTERNS:
        for m in pattern.finditer(text, start, end):
            g = m.groups()
            if kind == "dmy":
                value = _make(int(g[3]), int(g[2]), int(g[0]))
            elif kind == "iso":
                value = _make(int(g[0]), int(g[1]), int(g[2]))
            elif kind == "d_mon_y":
                value = _make(int(g[2]), _MONTHS[g[1][:3].lower()], int(g[0]))
            else:
                value = _make(int(g[2]), _MONTHS[g[0][:3].lower()], int(g[1]))
            if value and not any(f.start < m.end() and m.start() < f.end for f in found):
                found.append(FoundDate(value, m.start(), m.end()))
    return sorted(found, key=lambda f: f.start)


# ── Lines ──
def _lines(text: str) -> List[Tuple[int, int]]:
    """(start, end) of each line, end exclusive of the newline."""
    spans, pos = [], 0
    for line in text.split("\n"):
        spans.append((pos, pos + len(line)))
        pos += len(line) + 1
    return spans


def _line_at(lines: List[Tuple[int, int]], pos: int) -> int:
    """The line holding [pos] (lines are sorted by start)."""
    return max(0, bisect.bisect_right(lines, (pos, float("inf"))) - 1)


def _next_lines(lines: List[Tuple[int, int]], i: int, n: int, text: str) -> Iterator[Tuple[int, int]]:
    """The next [n] non-blank lines after line i."""
    taken = 0
    for s, e in lines[i + 1:]:
        if text[s:e].strip():
            yield s, e
            taken += 1
            if taken == n:
                return


# ── Expiry ──
# Strongest first. A cue anchors the first date after it on its line, or (labels above values) the latest date on the
# next line: on a card the next line holds issue, birth and expiry dates, and expiry is the latest.
_EXPIRY_CUES = [
    ("Date of expiry", re.compile(r"date\s+of\s+expir(?:y|ation)", re.I)),
    ("Expiry", re.compile(r"\bexpir(?:y|es|ation|ing)\b(?:\s+date)?(?:\s+on)?", re.I)),
    ("Valid till", re.compile(r"\bvalid(?:ity)?\s*(?:till|until|upto|up\s+to|thru|through)\b", re.I)),
]
# A period: its later date. "Period of Insurance: 01/04/2026 to 31/03/2027", "Valid from … to …", "from … to …" (a
# tenancy). A plain "from … to" is not a validity on a tax or salary document ("Period with the Employer: From … To …").
_PERIOD_CUES = [
    ("Period", re.compile(r"\b(?:period\s+of\s+(?:insurance|cover)|policy\s+period|insurance\s+period|cover(?:age)?\s+period|validity\s+period)\b", re.I)),
    ("Valid from", re.compile(r"\bvalid(?:ity)?\s+from\b", re.I)),
    ("From … to", re.compile(r"\bfrom\b(?=[^\n]{0,80}(?:\n[^\n]{0,80})?\bto\b)", re.I)),
]
_NOT_A_VALIDITY = re.compile(r"\b(?:employer|employment|assessment\s+year|financial\s+year|previous\s+year|salary|statement)\b", re.I)
_SAME_LINE_REACH = 60  # characters after a cue in which its date must start


def _expiry(text: str, lines) -> Optional[Proposal]:
    for label, cue in _EXPIRY_CUES:
        for m in cue.finditer(text):
            i = _line_at(lines, m.start())
            line_end = lines[i][1]
            after = [d for d in find_dates(text, m.end(), line_end) if d.start - m.end() <= _SAME_LINE_REACH]
            if after:
                return _date_proposal(text, after[0], label, "expiry_cue")
            # Nothing after it on its line: the values may be on the next line, under the labels.
            for s, e in _next_lines(lines, i, 1, text):
                below = find_dates(text, s, e)
                if below:
                    return _date_proposal(text, max(below, key=lambda d: d.value), label, "expiry_cue_below")
    for label, cue in _PERIOD_CUES:
        for m in cue.finditer(text):
            i = _line_at(lines, m.start())
            span_end = lines[i][1]
            if _NOT_A_VALIDITY.search(text, lines[i][0], span_end):
                continue
            dates = find_dates(text, m.end(), span_end)
            if len(dates) < 2:  # the period may wrap onto the next line
                for s, e in _next_lines(lines, i, 1, text):
                    dates += find_dates(text, s, e)
            if len(dates) >= 2:
                first, *rest = dates[:2]
                later = rest[0]
                if later.value > first.value:
                    return _date_proposal(text, later, label, "period_end")
    return None


def _date_proposal(text: str, d: FoundDate, label: str, rule: str) -> Proposal:
    return Proposal("expiresOn", d.value.isoformat(), text[d.start:d.end], label, rule)


# ── Numbers ──
# Strongest first. Each label has the shapes its number can take, so a label never adopts a stray token: a passport
# number is a letter and 7 digits, a PAN is 5 letters, 4 digits and a letter, an Aadhaar is 12 digits.
_AADHAAR = re.compile(r"(?<!\d)(?<!\d )\d{4} ?\d{4} ?\d{4}(?! ?\d)")  # not a piece of a 16-digit card number
_REGISTRATION = re.compile(r"[A-Z]{2} ?\d{1,2} ?[A-Z]{0,3} ?\d{4}(?![A-Z0-9])")
_GENERIC = re.compile(r"[A-Z0-9][A-Z0-9/\-]{3,30}[A-Z0-9](?![A-Z0-9/\-])")
_NUMBER_CUES = [
    ("Policy No.", re.compile(r"\bpolicy\s*(?:no|number|num)\b\.?", re.I), [_GENERIC]),
    ("Certificate No.", re.compile(r"\b(?:certificate|cert)\s*(?:no|number|sl\.?\s*no)\b\.?", re.I), [_GENERIC]),
    ("Passport No.", re.compile(r"\bpassport\s*(?:no|number)\b\.?", re.I), [re.compile(r"[A-Z]\d{7}(?![A-Z0-9])")]),
    ("Licence No.", re.compile(r"\b(?:driving\s+)?licen[cs]e\s*(?:no|number)\b\.?|\bDL\s*(?:no|number)\b\.?", re.I), [_GENERIC]),
    ("PAN", re.compile(r"\bpermanent\s+account\s+number\b(?:\s*card)?|\bPAN\s*(?:no|number)?\b\.?", re.I),
     [re.compile(r"[A-Z]{5}\d{4}[A-Z](?![A-Z0-9])")]),
    ("Aadhaar No.", re.compile(r"\baadhaar\s*(?:no|number)?\b\.?", re.I), [_AADHAAR]),
    ("Registration No.", re.compile(r"\b(?:registration|regn|reg)\.?\s*(?:no|number|mark)\b\.?|\bvehicle\s*(?:no|number)\b\.?", re.I), [_REGISTRATION]),
    ("Document No.", re.compile(r"\b(?:document|doc|card|serial|warranty|invoice|folio|membership|account)\s*(?:no|number)\b\.?", re.I), [_GENERIC]),
]
_SEPARATOR = re.compile(r"[ \t]*(?:[:#\-.]|\(s\))?[ \t]*")
# On an Aadhaar card the number is printed on its own, without a label.
_AADHAAR_DOCUMENT = re.compile(r"\baadhaar\b|unique\s+identification\s+authority", re.I)
MIN_NUMBER_DIGITS = 3


def _number_at(text: str, pos: int, end: int, shapes) -> Optional[Tuple[int, int]]:
    for shape in shapes:
        m = shape.match(text, pos, end)
        if m and _plausible_number(m.group()):
            return m.start(), m.end()
    return None


_YEAR_RANGE = re.compile(r"(?:19|20)\d{2}-(?:\d{2}|(?:19|20)\d{2})")  # "2026-27": an assessment year, not a number
_VALUE_LIKE = re.compile(r"(?<![A-Za-z0-9/\-])[A-Z0-9][A-Z0-9/\-]{3,30}(?![A-Za-z0-9/\-])")  # a value-shaped token; bounded (no backtracking blow-up)
_REST_REACH = 120  # characters after a label looked at for its value


def _plausible_number(value: str) -> bool:
    return (sum(c.isdigit() for c in value) >= MIN_NUMBER_DIGITS and not find_dates(value) and len(value) <= 32
            and not _YEAR_RANGE.fullmatch(value))


def _number(text: str, lines) -> Optional[Proposal]:
    tried: List[Tuple[int, int]] = []  # labels already read by a stronger cue ("Permanent Account Number" is not an account number)
    for label, cue, shapes in _NUMBER_CUES:
        for m in cue.finditer(text):
            if any(s < m.end() and m.start() < e for s, e in tried):
                continue
            tried.append((m.start(), m.end()))
            i = _line_at(lines, m.start())
            line_end = lines[i][1]
            sep = _SEPARATOR.match(text, m.end(), line_end)
            at = _number_at(text, sep.end() if sep else m.end(), line_end, shapes)
            rest = text[m.end():min(line_end, m.end() + _REST_REACH)]
            if at is None and not any(c.isdigit() for c in rest) and not _VALUE_LIKE.search(rest):
                # A row of labels (or a label ending its line): the value is on the next line, below it. Not when
                # the label has a value of another shape after it: that value just isn't a number.
                for s, e in _next_lines(lines, i, 1, text):
                    starts = [s] + [k + 1 for k in range(s, e) if text[k] in " \t"]
                    at = next((a for a in (_number_at(text, p, e, shapes) for p in starts) if a), None)
            if at:
                value = text[at[0]:at[1]]
                return Proposal("number", value, value, label, "number_cue")
    if _AADHAAR_DOCUMENT.search(text):
        m = _AADHAAR.search(text)
        if m:
            return Proposal("number", m.group(), m.group(), "Aadhaar", "aadhaar_shape")
    return None


# ── Issuer ──
_INSURER_SHAPE = re.compile(
    r"\b((?:[A-Z][A-Za-z&.'\-]{0,30}[ \t]+){0,5}(?:General[ \t]+|Health[ \t]+|Life[ \t]+)?(?:Insurance|Assurance)[ \t]+(?:Company|Co\.)[ \t]+(?:Limited|Ltd\.?))")


def _issuer(text: str) -> Optional[Proposal]:
    best: Optional[Tuple[int, int, int]] = None  # (start, -length, end): earliest, then longest
    for alias in ISSUERS:
        for m in alias.finditer(text):
            key = (m.start(), -(m.end() - m.start()), m.end())
            if best is None or key < best:
                best = key
    if best:
        value = text[best[0]:best[2]]
        return Proposal("issuer", value, value, "", "known_issuer")
    m = _INSURER_SHAPE.search(text)
    if m:
        value = m.group(1)
        return Proposal("issuer", value, value, "", "insurer_shape")
    return None


# ── Amount ──
_AMOUNT_CUES = [
    ("Total premium", re.compile(r"\btotal\s+(?:policy\s+)?premium\b(?:\s+(?:payable|paid|amount))?", re.I)),
    ("Amount paid", re.compile(r"\b(?:total\s+)?amount\s+(?:paid|payable|received)\b", re.I)),
    ("Premium", re.compile(r"\b(?:net\s+|gross\s+|annual\s+|final\s+)?premium\b(?:\s+(?:amount|payable|paid))?", re.I)),
    ("Fee", re.compile(r"\b(?:test\s+|registration\s+|renewal\s+)?fees?\b(?:\s+paid)?", re.I)),
]
_AMOUNT = re.compile(r"(?:(?:₹|rs\.?|inr)\s*)?((?:\d{1,3}(?:, ?\d{2,3})+|\d{2,9})(?:\.\d{1,2})?)(?![\d,]|\s?%|[./-]\d)", re.I)
_AMOUNT_REACH = 40
_AMOUNT_BETWEEN = re.compile(r"[\sA-Za-z().:/\-=]*")  # words like "(incl. GST)", ":", "Rs." between a cue and the amount


def _amount(text: str, lines) -> Optional[Proposal]:
    for label, cue in _AMOUNT_CUES:
        for m in cue.finditer(text):
            i = _line_at(lines, m.start())
            line_end = lines[i][1]
            for a in _AMOUNT.finditer(text, m.end(), min(line_end, m.end() + _AMOUNT_REACH)):
                if not _AMOUNT_BETWEEN.fullmatch(text, m.end(), a.start()):
                    break
                if any(d.start <= a.start(1) < d.end for d in find_dates(text, lines[i][0], line_end)):
                    continue
                value = _decimal(a.group(1))
                if value is not None and value > 0:
                    return Proposal("amount", f"{value:.2f}", text[a.start():a.end()].strip(), label, "amount_cue")
                break
    return None


def _decimal(raw: str) -> Optional[Decimal]:
    try:
        return Decimal(raw.replace(",", "").replace(" ", ""))
    except InvalidOperation:
        return None


def propose(text: str) -> List[Proposal]:
    """At most one proposal per field, in [FIELDS] order."""
    lines = _lines(text)
    found = [_expiry(text, lines), _number(text, lines), _issuer(text), _amount(text, lines)]
    return [p for p in found if p is not None]
