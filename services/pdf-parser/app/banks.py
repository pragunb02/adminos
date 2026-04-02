"""Bank-specific PDF parser templates for Indian banks."""

import logging
import re
from abc import ABC, abstractmethod
from typing import List, Optional

from app.models import ExtractedTransaction

logger = logging.getLogger(__name__)


class BaseBankParser(ABC):
    """Base class for bank-specific PDF parsers."""

    @property
    @abstractmethod
    def bank_code(self) -> str:
        ...

    @property
    @abstractmethod
    def bank_name(self) -> str:
        ...

    @property
    def base_confidence(self) -> float:
        return 0.8

    def extract_transactions(self, page) -> List[ExtractedTransaction]:
        """Extract transactions from a single PDF page using pdfplumber."""
        transactions: List[ExtractedTransaction] = []

        # Try table extraction first (most reliable)
        tables = page.extract_tables()
        if tables:
            for table in tables:
                txns = self._parse_table(table)
                transactions.extend(txns)

        # Fallback: line-by-line text parsing
        if not transactions:
            text = page.extract_text() or ""
            txns = self._parse_text(text)
            transactions.extend(txns)

        return transactions

    def _parse_table(self, table: list) -> List[ExtractedTransaction]:
        """Parse a table extracted by pdfplumber. Override for bank-specific layouts."""
        transactions = []
        if not table or len(table) < 2:
            return transactions

        # Try to identify column indices from header row
        header = [str(cell).lower().strip() if cell else "" for cell in table[0]]
        date_col = _find_col(header, ["date", "txn date", "transaction date", "value date"])
        desc_col = _find_col(header, ["description", "narration", "particulars", "details"])
        debit_col = _find_col(header, ["debit", "withdrawal", "dr", "debit amount"])
        credit_col = _find_col(header, ["credit", "deposit", "cr", "credit amount"])
        balance_col = _find_col(header, ["balance", "closing balance", "running balance"])

        if date_col is None or desc_col is None:
            return transactions

        for row in table[1:]:
            if not row or len(row) <= max(date_col, desc_col):
                continue

            date_str = _clean_cell(row[date_col]) if date_col < len(row) else ""
            description = _clean_cell(row[desc_col]) if desc_col < len(row) else ""

            if not date_str or not _looks_like_date(date_str):
                continue

            debit_amt = _parse_amount(row[debit_col]) if debit_col and debit_col < len(row) else None
            credit_amt = _parse_amount(row[credit_col]) if credit_col and credit_col < len(row) else None
            balance = _parse_amount(row[balance_col]) if balance_col and balance_col < len(row) else None

            if debit_amt and debit_amt > 0:
                transactions.append(ExtractedTransaction(
                    date=date_str,
                    description=description,
                    amount=debit_amt,
                    type="debit",
                    balance=balance,
                    confidence=self.base_confidence,
                ))
            elif credit_amt and credit_amt > 0:
                transactions.append(ExtractedTransaction(
                    date=date_str,
                    description=description,
                    amount=credit_amt,
                    type="credit",
                    balance=balance,
                    confidence=self.base_confidence,
                ))

        return transactions

    def _parse_text(self, text: str) -> List[ExtractedTransaction]:
        """Fallback: parse transactions from raw text lines."""
        transactions = []
        lines = text.split("\n")

        for line in lines:
            txn = self._parse_text_line(line)
            if txn:
                transactions.append(txn)

        return transactions

    def _parse_text_line(self, line: str) -> Optional[ExtractedTransaction]:
        """Try to parse a single text line as a transaction."""
        # Common pattern: date description amount
        pattern = (
            r"(\d{2}[/-]\d{2}[/-]\d{2,4})\s+"
            r"(.+?)\s+"
            r"([\d,]+\.?\d*)\s*"
            r"(?:(Dr|Cr|DR|CR))?"
        )
        match = re.search(pattern, line)
        if not match:
            return None

        date_str = match.group(1)
        description = match.group(2).strip()
        amount = _parse_amount_str(match.group(3))
        txn_type = "debit"
        if match.group(4) and match.group(4).upper() == "CR":
            txn_type = "credit"

        if amount and amount > 0 and len(description) > 2:
            return ExtractedTransaction(
                date=date_str,
                description=description,
                amount=amount,
                type=txn_type,
                confidence=self.base_confidence * 0.85,  # lower confidence for text parsing
            )
        return None


class GenericBankParser(BaseBankParser):
    """Generic parser for unrecognized banks."""

    @property
    def bank_code(self) -> str:
        return "unknown"

    @property
    def bank_name(self) -> str:
        return "Unknown Bank"

    @property
    def base_confidence(self) -> float:
        return 0.7


class SBIParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "sbi"

    @property
    def bank_name(self) -> str:
        return "State Bank of India"

    @property
    def base_confidence(self) -> float:
        return 0.85


class HDFCParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "hdfc"

    @property
    def bank_name(self) -> str:
        return "HDFC Bank"

    @property
    def base_confidence(self) -> float:
        return 0.85


class ICICIParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "icici"

    @property
    def bank_name(self) -> str:
        return "ICICI Bank"

    @property
    def base_confidence(self) -> float:
        return 0.85


class AxisParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "axis"

    @property
    def bank_name(self) -> str:
        return "Axis Bank"

    @property
    def base_confidence(self) -> float:
        return 0.85


class KotakParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "kotak"

    @property
    def bank_name(self) -> str:
        return "Kotak Mahindra Bank"

    @property
    def base_confidence(self) -> float:
        return 0.8


class YesBankParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "yes"

    @property
    def bank_name(self) -> str:
        return "Yes Bank"


class IndusIndParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "indusind"

    @property
    def bank_name(self) -> str:
        return "IndusInd Bank"


class PNBParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "pnb"

    @property
    def bank_name(self) -> str:
        return "Punjab National Bank"


class BOBParser(BaseBankParser):
    @property
    def bank_code(self) -> str:
        return "bob"

    @property
    def bank_name(self) -> str:
        return "Bank of Baroda"


# Registry of bank parsers
BANK_PARSERS = {
    "sbi": SBIParser(),
    "hdfc": HDFCParser(),
    "icici": ICICIParser(),
    "axis": AxisParser(),
    "kotak": KotakParser(),
    "yes": YesBankParser(),
    "indusind": IndusIndParser(),
    "pnb": PNBParser(),
    "bob": BOBParser(),
}


# --- Utility functions ---

def _find_col(header: list, candidates: list) -> Optional[int]:
    """Find column index matching any candidate name."""
    for i, cell in enumerate(header):
        for candidate in candidates:
            if candidate in cell:
                return i
    return None


def _clean_cell(cell) -> str:
    """Clean a table cell value."""
    if cell is None:
        return ""
    return str(cell).strip()


def _looks_like_date(text: str) -> bool:
    """Check if text looks like a date."""
    return bool(re.match(r"\d{1,2}[/-]\d{1,2}[/-]\d{2,4}", text))


def _parse_amount(cell) -> Optional[float]:
    """Parse an amount from a table cell."""
    if cell is None:
        return None
    return _parse_amount_str(str(cell))


def _parse_amount_str(text: str) -> Optional[float]:
    """Parse an amount string, handling commas and currency symbols."""
    if not text:
        return None
    cleaned = text.replace(",", "").replace("₹", "").replace("Rs.", "").replace("Rs", "").strip()
    try:
        return round(float(cleaned), 2)
    except (ValueError, TypeError):
        return None
