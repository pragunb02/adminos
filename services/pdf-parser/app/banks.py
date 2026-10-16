"""Bank and account detection from statement text."""

import re
from typing import Optional

# bank code → phrases that identify it in statement headers (and IFSC prefixes).
BANKS = {
    "hdfc": ["HDFC BANK", "HDFC0"],
    "sbi": ["STATE BANK OF INDIA", "SBIN0"],
    "icici": ["ICICI BANK", "ICIC0"],
    "axis": ["AXIS BANK", "UTIB0"],
    "kotak": ["KOTAK MAHINDRA", "KKBK0"],
    "yes": ["YES BANK", "YESB0"],
    "indusind": ["INDUSIND", "INDB0"],
    "pnb": ["PUNJAB NATIONAL BANK", "PUNB0"],
    "bob": ["BANK OF BARODA", "BARB0"],
    "idfc": ["IDFC FIRST", "IDFB0"],
}


def detect_bank(text: str) -> Optional[str]:
    up = text.upper()
    hits = {code: min((up.find(p) for p in phrases if p in up), default=-1) for code, phrases in BANKS.items()}
    found = {c: i for c, i in hits.items() if i >= 0}
    return min(found, key=found.get) if found else None


_ACCOUNT = re.compile(r"(?i)\b(?:a/?c|account)\s*(?:no\.?|number|num)?\s*[:#\-]?\s*[X*x\d -]*?(\d{4})\b")


def detect_account_last4(text: str) -> Optional[str]:
    m = _ACCOUNT.search(text)
    return m.group(1) if m else None
