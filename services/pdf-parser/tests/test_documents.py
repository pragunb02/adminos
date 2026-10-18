"""Reading vault documents (AOS-717): types, OCR, hostile files, limits, no network, and what is logged. All synthetic."""

import io
import logging
import os
import socket
import stat
import time

import pytest
from fastapi.testclient import TestClient
from fpdf import FPDF
from PIL import Image

from app import limits
from app.documents import detect, normalise, read_document
from app.isolation import ParseCrashed, ParseFailed, ParseTimeout
from app.main import app
from app.models import ParseError
from app.ocr import TesseractOcr
from tests import fixtures as fx
from tests.doc_eval.synthetic import SyntheticDoc, render

client = TestClient(app)
SECRET = "Narration Secret 4111"


def post(data: bytes, name: str = "doc.bin"):
    return client.post("/v1/documents:read", files={"file": (name, data)})


def doc(rendering: str, lines=None) -> bytes:
    lines = lines or ["Example Mutual General Insurance Company Limited", "Policy No.: 9876543210", "Valid till: 31/03/2027",
                      "Total Premium: Rs. 12,500.00"]
    return render(SyntheticDoc("t", "motor_policy", rendering, lines, {}))


def text_pdf(pages: list[str]) -> bytes:
    pdf = FPDF()
    pdf.set_font("Helvetica", size=11)
    for text in pages:
        pdf.add_page()
        pdf.multi_cell(0, 7, text)
    return bytes(pdf.output())


def fields(result: dict) -> dict:
    return {p["field"]: p["value"] for p in result["proposals"]}


# ── Happy paths ──
def test_a_text_layer_pdf_is_read_exactly_without_ocr():
    class NoOcr:
        name = "none"

        def read(self, png):
            raise AssertionError("OCR must not run on a page with a text layer")

    r = read_document(doc("text_pdf"), ocr=NoOcr())
    assert (r["kind"], r["source"], r["pagesRead"], r["truncated"]) == ("pdf", "text_layer", 1, False)
    assert fields(r) == {"expiresOn": "2027-03-31", "number": "9876543210",
                         "issuer": "Example Mutual General Insurance Company Limited", "amount": "12500.00"}


@pytest.mark.parametrize("rendering", ["scan_pdf", "png", "photo_jpeg"])
def test_scans_and_photos_are_read_by_ocr(rendering):
    r = read_document(doc(rendering))
    assert r["source"] == "ocr"
    assert fields(r)["expiresOn"] == "2027-03-31"


def test_webp_is_read_and_exif_rotation_is_undone():
    img = Image.open(io.BytesIO(doc("png")))
    out = io.BytesIO()
    exif = Image.Exif()
    exif[0x0112] = 6  # stored rotated: the camera says "turn 90° clockwise to view"
    img.rotate(90, expand=True).save(out, format="WEBP", exif=exif.tobytes(), lossless=True)
    r = read_document(out.getvalue())
    assert r["kind"] == "webp" and fields(r)["expiresOn"] == "2027-03-31"


def test_only_the_first_pages_are_read_and_a_longer_file_says_so():
    pages = [f"Page {i} of the policy wordings with enough text to count as a text layer." for i in range(7)]
    r = read_document(text_pdf(pages))
    assert (r["pagesTotal"], r["pagesRead"], r["truncated"]) == (7, limits.MAX_DOC_PAGES_READ, True)
    assert "Page 6" not in r["text"]


def test_text_is_normalised_and_capped():
    # fullwidth letters and digits, a zero-width space, CRLF, a bell, blank-line runs, a bidi override, spaces and tabs
    assert normalise("\uff30\uff4f\uff4c\uff49\uff43\uff59\u200b\uff2e\uff4f \uff11\uff12\uff13\r\n\x07\n\n\n\u202eX  Y\t ") == "PolicyNo 123\n\nX Y"
    long = text_pdf(["Valid till 31/03/2027 " + "word " * 3000] * 2)
    r = read_document(long)
    assert len(r["text"]) <= limits.MAX_TEXT_CHARS and r["truncated"]


