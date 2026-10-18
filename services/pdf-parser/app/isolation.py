"""Run one parse in a child process with a wall-clock and memory limit (AOS-626, AUD-22).

A thread can't be stopped in Python, so a file that makes pdfminer or openpyxl spin would hold the service for as
long as it likes. Each parse therefore runs in its own short-lived process, which is killed when the time is up. The
child also gets an address-space limit, so a file that inflates in memory fails with MemoryError instead of
exhausting the container.

`spawn` (not `fork`) starts the child: the server has threads, and forking a threaded process can deadlock. Results
come back over a one-way pipe as plain values: nothing of the file is logged, and errors carry only a code.
"""

import multiprocessing
from typing import Any, Callable

from app import limits
from app.models import ParseError

_ctx = multiprocessing.get_context("spawn")


class ParseTimeout(Exception):
    """The parse took longer than its time limit and was killed."""


class ParseCrashed(Exception):
    """The child died without an answer (out of memory, killed, interpreter crash). Carries no detail."""


class ParseFailed(Exception):
    """The parse raised something other than `ParseError`. Carries only the exception's type name."""

    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


def _child(conn, target: Callable[..., Any], args: tuple, memory_bytes: int | None) -> None:  # pragma: no cover - runs in the child
    try:
        if memory_bytes:
            import resource

            resource.setrlimit(resource.RLIMIT_AS, (memory_bytes, memory_bytes))
        conn.send(("ok", target(*args)))
    except ParseError as e:
        conn.send(("parse_error", e.code, e.message))
    except BaseException as e:  # noqa: BLE001 - the type name only: messages can quote the file
        conn.send(("failed", type(e).__name__))
    finally:
        conn.close()


def run_isolated(target: Callable[..., Any], args: tuple, timeout: float | None = None,
                 memory_bytes: int | None = limits.MAX_MEMORY_BYTES) -> Any:
    """Runs `target(*args)` in a child process and returns its (picklable) result.

    Raises the child's `ParseError` as-is, `ParseTimeout` after `timeout` seconds (the child is killed),
    `ParseCrashed` if the child dies without answering, and `ParseFailed(<type name>)` for any other failure.
    """
    timeout = limits.PARSE_TIMEOUT_SECONDS if timeout is None else timeout
    receiver, sender = _ctx.Pipe(duplex=False)
    process = _ctx.Process(target=_child, args=(sender, target, args, memory_bytes), daemon=True)
    process.start()
    sender.close()  # the child holds the only writer: EOF means it died
    try:
        if not receiver.poll(timeout):
            raise ParseTimeout()
        try:
            answer = receiver.recv()
        except EOFError:
            raise ParseCrashed() from None
    finally:
        receiver.close()
        if process.is_alive():
            process.kill()
        process.join(5)
    kind = answer[0]
    if kind == "ok":
        return answer[1]
    if kind == "parse_error":
        raise ParseError(answer[1], answer[2])
    raise ParseFailed(answer[1])
