from datetime import date
from decimal import Decimal

import pytest

from app.models import ParseError
from app.parser import parse_statement
from app.table_mapper import balances_reconcile, parse_amount, parse_date
from tests import fixtures as fx

D = Decimal


def test_hdfc_csv_maps_columns_merges_narration_and_skips_summary():
    r = parse_statement(fx.hdfc_csv(), "Acct_Statement.csv")
    assert r.bank_code == "hdfc" and r.account_last4 == "1234"
    assert [x.date for x in r.rows] == [date(2026, 10, 1), date(2026, 10, 2), date(2026, 10, 3)]
    swiggy, salary, amazon = r.rows
    assert swiggy.debit == D("249.00") and swiggy.credit is None
    assert swiggy.description.endswith("food delivery"), "continuation line appended"
    assert salary.credit == D("100000.00") and salary.balance == D("149751.00")
    assert amazon.debit == D("1299.50")
    assert r.period_start == date(2026, 10, 1) and r.period_end == date(2026, 10, 3)
    assert r.warnings == []  # balances reconcile


def test_sbi_xlsx_with_month_name_dates():
    r = parse_statement(fx.sbi_xlsx(), "statement.xlsx")
    assert r.bank_code == "sbi"
    assert [(x.debit, x.credit) for x in r.rows] == [(D("450.00"), None), (None, D("450.00")), (D("2000.00"), None)]
    assert r.rows[0].date == date(2026, 10, 1)


def test_icici_layout_with_inr_headers_and_zero_columns():
    from app.table_mapper import extract
    rows, _, warnings = extract([fx.icici_rows()])
    assert [(x.debit, x.credit) for x in rows] == [(D("649.00"), None), (None, D("12.34"))]
    assert rows[0].date == date(2026, 10, 5)


def test_single_amount_with_dr_cr_column_and_unknown_direction_skipped():
    r = parse_statement(fx.kotak_drcr_csv(), "kotak.csv")
    assert r.bank_code == "kotak"
    assert [(x.debit, x.credit) for x in r.rows] == [(D("320.00"), None), (None, D("1500.00"))]
    assert r.skipped_rows >= 1, "a row with no Dr/Cr must not be guessed"


def test_password_protected_pdf():
    data = fx.axis_pdf(password="01011990")
    with pytest.raises(ParseError) as e:
        parse_statement(data, "axis.pdf")
    assert e.value.code == "PASSWORD_REQUIRED"
    with pytest.raises(ParseError) as e:
        parse_statement(data, "axis.pdf", password="wrong")
    assert e.value.code == "PASSWORD_INCORRECT"
    r = parse_statement(data, "axis.pdf", password="01011990")
    assert r.bank_code == "axis" and r.account_last4 == "9876"
    assert [(x.debit, x.credit) for x in r.rows] == [(D("199.00"), None), (None, D("2000.00")), (D("1299.00"), None)]


def test_unprotected_pdf():
    r = parse_statement(fx.axis_pdf(), "axis.pdf")
    assert len(r.rows) == 3


def test_not_a_statement():
    with pytest.raises(ParseError) as e:
        parse_statement(b"name,age\nravi,30\n", "people.csv")
    assert e.value.code == "NO_TRANSACTIONS"


@pytest.mark.parametrize("raw,expected", [
    ("1,23,456.78", (D("123456.78"), None)),
    ("1,234.00 Cr", (D("1234.00"), "credit")),
    ("500.00Dr", (D("500.00"), "debit")),
    ("(500.00)", (D("500.00"), "debit")),
    ("-75", (D("75.00"), "debit")),
    ("INR 99.5", (D("99.50"), None)),
    ("₹ 10", (D("10.00"), None)),
    ("0.00", (None, None)),
    ("", (None, None)),
    ("abc", (None, None)),
])
def test_parse_amount(raw, expected):
    assert parse_amount(raw) == expected


@pytest.mark.parametrize("raw,expected", [
    ("01/10/26", date(2026, 10, 1)), ("01/10/2026", date(2026, 10, 1)), ("1 Oct 2026", date(2026, 10, 1)),
    ("01-Oct-26", date(2026, 10, 1)), ("2026-10-01", date(2026, 10, 1)), ("01.10.2026", date(2026, 10, 1)),
    ("01/10/2026 14:05:00", date(2026, 10, 1)), ("not a date", None), ("", None),
])
def test_parse_date(raw, expected):
    assert parse_date(raw) == expected


def test_balance_reconciliation_flags_misread_statements():
    good = parse_statement(fx.hdfc_csv(), "a.csv").rows
    assert balances_reconcile(good) is True
    assert balances_reconcile(list(reversed(good))) is True, "newest-first statements reconcile too"
    bad = [good[0], good[1].model_copy(update={"credit": D("1.00")}), good[2]]
    assert balances_reconcile(bad) is False
