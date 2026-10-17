"""CAS reader (AOS-805): synthetic statements only (tests/cas_fixtures.py)."""

import json
import logging
import pathlib
import socket
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

import app.cas as cas
from app.main import app
from app.models import ParseError
from tests import cas_fixtures as cf
from tests import fixtures as fx

client = TestClient(app)
GOLDEN = pathlib.Path(__file__).parent / "golden" / "cas_two_folios.json"


def post(data: bytes, password: str | None = cf.PASSWORD, name: str = "cas.pdf"):
    return client.post("/v1/cas:parse", files={"file": (name, data)}, data={"password": password} if password else None)


def test_a_detailed_statement_matches_the_committed_golden_json():
    res = post(cf.two_folio_statement())
    assert res.status_code == 200, res.text
    assert res.json() == json.loads(GOLDEN.read_text())


def test_the_committed_synthetic_pdf_still_gives_the_golden_json():
    # The API's end-to-end test uploads this exact file to the real parser container (CasImportIntegrationTest).
    res = post((GOLDEN.parent / "cas_two_folios.pdf").read_bytes())
    assert res.status_code == 200 and res.json() == json.loads(GOLDEN.read_text())


def test_numbers_are_decimal_strings_units_signed_amounts_gross():
    body = post(cf.two_folio_statement()).json()
    flexi = body["folios"][0]["schemes"][0]
    redemption = flexi["transactions"][-1]
    assert redemption == {"date": "2025-06-15", "kind": "sell", "amount": "5500.00", "units": "-50.000", "nav": "110.0000", "balance": "150.000"}
    stamp = flexi["transactions"][1]
    assert stamp["kind"] == "stamp_duty" and stamp["amount"] == "0.50" and stamp["units"] is None
    assert [t["kind"] for t in flexi["transactions"]] == ["buy", "stamp_duty", "sip_buy", "stamp_duty", "sip_buy", "stamp_duty", "sell"]
    assert flexi["closingUnits"] == "150.000" and flexi["openingUnits"] == "0"
    assert flexi["plan"] == "direct" and body["folios"][1]["schemes"][0]["plan"] == "regular"


def test_the_printed_isin_is_kept_even_when_the_isin_database_does_not_know_it():
    body = post(cf.two_folio_statement()).json()
    assert [s["isin"] for f in body["folios"] for s in f["schemes"]] == ["INFZZ9000010", "INFZZ9000028"]
    assert cas.cams_detailed.isin_search is cas._isin_search, "the pinned casparser hook point moved: re-check on upgrade"


def test_folio_numbers_only_in_the_identifiers_block_and_personal_details_never_leave():
    res = post(cf.two_folio_statement())
    body = res.json()
    assert body["identifiers"] == {"folios": [{"ref": "f1", "value": "90001234 / 56"}, {"ref": "f2", "value": "80005678"}]}
    without_ids = json.dumps({k: v for k, v in body.items() if k != "identifiers"})
    for leaked in ("90001234", "80005678", "Test Investor", "investor@example.invalid", "+910000000000", "Example Road", "400001",
                   "Systematic Investment", "Stamp Duty", "Redemption", "PAN", "KYC"):
        assert leaked not in without_ids, f"{leaked!r} left the parser"


def test_password_errors_have_stable_codes_and_never_echo_the_password():
    pdf = cf.two_folio_statement()
    assert post(pdf, password=None).json()["error"]["code"] == "PASSWORD_REQUIRED"
    res = post(pdf, password=cf.WRONG)
    assert res.status_code == 422 and res.json()["error"]["code"] == "PASSWORD_INCORRECT"
    assert cf.WRONG not in res.text


def test_other_files_are_refused_by_code():
    assert post(b"Folio No: 90001234 not a pdf").json()["error"]["code"] == "NOT_A_PDF"
    bank = fx.axis_pdf(password=cf.PASSWORD)
    assert post(bank).json()["error"]["code"] == "NOT_A_CAS"
    summary = cf.cams_detailed([cf.FolioBlock("Example Mutual Fund", "90001234", [cf.liquid_fund()])], heading="Consolidated Account Summary")
    assert post(summary).json()["error"]["code"] == "CAS_SUMMARY_UNSUPPORTED"
    no_marker = cf.cams_detailed([cf.FolioBlock("Example Mutual Fund", "90001234", [cf.liquid_fund()])], marker="")
    assert post(no_marker).json()["error"]["code"] == "NOT_A_CAS"
    unencrypted = cf.cams_detailed([cf.FolioBlock("Example Mutual Fund", "90001234", [cf.liquid_fund()])], password=None)
    assert post(unencrypted, password="anything").status_code == 200


