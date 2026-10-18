"""How much one statement may make the parser do (AOS-626, AUD-22).

A statement is at most 20 MB, but a hostile file can still ask for far more work than its size suggests: a PDF with
thousands of pages, a spreadsheet whose sheet claims a million rows, a CSV of tiny rows. Every reader charges what it
reads to one `Budget`, and the whole parse runs in a child process with a wall-clock limit (`app.isolation`).

The caps are far above any real statement: a decade of a busy account is about 20 000 rows.
"""

from app.models import ParseError

MAX_PAGES = 300  # PDF pages
MAX_SHEETS = 50  # spreadsheet sheets
MAX_ROWS = 100_000  # rows across all tables and sheets
MAX_CELLS = 2_000_000  # cells across all tables and sheets (rows × columns)
PARSE_TIMEOUT_SECONDS = 30.0  # wall clock per file; the API waits 60 s
MAX_MEMORY_BYTES = 1024 * 1024 * 1024  # address space of the parsing child process


class Budget:
    """Rows and cells read so far from one file. Raises `ParseError` as soon as a cap is passed."""

    def __init__(self, max_rows: int | None = None, max_cells: int | None = None):
        self.max_rows = MAX_ROWS if max_rows is None else max_rows
        self.max_cells = MAX_CELLS if max_cells is None else max_cells
        self.rows = 0
        self.cells = 0

    def charge(self, cells: int) -> None:
        """One more row of `cells` cells."""
        self.rows += 1
        self.cells += cells
        if self.rows > self.max_rows or self.cells > self.max_cells:
            raise too_many_rows()

    def check_declared(self, rows: int, cols: int) -> None:
        """A sheet that declares its size up front (xls) is refused before it is read."""
        if self.rows + rows > self.max_rows or self.cells + rows * cols > self.max_cells:
            raise too_many_rows()


def too_many_rows():
    return ParseError("TOO_MANY_ROWS", "The statement has too many rows. Export a shorter period")


def too_many_pages():
    return ParseError("TOO_MANY_PAGES", f"The statement has more than {MAX_PAGES} pages. Export a shorter period")


def too_many_sheets():
    return ParseError("TOO_MANY_SHEETS", f"The workbook has more than {MAX_SHEETS} sheets")
