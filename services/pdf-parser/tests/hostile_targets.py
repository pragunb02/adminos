"""Functions the isolation tests run in a child process (spawn needs them importable by module name)."""

import os
import time

from app.models import ParseError


def spin_forever():
    while True:
        time.sleep(0.05)


def die():
    os._exit(3)


def allocate(n_bytes: int):
    return len(bytearray(n_bytes))


def refuse():
    raise ParseError("NO_TRANSACTIONS", "No transactions found")


def fail_with_content():
    raise ValueError("Secret Narration SWIGGY 249")


def echo(value):
    return value
