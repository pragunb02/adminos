"""Statement file → StatementParseResult."""

from app.banks import detect_account_last4, detect_bank
from app.models import ParseError, StatementParseResult
from app.readers import read
from app.table_mapper import extract


def parse_statement(data: bytes, filename: str, password: str | None = None) -> StatementParseResult:
    raw = read(data, filename, password)
    rows, skipped, warnings = extract(raw.tables)
    if not rows:
        raise ParseError("NO_TRANSACTIONS", "No transactions found. Is this a bank account statement?")
    dates = [r.date for r in rows]
    return StatementParseResult(
        bank_code=detect_bank(raw.text),
        account_last4=detect_account_last4(raw.text),
        period_start=min(dates),
        period_end=max(dates),
        rows=rows,
        skipped_rows=skipped,
        warnings=raw.warnings + warnings,
    )