def test_depository_statements_and_unknown_issuers_are_refused():
    for file_type, code in (("NSDL", "CAS_DEPOSITORY_UNSUPPORTED"), ("CDSL", "CAS_DEPOSITORY_UNSUPPORTED"), ("UNKNOWN", "NOT_A_CAS")):
        with pytest.raises(ParseError) as e:
            cas._to_result(SimpleNamespace(file_type=file_type, cas_type="DETAILED"))
        assert e.value.code == code


def test_an_unrecognised_transaction_type_is_passed_on_as_unknown():
    txn = SimpleNamespace(date="2025-01-02", type="UNKNOWN", amount=None, units="5.5", nav=None, balance="5.5")
    scheme = SimpleNamespace(scheme="Example Fund - Growth", isin="not-an-isin", amfi="12x", type="N/A", open=0, close="5.5",
                             valuation=SimpleNamespace(date="31-Dec-2025", nav=0, value=0, cost=None), transactions=[txn])
    data = SimpleNamespace(file_type="KFINTECH", cas_type="DETAILED", folios=[SimpleNamespace(folio="7001", amc="<b>Example</b> MF", schemes=[scheme])],
                           statement_period=SimpleNamespace(from_="garbage", to="31-Dec-2025"), parse_warnings=["x", "y"])
    out = cas._to_result(data)
    s = out.folios[0].schemes[0]
    assert out.issuer == "kfintech" and out.parse_warnings == 2 and out.statement_from is None
    assert s.transactions[0].kind == "unknown" and s.isin is None and s.amfi_code is None and s.scheme_type is None and s.plan is None
    assert out.folios[0].amc == "bExample/b MF", "markup characters are stripped from public names"
    assert s.valuation_date.isoformat() == "2025-12-31" and s.nav is None


def test_too_many_pages_is_refused_before_parsing(monkeypatch):
    monkeypatch.setattr(cas, "MAX_PAGES", 0)
    assert post(cf.two_folio_statement()).json()["error"]["code"] == "TOO_MANY_PAGES"


def test_too_large(monkeypatch):
    import app.main as m
    monkeypatch.setattr(m, "MAX_BYTES", 10)
    assert post(b"%PDF-" + b"x" * 11).status_code == 413


def test_an_unexpected_failure_is_a_generic_error(monkeypatch):
    def boom(*_):
        raise RuntimeError("Folio 90001234 secret detail")
    monkeypatch.setattr("app.main.read_cas", boom)
    res = post(cf.two_folio_statement())
    assert res.json()["error"]["code"] == "UNREADABLE" and "90001234" not in res.text


def test_parsing_needs_no_network(monkeypatch):
    def no_network(*_, **__):
        raise AssertionError("the CAS reader opened a socket")
    monkeypatch.setattr(socket, "socket", no_network)
    monkeypatch.setattr(socket, "create_connection", no_network)
    assert cas.read_cas(cf.two_folio_statement(), cf.PASSWORD).issuer == "cams"


def test_the_category_comes_from_the_bundled_isin_database():
    # A public reference ISIN from the bundled database (not a holding): an arbitrage fund.
    assert cas._category("INF179K01319") == "Hybrid Scheme - Arbitrage Fund"
    assert cas._category("INFZZ9000010") is None and cas._category(None) is None


def test_logs_contain_no_folio_password_or_names(caplog):
    caplog.set_level(logging.INFO)
    post(cf.two_folio_statement())
    post(cf.two_folio_statement(), password=cf.WRONG)
    for secret in (cf.PASSWORD, cf.WRONG, "90001234", "Test Investor", "Flexi Cap", "Example Mutual Fund"):
        assert secret not in caplog.text, f"log leaked {secret!r}"
