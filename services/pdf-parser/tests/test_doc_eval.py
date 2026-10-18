"""The documents eval gate (AOS-717): the reader on the synthetic set must meet the committed floor on every set.

The floor is in tests/doc_eval/baseline.json next to the measured baseline. It has a margin below the baseline
because CI's Tesseract (Ubuntu) can be another patch version than the image's (Debian). The research gate holds with
no margin: a proposed expiry date is right at least 95% of the time. Needs Tesseract (CI installs it, and so does the
image); without it the gate fails rather than skipping, because an untested OCR path must not pass.
"""

import json
import shutil

import pytest

from tests.doc_eval.evaluate import BASELINE, score, sets


@pytest.fixture(scope="module")
def report():
    assert shutil.which("tesseract"), "Tesseract is needed for the documents eval (apt-get install tesseract-ocr tesseract-ocr-eng)"
    return {name: score(docs) for name, docs in sets().items()}


def test_every_set_meets_the_floor(report):
    floor = json.loads(BASELINE.read_text())["floor"]
    assert floor["expiresOn.precision"] >= 0.95, "the research gate: expiry precision ≥ 0.95"
    below = [f"{name} {metric}: {r['metrics'][metric]} < {minimum}" for name, r in report.items()
             for metric, minimum in floor.items() if r["metrics"][metric] < minimum]
    assert not below, "\n".join(below + [f"  {f}" for r in report.values() for f in r["failures"]])


def test_the_baseline_records_every_set_and_metric(report):
    baseline = json.loads(BASELINE.read_text())
    assert set(baseline["sets"]) == set(report)
    for name, r in report.items():
        assert baseline["sets"][name]["documents"] == r["documents"]
        assert set(baseline["sets"][name]["metrics"]) == set(r["metrics"])


def test_a_text_layer_is_read_exactly(report):
    for name, r in report.items():
        assert r["metrics"]["ocrSurvival.text_pdf"] == 1.0, name


def test_the_sets_are_synthetic_and_never_real():
    from tests.doc_eval.synthetic import NAMES, verhoeff_valid

    for docs in sets().values():
        for d in docs:
            n = d.truth.get("number") or ""
            if d.kind == "aadhaar":
                assert not verhoeff_valid(n.replace(" ", "")), "an Aadhaar-shaped number must fail the Verhoeff check"
            if d.kind == "pan":
                assert n[3] == "Z", "PAN-shaped numbers use the unassigned holder type Z"
            assert not any(len(w) > 2 and w.islower() and "@" in w for w in " ".join(d.lines).split()), "no emails"
    assert all(any(tag in name for tag in ("Example", "Sample", "Placeholder", "Testcase", "Demo", "Dummy")) for name in NAMES)
