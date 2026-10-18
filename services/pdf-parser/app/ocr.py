"""Local OCR for the documents vault (AOS-717, proposes D-140): Tesseract 5, run as a separate process.

Why Tesseract (the evidence is in docs/tickets/AOS-717.md):
- **Licence:** Apache-2.0, packaged by Debian, so it is patched with the base image.
- **Image size:** about 40 MB for the engine and English data. PaddlePaddle has no wheel for this Python (3.14).
  docTR needs PyTorch (a 159 MB wheel) or ONNX Runtime with OpenCV (61 MB of wheels) and models fetched from the internet.
- **CPU only:** no GPU and no model download at run time. The parser's network has no internet (D-91).
- **Accuracy:** good on clean scans and text, weaker on skewed photos. Measured on the synthetic eval set.

It runs as its own process: no shell, a fixed argument list, the image on stdin and the text on stdout. It has a
time limit (killed after it) and the parsing child's memory limit (AOS-626). Its stderr is never read into a message
or log, because it can quote the document.
"""

import os
import shutil
import subprocess
from typing import Protocol

from app import limits
from app.models import ParseError

# Fixed environment: one thread (the container has 2 CPUs and runs 2 parses at once), C locale, no proxy settings.
_ENV = {"OMP_THREAD_LIMIT": "1", "LC_ALL": "C", "PATH": "/usr/local/bin:/usr/bin:/bin"}


class Ocr(Protocol):
    """Image (PNG bytes) → text. A new engine (docTR, PaddleOCR) plugs in as another implementation."""

    name: str

    def read(self, png: bytes) -> str: ...


class TesseractOcr:
    name = "tesseract"

    def __init__(self, binary: str | None = None, timeout: float = limits.OCR_PAGE_TIMEOUT_SECONDS, language: str = "eng"):
        self.binary = binary if binary is not None else shutil.which("tesseract", path=_ENV["PATH"])
        self.timeout = timeout
        if not language.isalpha() or not language.isascii():  # never a path or an option
            raise ValueError("language: letters only")
        self.language = language

    @property
    def available(self) -> bool:
        return bool(self.binary) and os.access(self.binary, os.X_OK)

    def read(self, png: bytes) -> str:
        if not self.available:
            raise ParseError("OCR_UNAVAILABLE", "Reading photos and scans isn't set up on this server")
        # --oem 1: the LSTM engine; --psm 3: automatic page layout. "stdin stdout": no files anywhere.
        args = [self.binary, "stdin", "stdout", "--oem", "1", "--psm", "3", "-l", self.language]
        try:
            done = subprocess.run(args, input=png, capture_output=True, timeout=self.timeout, env=_ENV, check=False, shell=False)
        except subprocess.TimeoutExpired:
            raise ParseError("OCR_TIMEOUT", "The document took too long to read") from None
        except OSError:
            raise ParseError("OCR_UNAVAILABLE", "Reading photos and scans isn't set up on this server") from None
        if done.returncode != 0:
            raise ParseError("UNREADABLE", "The document couldn't be read")  # never stderr: it can quote the image
        return done.stdout.decode("utf-8", errors="replace")
