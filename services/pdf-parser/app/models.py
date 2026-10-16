"""Wire models for statement parsing (camelCase JSON, D-31). Amounts are decimal strings."""

from datetime import date
from decimal import Decimal
from typing import List, Optional

from pydantic import BaseModel, ConfigDict, field_serializer
from pydantic.alias_generators import to_camel


class _Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class StatementRow(_Camel):
    """One statement line. Exactly one of debit/credit is set."""

    date: date
    description: str
    debit: Optional[Decimal] = None
    credit: Optional[Decimal] = None
    balance: Optional[Decimal] = None
    reference: Optional[str] = None

    @field_serializer("debit", "credit", "balance")
    def _money(self, v: Optional[Decimal]) -> Optional[str]:
        return None if v is None else f"{v:.2f}"


class StatementParseResult(_Camel):
    bank_code: Optional[str] = None
    account_last4: Optional[str] = None
    period_start: Optional[date] = None
    period_end: Optional[date] = None
    rows: List[StatementRow]
    skipped_rows: int = 0
    warnings: List[str] = []


class ParseError(Exception):
    """A client-facing failure with a stable code. Messages never contain statement content."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message
