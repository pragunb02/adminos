"""Raw tables → statement rows by recognising column roles from header synonyms (Strategy, D-27).

Works across banks without per-bank code: HDFC "Withdrawal Amt.", SBI "Debit", ICICI
"Withdrawal Amount (INR)" all map to the same role. Bank quirks can extend SYNONYMS.
"""

import re
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Dict, List, Optional, Tuple

from app.models import StatementRow
from app.readers import Table

# role → header phrases (normalized: lowercase, no punctuation). More specific phrases first.
SYNONYMS: Dict[str, List[str]] = {
    "date": ["transaction date", "txn date", "tran date", "posting date", "date"],
    "value_date": ["value date", "value dt"],
    "description": ["narration", "description", "particulars", "transaction remarks", "remarks", "transaction details", "details"],
    "debit": ["withdrawal amount", "withdrawal amt", "withdrawals", "withdrawal", "debit amount", "debit", "amount dr", "dr amount"],
    "credit": ["deposit amount", "deposit amt", "deposits", "deposit", "credit amount", "credit", "amount cr", "cr amount"],
    "drcr": ["dr cr", "cr dr", "debit credit", "dr or cr", "type"],
    "amount": ["transaction amount", "amount"],
    "balance": ["closing balance", "running balance", "available balance", "balance"],
    "reference": ["chq ref no", "ref no cheque no", "chq no", "cheque no", "ref no", "reference no", "reference", "utr"],
}

DATE_FORMATS = [
    "%d/%m/%Y", "%d/%m/%y", "%d-%m-%Y", "%d-%m-%y", "%d.%m.%Y", "%d.%m.%y",
    "%d %b %Y", "%d-%b-%Y", "%d-%b-%y", "%d %b %y", "%d/%b/%Y", "%d %B %Y", "%d-%B-%Y", "%Y-%m-%d",
]


def _norm_header(cell: str) -> str:
    s = cell.lower()
    s = re.sub(r"\(.*?\)", " ", s)  # "(INR)"
    s = re.sub(r"[^a-z ]", " ", s)
    return " ".join(s.split())


def _role_of(cell: str) -> Optional[str]:
    h = _norm_header(cell)
    if not h:
        return None
    for role, phrases in SYNONYMS.items():
        if any(h == p for p in phrases):
            return role
    for role, phrases in SYNONYMS.items():
        if any(p in h for p in phrases if len(p) > 3):
            return role
    return None


@dataclass
class ColumnMap:
    roles: Dict[str, int]
    width: int

    def usable(self) -> bool:
        r = self.roles
        date_ok = "date" in r or "value_date" in r
        money_ok = ("debit" in r and "credit" in r) or "amount" in r
        return date_ok and "description" in r and money_ok


def find_header(table: Table, scan: int = 60) -> Optional[Tuple[int, ColumnMap]]:
    for i, row in enumerate(table[:scan]):
        roles: Dict[str, int] = {}
        for j, cell in enumerate(row):
            role = _role_of(cell)
            if role and role not in roles:
                roles[role] = j
        cm = ColumnMap(roles, len(row))
        if cm.usable():
            return i, cm
    return None


def parse_date(cell: str) -> Optional[date]:
    s = cell.strip()
    if not s:
        return None
    s = re.split(r"[ T](?=\d{1,2}:\d{2})", s)[0].strip()  # drop a trailing time
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(s, fmt).date()
        except ValueError:
            continue
    return None


_MONEY_JUNK = re.compile(r"(?i)\b(inr|rs\.?)\b|₹|,|\s")


def parse_amount(cell: str) -> Tuple[Optional[Decimal], Optional[str]]:
    """'1,23,456.78 Cr' → (123456.78, 'credit'); '(500.00)' / '-500' → (500, 'debit')."""
    s = cell.strip()
    if not s or s in {"-", "--", "0", "0.00", "NIL", "nil"}:
        return None, None
    hint = None
    m = re.search(r"(?i)(?<=[\d\s.])(cr|dr)\.?$", s)
    if m:
        hint = "credit" if m.group(1).lower() == "cr" else "debit"
        s = s[: m.start()]
    if s.startswith("(") and s.endswith(")"):
        s, hint = s[1:-1], "debit"
    s = _MONEY_JUNK.sub("", s)
    if s.startswith("-"):
        s, hint = s[1:], hint or "debit"
    try:
        v = Decimal(s)
    except InvalidOperation:
        return None, None
    return (v.quantize(Decimal("0.01")) if v != 0 else None), hint


