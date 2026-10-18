"""AdminOS statement parser (D-37) and vault document reader (AOS-717). Stateless: files, passwords and the text read
from them are used in memory only, never stored or logged."""

import asyncio
import logging
from typing import Optional

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from app import limits
from app.documents import read_document
from app.isolation import ParseCrashed, ParseFailed, ParseTimeout, run_isolated
from app.models import ParseError
from app.parser import parse_statement

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("statement-parser")

MAX_BYTES = 20 * 1024 * 1024
# Parses at once (each is a child process, AOS-626); more requests wait their turn.
MAX_CONCURRENT_PARSES = 2
_parse_slots = asyncio.Semaphore(MAX_CONCURRENT_PARSES)

app = FastAPI(title="AdminOS statement parser", version="1.0.0")


@app.get("/health")
async def health():
    return {"status": "ok"}


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


@app.post("/v1/statements:parse")
async def parse(file: UploadFile = File(...), password: Optional[str] = Form(default=None)):
    data = await file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        return _error(413, "FILE_TOO_LARGE", "Statement exceeds 20 MB")
    try:
        # In a child process with a time and memory limit, off the event loop (AOS-626).
        async with _parse_slots:
            result = await run_in_threadpool(run_isolated, parse_statement, (data, file.filename or "", password))
    except ParseError as e:
        logger.info("statement rejected: %s", e.code)  # code only: no file name, content or password
        return _error(422, e.code, e.message)
    except ParseTimeout:
        logger.warning("statement parse timed out")
        return _error(422, "PARSE_TIMEOUT", "The statement took too long to read. Export a shorter period")
    except ParseCrashed:
        logger.warning("statement parse crashed")
        return _error(422, "UNREADABLE", "The statement couldn't be read")
    except ParseFailed as e:
        logger.warning("statement parse failed: %s", e.kind)
        return _error(422, "UNREADABLE", "The statement couldn't be read")
    except Exception as e:  # never echo details: they can contain statement content
        logger.warning("statement parse failed: %s", type(e).__name__)
        return _error(422, "UNREADABLE", "The statement couldn't be read")
    logger.info("statement parsed: bank=%s rows=%d skipped=%d", result.bank_code, len(result.rows), result.skipped_rows)
    return result.model_dump(by_alias=True, mode="json")


@app.post("/v1/documents:read")
async def read_vault_document(file: UploadFile = File(...)):
    """A vault document's text and field proposals (AOS-717). The API sends the decrypted bytes; nothing is kept."""
    data = await file.read(limits.MAX_DOC_BYTES + 1)
    if len(data) > limits.MAX_DOC_BYTES:
        return _error(413, "FILE_TOO_LARGE", "A document can be at most 20 MB")
    try:
        async with _parse_slots:  # the same two slots as statements: OCR is the heaviest work here
            result = await run_in_threadpool(run_isolated, read_document, (data,), limits.DOC_TIMEOUT_SECONDS)
    except ParseError as e:
        logger.info("document rejected: %s", e.code)  # code only: never the file, its name or its text
        return _error(422, e.code, e.message)
    except ParseTimeout:
        logger.warning("document read timed out")
        return _error(422, "READ_TIMEOUT", "The document took too long to read")
    except (ParseCrashed, ParseFailed) as e:
        logger.warning("document read failed: %s", getattr(e, "kind", "crashed"))
        return _error(422, "UNREADABLE", "The document couldn't be read")
    except Exception as e:  # never echo details: they can contain document content
        logger.warning("document read failed: %s", type(e).__name__)
        return _error(422, "UNREADABLE", "The document couldn't be read")
    logger.info("document read: kind=%s source=%s pages=%d/%d proposals=%d", result["kind"], result["source"],
                result["pagesRead"], result["pagesTotal"], len(result["proposals"]))
    return result
