"""Core PDF parsing logic with bank detection and extraction."""

import logging
import re
from typing import List, Optional, Tuple

import pdfplumber

from app.models import ExtractedTransaction, ParseResult
from app.banks import BANK_PARSERS, BaseBankParser, GenericBankParser

logger = logging.getLogger(__name__)


def detect_bank(pdf_content: bytes) -> Optional[str]:
    """Detect which bank issued the statement from PDF content."""
    try:
        import io
        with pdfplumber.open(io.BytesIO(pdf_content)) as pdf:
            if not pdf.pages:
                return None
            # Read first page text for bank identification
            first_page = pdf.pages[0].extract_text() or ""
            return _identify_bank(first_page)
    except Exception as e:
        logger.error("Bank detection failed: %s", str(e))
        return None


def parse_bank_statement(
    pdf_content: bytes, filename: str = "statement.pdf"
) -> ParseResult:
    """Parse a bank statement PDF and extract transactions."""
    import io

    errors: List[str] = []
    transactions: List[ExtractedTransaction] = []

    with pdfplumber.open(io.BytesIO(pdf_content)) as pdf:
        if not pdf.pages:
            return ParseResult(
                bank_code="unknown",
                bank_name="Unknown Bank",
                transactions=[],
                total_transactions=0,
                avg_confidence=0.0,
                errors=["PDF has no pages"],
            )

        # Detect bank from first page
        first_page_text = pdf.pages[0].extract_text() or ""
        bank_code = _identify_bank(first_page_text)

        # Select bank-specific parser
        parser = BANK_PARSERS.get(bank_code, GenericBankParser())
        bank_name = parser.bank_name

        # Extract account number if possible
        account_last4 = _extract_account_last4(first_page_text)

        # Extract statement period
        statement_period = _extract_statement_period(first_page_text)

        # Parse each page
        for page_num, page in enumerate(pdf.pages):
            try:
                page_txns = parser.extract_transactions(page)
                transactions.extend(page_txns)
            except Exception as e:
                error_msg = f"Failed to parse page {page_num + 1}: {str(e)}"
                logger.warning(error_msg)
                errors.append(error_msg)

    # Compute average confidence
    avg_conf = 0.0
    if transactions:
        avg_conf = sum(t.confidence for t in transactions) / len(transactions)
        avg_conf = round(avg_conf, 2)

    return ParseResult(
        bank_code=bank_code or "unknown",
        bank_name=bank_name,
        account_last4=account_last4,
        statement_period=statement_period,
        transactions=transactions,
        total_transactions=len(transactions),
        avg_confidence=avg_conf,
        errors=errors,
    )


# --- Bank identification ---

_BANK_PATTERNS = {
    "sbi": [r"state bank of india", r"\bsbi\b", r"sbin\d"],
    "hdfc": [r"hdfc bank", r"hdfcbank"],
    "icici": [r"icici bank", r"icicibank"],
    "axis": [r"axis bank", r"axisbank"],
    "kotak": [r"kotak mahindra", r"kotak bank"],
    "yes": [r"yes bank"],
    "indusind": [r"indusind bank"],
    "pnb": [r"punjab national bank", r"\bpnb\b"],
    "bob": [r"bank of baroda", r"\bbob\b"],
}


def _identify_bank(text: str) -> Optional[str]:
    """Identify bank from page text using pattern matching."""
    text_lower = text.lower()
    for bank_code, patterns in _BANK_PATTERNS.items():
        for pattern in patterns:
            if re.search(pattern, text_lower):
                return bank_code
    return None


def _extract_account_last4(text: str) -> Optional[str]:
    """Extract last 4 digits of account number from statement header."""
    patterns = [
        r"(?:a/c|account|acct)\s*(?:no\.?\s*)?[:\s]*[xX*]*(\d{4})\b",
        r"(?:a/c|account|acct)\s*(?:no\.?\s*)?[:\s]*\d+(\d{4})\b",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return match.group(1)
    return None


def _extract_statement_period(text: str) -> Optional[str]:
    """Extract statement period from header text."""
    patterns = [
        r"(?:statement\s+(?:for|period))[:\s]+(.+?)(?:\n|$)",
        r"(?:from|period)[:\s]+(\d{2}[/-]\d{2}[/-]\d{4})\s*(?:to|-)\s*(\d{2}[/-]\d{2}[/-]\d{4})",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return match.group(0).strip()
    return None
