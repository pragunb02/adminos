"""AdminOS statement parser (D-37). Stateless: files and passwords are used in memory only, never stored or logged."""

import asyncio
import logging
from typing import Optional

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

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
