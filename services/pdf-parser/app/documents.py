"""A vault document → its text and field proposals (AOS-717). In memory only: nothing is written, nothing is logged.

1. **Type** from the first bytes (never the name): PDF, PNG, JPEG or WebP. HEIC is refused with a code (Pillow can't
   decode it without another native library).
2. **PDF:** the text layer of each of the first pages when it has one (exact, and most insurers' PDFs do). A page
   without one is rendered and read by OCR.
3. **Image:** its size is checked *before* it is decoded (a decompression bomb is refused, not inflated). It is turned
   upright by its EXIF orientation, made grey, scaled into an OCR-friendly size, and read by OCR.
4. **Fields:** `app.fields.propose` on the text.

Called inside a child process with a time and memory limit (`app.isolation`, AOS-626); returns plain values.
"""

import io
import re
import unicodedata
from typing import List, Optional

from app import fields, limits
from app.models import ParseError
from app.ocr import Ocr, TesseractOcr

_CONTROL = re.compile("[\x00-\x08\x0b-\x1f\x7f-\x9f\u200b-\u200f\u202a-\u202e\u2060-\u2064\ufeff]")  # controls, zero-width, bidi
_SPACES = re.compile("[ \t\u00a0]+")


def detect(data: bytes) -> Optional[str]:
    if data.startswith(b"%PDF-"):
        return "pdf"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data[4:8] == b"ftyp" and data[8:12] in (b"heic", b"heix", b"hevc", b"mif1", b"msf1"):
        return "heic"
    return None


def normalise(text: str) -> str:
    """NFKC (fullwidth digits become ASCII), no control or invisible characters, single spaces, no blank-line runs."""
    text = unicodedata.normalize("NFKC", text).replace("\r\n", "\n").replace("\r", "\n")
    text = _CONTROL.sub("", text)
    lines = [_SPACES.sub(" ", line).strip() for line in text.split("\n")]
    out: List[str] = []
    for line in lines:
        if line or (out and out[-1]):
            out.append(line)
    return "\n".join(out).strip()


def _non_space(text: str) -> int:
    return sum(1 for c in text if not c.isspace())


def read_document(data: bytes, ocr: Optional[Ocr] = None) -> dict:
    """Bytes → {kind, source, pagesTotal, pagesRead, truncated, text, proposals}. Raises ParseError with a code."""
    if not data:
        raise ParseError("EMPTY_FILE", "The file is empty")
    if len(data) > limits.MAX_DOC_BYTES:
        raise ParseError("FILE_TOO_LARGE", "A document can be at most 20 MB")
    kind = detect(data)
    if kind is None:
        raise ParseError("UNSUPPORTED_FORMAT", "Only PDF, PNG, JPEG and WebP documents can be read")
    if kind == "heic":
        raise ParseError("UNSUPPORTED_IMAGE", "HEIC photos can't be read yet. Save the photo as JPEG and upload that")
    ocr = ocr or TesseractOcr()
    if kind == "pdf":
        pages, total, sources = _read_pdf(data, ocr)
    else:
        pages, total, sources = [_ocr_image(_open_image(data), ocr)], 1, {"ocr"}
    text = normalise("\n\n".join(pages))
    truncated = total > len(pages) or len(text) > limits.MAX_TEXT_CHARS
    text = text[: limits.MAX_TEXT_CHARS]
    source = "mixed" if len(sources) > 1 else (next(iter(sources)) if sources else "text_layer")
    return {
        "kind": kind,
        "source": source,
        "pagesTotal": total,
        "pagesRead": len(pages),
        "truncated": truncated,
        "text": text,
        "proposals": [p.to_wire() for p in fields.propose(text)],
    }


def _read_pdf(data: bytes, ocr: Ocr):
    import pdfplumber

    try:
        pdf = pdfplumber.open(io.BytesIO(data))
        page_count = len(pdf.pages)
    except Exception as e:
        from app.readers import _is_password_error

        if _is_password_error(e):
            raise ParseError("PASSWORD_PROTECTED", "This PDF is password-protected. Upload a copy without a password")
        raise ParseError("UNSUPPORTED_FORMAT", "Not a readable PDF")
    with pdf:
        if page_count > limits.MAX_PAGES:
            raise ParseError("TOO_MANY_PAGES", f"The document has more than {limits.MAX_PAGES} pages")
        pages: List[str] = []
        sources = set()
        for page in pdf.pages[: limits.MAX_DOC_PAGES_READ]:
            layer = page.extract_text() or ""
            if _non_space(layer) >= limits.MIN_TEXT_LAYER_CHARS:
                pages.append(layer)
                sources.add("text_layer")
            else:
                pages.append(_ocr_image(_render(page), ocr))
                sources.add("ocr")
    return pages, page_count, sources


def _render(page):
    """A page without a text layer, rendered for OCR; a page whose size would pass the pixel cap is rendered smaller."""
    width_in, height_in = float(page.width) / 72, float(page.height) / 72
    if width_in <= 0 or height_in <= 0:
        raise ParseError("UNSUPPORTED_FORMAT", "Not a readable PDF")
    dpi = limits.OCR_DPI
    max_dpi = (limits.MAX_IMAGE_PIXELS / (width_in * height_in)) ** 0.5
    if max_dpi < dpi:
        dpi = int(max_dpi)
        if dpi < 50:  # a page the size of a wall: refuse instead of reading noise
            raise limits.image_too_large()
    return page.to_image(resolution=dpi).original


def _open_image(data: bytes):
    from PIL import Image, ImageOps

    Image.MAX_IMAGE_PIXELS = limits.MAX_IMAGE_PIXELS  # Pillow's own bomb guard, as a second line
    try:
        img = Image.open(io.BytesIO(data))
        width, height = img.size  # from the header: nothing decoded yet
    except Image.DecompressionBombError:
        raise limits.image_too_large() from None
    except Exception:
        raise ParseError("UNSUPPORTED_FORMAT", "Not a readable image") from None
    if width <= 0 or height <= 0 or width * height > limits.MAX_IMAGE_PIXELS:
        raise limits.image_too_large()
    if img.format not in ("PNG", "JPEG", "WEBP"):
        raise ParseError("UNSUPPORTED_FORMAT", "Only PDF, PNG, JPEG and WebP documents can be read")
    try:
        if getattr(img, "n_frames", 1) > 1:
            img.seek(0)  # an animated image: its first frame only
        img.load()
        return ImageOps.exif_transpose(img)
    except Image.DecompressionBombError:
        raise limits.image_too_large() from None
    except Exception:
        raise ParseError("UNSUPPORTED_FORMAT", "Not a readable image") from None


def _ocr_image(img, ocr: Ocr) -> str:
    from PIL import Image

    grey = img.convert("L")  # plain grey: auto-contrast made phone photos worse on the eval set
    longest = max(grey.size)
    if longest > limits.OCR_MAX_SIDE:
        scale = limits.OCR_MAX_SIDE / longest
    elif longest < limits.OCR_MIN_SIDE:
        scale = limits.OCR_MIN_SIDE / longest
    else:
        scale = 1.0
    if scale != 1.0:
        grey = grey.resize((max(1, round(grey.width * scale)), max(1, round(grey.height * scale))), Image.Resampling.LANCZOS)
    out = io.BytesIO()
    grey.save(out, format="PNG")
    return ocr.read(out.getvalue())
