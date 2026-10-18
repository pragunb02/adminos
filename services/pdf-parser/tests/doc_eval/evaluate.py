"""Scores the document reader on the synthetic set (AOS-717). Run `python -m tests.doc_eval.evaluate` to print the
report, `--write` to record it as the committed baseline (tests/doc_eval/baseline.json).

Per field:
- `recall`: documents with the field whose proposal is exactly right ÷ documents with the field;
- `precision`: right proposals ÷ all proposals (a wrong proposal is worse than none);
- `false`: wrong proposals, counted (including a proposal on a document that has no such field).
Also, per rendering, `ocrSurvival`: of the true values, the share found verbatim in the text read (OCR quality on its
own, apart from the rules). The research gate: expiry precision ≥ 0.95 on accepted proposals.

Three sets:
- `dev` (seed 717): the set the rules were written against;
- `heldOut` (seed 7170, with label and layout variants dev doesn't have): its first-run numbers are in the ticket, but
  its failures were then looked at while fixing the rules, so it is no longer unseen;
- `fresh` (seed 71701, the same held-out variants): generated and scored only once, after the rules were final.
  It is the honest held-out number.
"""

import json
import re
import shutil
import subprocess
import sys
import time
from collections import defaultdict
from decimal import Decimal
from pathlib import Path
from typing import Dict, List

from app.documents import read_document
from app.fields import FIELDS
from tests.doc_eval.synthetic import RENDERINGS, SyntheticDoc, generate, render

BASELINE = Path(__file__).with_name("baseline.json")
DEV_SEED, HELD_OUT_SEED, FRESH_SEED = 717, 7170, 71701
DEV_PER_KIND, HELD_OUT_PER_KIND, FRESH_PER_KIND = 4, 2, 3


def sets() -> Dict[str, List[SyntheticDoc]]:
    return {
        "dev": generate(DEV_SEED, DEV_PER_KIND),
        "heldOut": generate(HELD_OUT_SEED, HELD_OUT_PER_KIND, held_out=True, prefix="held"),
        "fresh": generate(FRESH_SEED, FRESH_PER_KIND, held_out=True, prefix="fresh"),
    }


def _same(field: str, proposed: str, truth: str) -> bool:
    if field == "amount":
        return Decimal(proposed) == Decimal(truth)
    if field == "issuer":
        return " ".join(proposed.split()).lower() == " ".join(truth.split()).lower()
    return proposed == truth


def _squash(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip().lower()


def score(docs: List[SyntheticDoc]) -> dict:
    per_field = {f: {"truths": 0, "proposals": 0, "right": 0} for f in FIELDS}
    survival = defaultdict(lambda: [0, 0])
    failures = []
    started = time.monotonic()
    for doc in docs:
        result = read_document(render(doc))
        proposals = {p["field"]: p for p in result["proposals"]}
        text = _squash(result["text"])
        for f in FIELDS:
            truth, p = doc.truth.get(f), proposals.get(f)
            c = per_field[f]
            c["truths"] += truth is not None
            if p is not None:
                c["proposals"] += 1
                if truth is not None and _same(f, p["value"], truth):
                    c["right"] += 1
                else:
                    failures.append(f"{doc.id} [{doc.rendering}] {f}: proposed {p['value']!r} ({p['rule']}), truth {truth!r}")
            elif truth is not None:
                failures.append(f"{doc.id} [{doc.rendering}] {f}: missed {truth!r}")
            if truth is not None and f in ("number", "issuer"):
                survival[doc.rendering][1] += 1
                survival[doc.rendering][0] += _squash(truth) in text
    metrics = {}
    for f, c in per_field.items():
        metrics[f"{f}.recall"] = round(c["right"] / c["truths"], 4) if c["truths"] else 1.0
        metrics[f"{f}.precision"] = round(c["right"] / c["proposals"], 4) if c["proposals"] else 1.0
        metrics[f"{f}.false"] = c["proposals"] - c["right"]
    for r in RENDERINGS:
        found, total = survival[r]
        if total:
            metrics[f"ocrSurvival.{r}"] = round(found / total, 4)
    return {"documents": len(docs), "metrics": metrics, "seconds": round(time.monotonic() - started, 1), "failures": failures}


def engine_version() -> str:
    binary = shutil.which("tesseract")
    if not binary:
        return "none"
    out = subprocess.run([binary, "--version"], capture_output=True, text=True, check=False)
    return (out.stdout or out.stderr).splitlines()[0].strip() if (out.stdout or out.stderr) else "unknown"


def main(argv: List[str]) -> int:
    report = {name: score(docs) for name, docs in sets().items()}
    for name, r in report.items():
        print(f"== {name}: {r['documents']} documents in {r['seconds']} s")
        for k, v in r["metrics"].items():
            print(f"  {k:28} {v}")
        for f in r["failures"]:
            print(f"  - {f}")
    if "--write" in argv:
        old = json.loads(BASELINE.read_text()) if BASELINE.exists() else {}
        BASELINE.write_text(json.dumps({
            "feature": "documents-ocr-v1",
            "engine": engine_version(),
            "evaluatedOn": time.strftime("%Y-%m-%d"),
            "sets": {name: {"documents": r["documents"], "metrics": r["metrics"]} for name, r in report.items()},
            "floor": old.get("floor", {}),
        }, indent=2) + "\n")
        print(f"wrote {BASELINE}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
