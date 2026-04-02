"""AdminOS PDF Parser — extracts transactions from bank statement PDFs."""

import logging
from typing import Optional

from fastapi import FastAPI, HTTPException, UploadFile, File
from pydantic import BaseModel

from app.parser import parse_bank_statement, detect_bank
from app.models import ParseResult, ExtractedTransaction

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="AdminOS PDF Parser", version="0.1.0")


@app.get("/health")
async def health():
    return {"status": "ok"}


class ParseRequest(BaseModel):
    storage_key: str
    sync_session_id: str
    user_id: str


@app.post("/parse", response_model=ParseResult)
async def parse_pdf(file: UploadFile = File(...)):
    """Parse a bank statement PDF and extract transactions."""
    if not file.filename or not file.filename.lower().endswith(".pdf"):
        raise HTTPException(status_code=400, detail="Only PDF files are accepted")

    content = await file.read()
    if len(content) > 20 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="File exceeds 20MB limit")

    try:
        result = parse_bank_statement(content, file.filename)
        logger.info(
            "Parsed %s: bank=%s, transactions=%d, avg_confidence=%.2f",
            file.filename,
            result.bank_code,
            len(result.transactions),
            result.avg_confidence,
        )
        return result
    except Exception as e:
        logger.error("Failed to parse PDF %s: %s", file.filename, str(e))
        raise HTTPException(status_code=422, detail=f"Failed to parse PDF: {str(e)}")


@app.post("/detect-bank")
async def detect_bank_endpoint(file: UploadFile = File(...)):
    """Detect which bank issued the statement."""
    content = await file.read()
    bank = detect_bank(content)
    return {"bank_code": bank or "unknown"}
