"""Data models for the PDF parser."""

from typing import List, Optional
from pydantic import BaseModel


class ExtractedTransaction(BaseModel):
    """A single transaction extracted from a bank statement."""
    date: str
    description: str
    amount: float
    type: str  # "debit" or "credit"
    balance: Optional[float] = None
    confidence: float = 0.8


class ParseResult(BaseModel):
    """Result of parsing a bank statement PDF."""
    bank_code: str
    bank_name: str
    account_last4: Optional[str] = None
    statement_period: Optional[str] = None
    transactions: List[ExtractedTransaction]
    total_transactions: int
    avg_confidence: float
    parse_method: str = "pdfplumber"
    errors: List[str] = []