# ── Types and hostile files ──
@pytest.mark.parametrize("data,kind", [(b"%PDF-1.7", "pdf"), (b"\x89PNG\r\n\x1a\n....", "png"), (b"\xff\xd8\xff\xe0", "jpeg"),
                                       (b"RIFF\x00\x00\x00\x00WEBPVP8 ", "webp"), (b"\x00\x00\x00\x18ftypheic", "heic"), (b"GIF89a", None),
                                       (b"PK\x03\x04", None), (b"", None)])
def test_types_come_from_the_first_bytes(data, kind):
    assert detect(data) == kind


@pytest.mark.parametrize("data,code", [
    (b"", "EMPTY_FILE"), (b"GIF89a" + b"\x00" * 20, "UNSUPPORTED_FORMAT"), (b"\x00\x00\x00\x18ftypheic" + b"\x00" * 20, "UNSUPPORTED_IMAGE"),
    (b"%PDF-1.7\n" + b"garbage " * 50, "UNSUPPORTED_FORMAT"), (b"\x89PNG\r\n\x1a\n" + b"\x00" * 50, "UNSUPPORTED_FORMAT"),
    (b"\xff\xd8\xff\xe0" + b"\x13" * 60, "UNSUPPORTED_FORMAT"),
])
def test_bad_files_fail_closed_with_a_code(data, code):
    with pytest.raises(ParseError) as e:
        read_document(data)
    assert e.value.code == code


def test_a_decompression_bomb_is_refused_before_it_is_decoded():
    out = io.BytesIO()
    Image.new("1", (9000, 6000)).save(out, format="PNG")  # 54 MP in a few kB
    started = time.monotonic()
    with pytest.raises(ParseError) as e:
        read_document(out.getvalue())
    assert e.value.code == "IMAGE_TOO_LARGE" and time.monotonic() - started < 2


def test_a_scanned_page_the_size_of_a_wall_is_refused_and_a_large_one_is_rendered_smaller():
    def blank(w_pt, h_pt):
        pdf = FPDF(unit="pt", format=(w_pt, h_pt))
        pdf.add_page()
        return bytes(pdf.output())

    with pytest.raises(ParseError) as e:
        read_document(blank(14400, 14400))  # 200 × 200 inches
    assert e.value.code == "IMAGE_TOO_LARGE"
    seen = []

    class Size:
        name = "size"

        def read(self, png):
            seen.append(Image.open(io.BytesIO(png)).size)
            return ""

    read_document(blank(2880, 2880), ocr=Size())  # 40 × 40 inches: rendered below the pixel cap, then scaled for OCR
    assert max(seen[0]) <= limits.OCR_MAX_SIDE


def test_a_password_protected_pdf_is_refused():
    with pytest.raises(ParseError) as e:
        read_document(fx.axis_pdf(password="01011990"))
    assert e.value.code == "PASSWORD_PROTECTED"


def test_a_pdf_over_the_page_cap_is_refused():
    pdf = FPDF()
    for _ in range(limits.MAX_PAGES + 1):
        pdf.add_page()
    with pytest.raises(ParseError) as e:
        read_document(bytes(pdf.output()))
    assert e.value.code == "TOO_MANY_PAGES"


# ── OCR process ──
def script(tmp_path, body: str) -> str:
    path = tmp_path / "fake-ocr"
    path.write_text("#!/bin/sh\n" + body + "\n")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return str(path)


def test_ocr_missing_is_a_clear_code():
    with pytest.raises(ParseError) as e:
        TesseractOcr(binary="").read(b"png")
    assert e.value.code == "OCR_UNAVAILABLE"


def test_an_ocr_run_past_its_time_is_killed(tmp_path):
    started = time.monotonic()
    with pytest.raises(ParseError) as e:
        TesseractOcr(binary=script(tmp_path, "sleep 10"), timeout=0.5).read(b"png")
    assert e.value.code == "OCR_TIMEOUT" and time.monotonic() - started < 5


