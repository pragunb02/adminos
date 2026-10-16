import logging

from fastapi.testclient import TestClient

from app.main import app
from tests import fixtures as fx

client = TestClient(app)


def post(data: bytes, name: str, password: str | None = None):
    return client.post("/v1/statements:parse", files={"file": (name, data)}, data={"password": password} if password else None)


def test_parse_returns_camelcase_rows_with_decimal_strings():
    res = post(fx.hdfc_csv(), "s.csv")
    assert res.status_code == 200
    body = res.json()
    assert body["bankCode"] == "hdfc" and body["accountLast4"] == "1234" and body["periodStart"] == "2026-10-01"
    assert body["rows"][0] == {"date": "2026-10-01", "description": body["rows"][0]["description"], "debit": "249.00",
                               "credit": None, "balance": "49751.00", "reference": "0000123456789012"}


def test_errors_have_stable_codes_and_never_echo_content():
    pdf = fx.axis_pdf(password="01011990")
    assert post(pdf, "a.pdf").json()["error"]["code"] == "PASSWORD_REQUIRED"
    res = post(pdf, "a.pdf", password="wrong-pass-123")
    assert res.status_code == 422 and res.json()["error"]["code"] == "PASSWORD_INCORRECT"
    assert "wrong-pass-123" not in res.text
    res = post(b"Secret Narration SWIGGY 249\n", "x.csv")
    assert res.json()["error"]["code"] == "NO_TRANSACTIONS" and "SWIGGY" not in res.text


def test_too_large(monkeypatch):
    import app.main as m
    monkeypatch.setattr(m, "MAX_BYTES", 10)
    assert post(b"x" * 11, "big.csv").status_code == 413


def test_logs_contain_no_content_filenames_or_passwords(caplog):
    caplog.set_level(logging.INFO)
    post(fx.axis_pdf(password="01011990"), "Ravi_Statement_Oct.pdf", password="01011990")
    post(fx.hdfc_csv(), "Ravi_Statement_Oct.csv")
    logs = caplog.text
    for secret in ("01011990", "Ravi_Statement", "SWIGGY", "FLIPKART", "1234"):
        assert secret not in logs, f"log leaked {secret!r}: {logs}"