def _cell(row: List[str], idx: Optional[int]) -> str:
    return row[idx] if idx is not None and idx < len(row) else ""


def map_rows(table: Table, header_idx: int, cm: ColumnMap) -> Tuple[List[StatementRow], int]:
    """Rows below the header → StatementRows. Lines without a date continue the previous narration."""
    r = cm.roles
    out: List[StatementRow] = []
    skipped = 0
    for row in table[header_idx + 1:]:
        if not any(c.strip() for c in row):
            continue
        d = parse_date(_cell(row, r.get("date"))) or parse_date(_cell(row, r.get("value_date")))
        desc = _cell(row, r.get("description"))
        if d is None:
            if out and desc and not any(parse_amount(_cell(row, r.get(k)))[0] for k in ("debit", "credit", "amount")):
                last = out[-1]
                out[-1] = last.model_copy(update={"description": f"{last.description} {desc}".strip()})
            else:
                skipped += 1  # opening/closing balance, totals, footers
            continue

        debit, credit = _money(row, r)
        if debit is None and credit is None:
            skipped += 1
            continue
        bal, bal_hint = parse_amount(_cell(row, r.get("balance")))
        if bal is not None and bal_hint == "debit":
            bal = -bal  # overdrawn / negative balance
        out.append(StatementRow(
            date=d, description=desc or "(no description)", debit=debit, credit=credit, balance=bal,
            reference=_cell(row, r.get("reference")) or None,
        ))
    return out, skipped


def _money(row: List[str], r: Dict[str, int]) -> Tuple[Optional[Decimal], Optional[Decimal]]:
    if "debit" in r and "credit" in r:
        d, _ = parse_amount(_cell(row, r["debit"]))
        c, _ = parse_amount(_cell(row, r["credit"]))
        if d is not None and c is not None:  # both filled: trust neither
            return None, None
        return d, c
    amount, hint = parse_amount(_cell(row, r.get("amount")))
    if amount is None:
        return None, None
    flag = _cell(row, r.get("drcr")).strip().lower()
    if flag.startswith(("cr", "c")) or flag == "credit":
        hint = "credit"
    elif flag.startswith(("dr", "d")) or flag == "debit":
        hint = "debit"
    if hint == "credit":
        return None, amount
    if hint == "debit":
        return amount, None
    return None, None  # direction unknown: never guess money direction


def balances_reconcile(rows: List[StatementRow]) -> Optional[bool]:
    """True if running balances match debits/credits (in either sort order); None if not checkable."""
    pts = [(i, x) for i, x in enumerate(rows) if x.balance is not None]
    if len(pts) < 3:
        return None

    def score(seq: List[StatementRow]) -> float:
        ok = total = 0
        for prev, cur in zip(seq, seq[1:]):
            if prev.balance is None or cur.balance is None:
                continue
            total += 1
            ok += prev.balance - (cur.debit or 0) + (cur.credit or 0) == cur.balance
        return ok / total if total else 0.0

    return max(score(rows), score(list(reversed(rows)))) >= 0.9


def extract(tables: List[Table]) -> Tuple[List[StatementRow], int, List[str]]:
    """All tables → rows. Continuation tables (PDF pages without a header) reuse the last mapping."""
    rows: List[StatementRow] = []
    skipped = 0
    last: Optional[ColumnMap] = None
    for t in tables:
        found = find_header(t)
        if found:
            idx, cm = found
            last = cm
        elif last and t and len(t[0]) == last.width:
            idx, cm = -1, last
        else:
            continue
        got, sk = map_rows(t, idx, cm)
        rows.extend(got)
        skipped += sk
    warnings = []
    if balances_reconcile(rows) is False:
        warnings.append("Running balances don't reconcile with debits/credits; some rows may be mis-read")
    return rows, skipped, warnings
