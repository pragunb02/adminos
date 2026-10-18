"""Prints what the reader made of chosen synthetic documents, for working on the rules:
`python -m tests.doc_eval.show dev-pan-0 held-puc-0`. Synthetic documents only."""

import sys

from app.documents import read_document
from tests.doc_eval.evaluate import sets
from tests.doc_eval.synthetic import render


def main(ids):
    docs = {d.id: d for docs in sets().values() for d in docs}
    for i in ids:
        doc = docs[i]
        result = read_document(render(doc))
        print(f"== {i} [{doc.rendering}] source={result['source']}")
        print(result["text"])
        print("-- proposals:", result["proposals"])
        print("-- truth:", doc.truth)


if __name__ == "__main__":
    main(sys.argv[1:])
