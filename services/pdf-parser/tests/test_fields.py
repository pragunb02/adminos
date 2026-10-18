"""Field rules (AOS-717): what is proposed, what isn't, and that every proposal is grounded in the text. Synthetic text."""

from datetime import date

import pytest

from app.fields import find_dates, propose
from tests.doc_eval.evaluate import sets


def by_field(text: str) -> dict:
    return {p.field: p for p in propose(text)}


@pytest.mark.parametrize("raw,expected", [
    ("31/03/2027", date(2027, 3, 31)), ("31-03-2027", date(2027, 3, 31)), ("31.03.2027", date(2027, 3, 31)),
    ("31 / 03 / 2027", date(2027, 3, 31)), ("2027-03-31", date(2027, 3, 31)), ("31 Mar 2027", date(2027, 3, 31)),
    ("31-Mar-2027", date(2027, 3, 31)), ("31st March, 2027", date(2027, 3, 31)), ("March 31, 2027", date(2027, 3, 31)),
    ("5 Sept 2026", date(2026, 9, 5)), ("01/04/2026.", date(2026, 4, 1)),
])
def test_dates_are_read_day_first_in_every_common_shape(raw, expected):
    assert [d.value for d in find_dates(f"on {raw} here")] == [expected]


@pytest.mark.parametrize("raw", ["31/02/2027", "13/13/2026", "01/01/1899", "01/01/2201", "12/2027", "1234567890", "Mayor 5 2026",
                                 "03/04/26", "2026-27", "Rs. 12.03.2027.5"])
def test_not_dates(raw):
    assert find_dates(raw) == []


def test_an_expiry_cue_takes_the_date_after_it_not_the_issue_date():
    p = by_field("Date of Issue: 02/04/2026\nValid till: 01/04/2027\nPrinted on 03/04/2026")["expiresOn"]
    assert (p.value, p.evidence, p.rule) == ("2027-04-01", "01/04/2027", "expiry_cue")


def test_labels_above_values_take_the_latest_date_below():
    text = "Date of Birth Date of Issue Date of Expiry\n17/07/1996 02/12/2021 01/12/2031"
    assert by_field(text)["expiresOn"].value == "2031-12-01"


def test_a_period_gives_its_later_date_even_when_wrapped():
    assert by_field("Period of Insurance: From 00:00 hrs on 01/04/2026 To\nMidnight of 31/03/2027")["expiresOn"].value == "2027-03-31"
    assert by_field("tenancy for 11 months from 1 February 2026 to\n31 December 2026.")["expiresOn"].value == "2026-12-31"


def test_salary_and_tax_periods_are_not_validity():
    assert "expiresOn" not in by_field("Period with the Employer: From 01-Apr-2025 To 31-Mar-2026\nAssessment Year: 2026-27")


def test_no_cue_no_date_proposed():
    assert "expiresOn" not in by_field("Date of Commencement: 01/01/2020\nNext Premium Due: 01/01/2027\nDOB 01/01/1990")


def test_numbers_follow_their_label_and_shape():
    assert by_field("Policy No.: 3001/12345678/00/000")["number"].value == "3001/12345678/00/000"
    assert by_field("Registration No.: KA 05 MN 1234 Fuel: Petrol")["number"].value == "KA 05 MN 1234"
    assert by_field("Passport No. Surname\nM2030432 PLACEHOLDER")["number"].value == "M2030432"
    assert by_field("Permanent Account Number\nABCZE1234F")["number"].value == "ABCZE1234F"


def test_a_label_never_adopts_a_stray_token():
    assert "number" not in by_field("Passport No.\n$6 104950")  # OCR read S as $: nothing rather than a part
    assert "number" not in by_field("Certificate No.: ABCDEFG\nAssessment Year: 2026-27")
    assert "number" not in by_field("Permanent Account Number\nPRFZLO436W")  # not a PAN shape, and not an "Account Number"


def test_the_policy_number_wins_over_the_registration_number():
    assert by_field("Registration No.: KA01AB1234\nPolicy No.: 9988776655")["number"].label == "Policy No."


def test_an_unlabelled_aadhaar_number_on_an_aadhaar_card_only():
    assert by_field("Unique Identification Authority of India\n2345 6789 0124")["number"].rule == "aadhaar_shape"
    assert "number" not in by_field("Invoice\n2345 6789 0124")
    assert "number" not in by_field("Aadhaar\nCard 4111 1111 1111 1111")  # a piece of a 16-digit card number isn't an Aadhaar


def test_issuers_are_known_names_as_printed_or_an_insurance_company_shape():
    assert by_field("INCOME TAX DEPARTMENT\nGOVT. OF INDIA")["issuer"].value == "INCOME TAX DEPARTMENT"
    assert by_field("Transport Department, Government of Kerala\nDriving Licence")["issuer"].value == "Transport Department, Government of Kerala"
    assert by_field("Example Mutual General Insurance Company Limited\nPolicy")["issuer"].rule == "insurer_shape"
    assert "issuer" not in by_field("Example Appliances Pvt. Ltd.\nWarranty Card")


def test_amounts_are_the_premium_or_fee_not_the_sum_insured():
    text = "Sum Insured: Rs. 5,00,000\nIDV: 4,50,000\nBasic premium: 9,000 GST @18%: 1,620\nTotal Premium (incl. GST): Rs. 10,620.00"
    p = by_field(text)["amount"]
    assert (p.value, p.evidence, p.label) == ("10620.00", "Rs. 10,620.00", "Total premium")
    assert by_field("Fees: Rs. 90.00")["amount"].value == "90.00"
    assert by_field("Amount Paid: Rs.74, 241.00")["amount"].value == "74241.00"  # OCR's space after a comma
    assert "amount" not in by_field("Premium due on 01/04/2027")  # a date isn't an amount
    assert "amount" not in by_field("Premium: 18% GST extra")


def test_every_proposal_is_grounded_in_the_text():
    from app.documents import normalise
    from tests.doc_eval.synthetic import generate

    for docs in [generate(5, 3), generate(6, 3, held_out=True)]:
        for d in docs:
            text = normalise("\n".join(d.lines + [f"{a} {b}" for a, b in d.columns]))
            for p in propose(text):
                assert p.evidence in text, (d.id, p)
                if p.field in ("number", "issuer"):
                    assert p.value == p.evidence


def test_hostile_text_is_handled_in_linear_time():
    import time

    texts = ["Valid till " + "1/" * 20000, "Policy No. " + "A-" * 20000, "Premium " + "1," * 20000, "From " + "a " * 20000 + " to",
             "valid till\n" * 5000, ("Insurance " * 5000) + "Company Limited"]
    for t in texts:
        started = time.monotonic()
        propose(t)
        assert time.monotonic() - started < 2.0, t[:30]


def test_the_sets_use_every_kind_and_rendering():
    from tests.doc_eval.synthetic import KINDS, RENDERINGS

    docs = [d for s in sets().values() for d in s]
    assert {d.kind for d in docs} == set(KINDS)
    assert {d.rendering for d in docs} == set(RENDERINGS)