def test_an_ocr_failure_never_carries_its_stderr(tmp_path):
    with pytest.raises(ParseError) as e:
        TesseractOcr(binary=script(tmp_path, f"echo '{SECRET}' >&2; exit 1")).read(b"png")
    assert e.value.code == "UNREADABLE" and SECRET not in e.value.message


def test_ocr_gets_a_fixed_environment_and_no_shell(tmp_path, monkeypatch):
    monkeypatch.setenv("HTTPS_PROXY", "http://example.invalid:3128")
    out = TesseractOcr(binary=script(tmp_path, 'echo "$HTTPS_PROXY|$OMP_THREAD_LIMIT|$1 $2"')).read(b"png")
    assert out.strip() == "|1|stdin stdout"
    with pytest.raises(ValueError):
        TesseractOcr(language="eng; rm -rf /")


def test_reading_needs_no_network(monkeypatch):
    def refuse(*_a, **_k):
        raise AssertionError("the document reader must not open a network connection")

    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket, "create_connection", refuse)
    assert fields(read_document(doc("png")))["expiresOn"] == "2027-03-31"


# ── Route ──
def test_the_route_returns_text_and_proposals():
    res = post(doc("text_pdf"), "../../etc/passwd")
    assert res.status_code == 200
    body = res.json()
    assert set(body) == {"kind", "source", "pagesTotal", "pagesRead", "truncated", "text", "proposals"}
    assert {p["field"] for p in body["proposals"]} == {"expiresOn", "number", "issuer", "amount"}
    assert all(set(p) == {"field", "value", "evidence", "label", "rule"} for p in body["proposals"])


def test_the_route_refuses_oversized_files_and_never_echoes_content(caplog):
    assert post(b"%PDF-" + b"0" * limits.MAX_DOC_BYTES).status_code == 413
    with caplog.at_level(logging.INFO):
        res = post(b"GIF89a " + SECRET.encode())
    assert res.status_code == 422 and res.json()["error"]["code"] == "UNSUPPORTED_FORMAT"
    assert SECRET not in res.text and SECRET not in caplog.text


def test_the_route_logs_counts_never_the_text(caplog):
    with caplog.at_level(logging.INFO):
        assert post(doc("text_pdf", ["Valid till 31/03/2027", f"Policy No.: 9876543210 {SECRET}"])).status_code == 200
    assert "document read: kind=pdf" in caplog.text
    assert SECRET not in caplog.text and "9876543210" not in caplog.text


@pytest.mark.parametrize("failure,code", [(ParseTimeout(), "READ_TIMEOUT"), (ParseCrashed(), "UNREADABLE"), (ParseFailed("MemoryError"), "UNREADABLE"),
                                          (RuntimeError(SECRET), "UNREADABLE")])
def test_the_route_maps_child_failures_to_codes(monkeypatch, caplog, failure, code):
    import app.main as main

    def fail(*_a, **_k):
        raise failure

    monkeypatch.setattr(main, "run_isolated", fail)
    with caplog.at_level(logging.INFO):
        res = post(doc("png"))
    assert res.status_code == 422 and res.json()["error"]["code"] == code
    assert SECRET not in res.text and SECRET not in caplog.text


def test_the_route_runs_in_the_isolated_child_with_the_document_time_limit(monkeypatch):
    import app.main as main

    seen = {}
    real = main.run_isolated

    def spy(target, args, timeout=None, **kw):
        seen.update(target=target.__name__, timeout=timeout)
        return real(target, args, timeout, **kw)

    monkeypatch.setattr(main, "run_isolated", spy)
    assert post(doc("text_pdf")).status_code == 200
    assert seen == {"target": "read_document", "timeout": limits.DOC_TIMEOUT_SECONDS}


def test_the_ocr_binary_is_present_in_this_environment():
    assert TesseractOcr().available, "Tesseract must be installed where the parser runs (image and CI)"
    assert os.access(TesseractOcr().binary, os.X_OK)
