"""Synthetic vault documents for the AOS-717 eval. Generated, never real.

Each document is a list of lines plus the truth for each field (None when the document has no such field: a PAN
card has no expiry). It is rendered four ways, because each reaches the reader differently:
- `text_pdf`: a PDF with a text layer, like an insurer's e-policy;
- `scan_pdf`: a PDF holding only a page image, like a scanner's output;
- `png`: a clean screenshot or export;
- `photo_jpeg`: a phone photo (slight rotation, blur, noise, JPEG artefacts).

**Never real:** names, addresses and numbers are random or obviously made up.
- Aadhaar-shaped numbers fail the Verhoeff check, so none can be a real Aadhaar.
- PAN-shaped numbers use the holder-type letter Z, which no PAN has.
- Other numbers are random digits.
"""

import io
import random
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Dict, List, Optional

KINDS = ["motor_policy", "health_policy", "life_policy", "puc", "passport", "pan", "aadhaar", "licence", "rc", "warranty", "rent", "form16"]
RENDERINGS = ["text_pdf", "scan_pdf", "png", "photo_jpeg"]
CARD_KINDS = {"passport", "pan", "aadhaar", "licence"}  # photographed cards, never e-PDFs

NAMES = ["Asha Example", "Kabir Sample", "Meera Placeholder", "Dev Testcase", "Rohan Demo", "Isha Dummy"]
CITIES = ["Bengaluru", "Pune", "Jaipur", "Kochi", "Indore", "Nagpur"]
STATES = {"KA": "Karnataka", "MH": "Maharashtra", "RJ": "Rajasthan", "KL": "Kerala", "MP": "Madhya Pradesh"}
MOTOR_INSURERS = ["HDFC ERGO General Insurance Company Limited", "ICICI Lombard General Insurance Company Limited",
                  "Tata AIG General Insurance Company Limited", "The New India Assurance Co. Ltd.", "Bajaj General Insurance Limited",
                  "Go Digit General Insurance Limited", "Acko General Insurance Limited", "SBI General Insurance Company Limited"]
HEALTH_INSURERS = ["Star Health and Allied Insurance Co. Ltd.", "Niva Bupa Health Insurance Company Limited", "Care Health Insurance Limited",
                   "Aditya Birla Health Insurance Co. Limited", "ManipalCigna Health Insurance Company Limited"]
LIFE_INSURERS = ["Life Insurance Corporation of India", "HDFC Life Insurance Company Limited", "ICICI Prudential Life Insurance Company Limited",
                 "SBI Life Insurance Company Limited", "Tata AIA Life Insurance Company Limited"]
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
LONG_MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]


@dataclass
class SyntheticDoc:
    id: str
    kind: str
    rendering: str
    lines: List[str]
    truth: Dict[str, Optional[str]]
    held_out: bool = False
    columns: List[List[str]] = field(default_factory=list)  # label/value rows drawn as columns (cards)


# ── Values ──
_VERHOEFF_D = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5], [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
               [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1], [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
               [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]]
_VERHOEFF_P = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4], [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
               [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1], [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]]


def verhoeff_valid(digits: str) -> bool:
    c = 0
    for i, ch in enumerate(reversed(digits)):
        c = _VERHOEFF_D[c][_VERHOEFF_P[i % 8][int(ch)]]
    return c == 0


def fake_aadhaar(rng: random.Random) -> str:
    while True:
        n = str(rng.randint(2, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(11))
        if not verhoeff_valid(n):
            return f"{n[:4]} {n[4:8]} {n[8:]}"


def fake_pan(rng: random.Random) -> str:
    letters = "ABCDEFGHJKLMNPRSTUVWXY"
    return "".join(rng.choice(letters) for _ in range(3)) + "Z" + rng.choice(letters) + "".join(str(rng.randint(0, 9)) for _ in range(4)) + rng.choice(letters)


def digits(rng: random.Random, n: int) -> str:
    return str(rng.randint(1, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(n - 1))


def registration(rng: random.Random, spaced: bool) -> str:
    state = rng.choice(list(STATES))
    parts = [state, f"{rng.randint(1, 60):02d}", "".join(rng.choice("ABCDEFGHJKLMNPRSTUVWXYZ") for _ in range(2)), f"{rng.randint(1, 9999):04d}"]
    return " ".join(parts) if spaced else "".join(parts)


def fmt_date(d: date, style: str) -> str:
    return {
        "dmy_slash": f"{d.day:02d}/{d.month:02d}/{d.year}",
        "dmy_dash": f"{d.day:02d}-{d.month:02d}-{d.year}",
        "dmy_dot": f"{d.day:02d}.{d.month:02d}.{d.year}",
        "d_mon_y": f"{d.day:02d} {MONTHS[d.month - 1]} {d.year}",
        "d_mon_y_dash": f"{d.day:02d}-{MONTHS[d.month - 1]}-{d.year}",
        "mon_d_y": f"{LONG_MONTHS[d.month - 1]} {d.day}, {d.year}",
        "d_month_y": f"{d.day} {LONG_MONTHS[d.month - 1]} {d.year}",
    }[style]


def inr(amount: int, rng: random.Random) -> str:
    s = str(amount)
    head, tail = s[:-3], s[-3:]
    groups = []
    while len(head) > 2:
        groups.insert(0, head[-2:])
        head = head[:-2]
    if head:
        groups.insert(0, head)
    grouped = ",".join(groups + [tail]) if groups else tail
    return rng.choice(["Rs. ", "Rs.", "INR ", ""]) + grouped + rng.choice([".00", "", ".00"])


def _day(rng: random.Random, start: date, span_days: int) -> date:
    return start + timedelta(days=rng.randint(0, span_days))


# ── Templates ── (truth uses canonical values: ISO date, the number as printed, the issuer as printed, decimal amount)
def _motor(rng, held_out):
    insurer = rng.choice(MOTOR_INSURERS)
    start = _day(rng, date(2026, 1, 1), 300)
    end = start + timedelta(days=364)
    style = rng.choice(["dmy_slash", "dmy_dash", "d_mon_y", "d_mon_y_dash"])
    number = rng.choice([f"{digits(rng, 4)}/{digits(rng, 8)}/00/000", f"OG-{start.year % 100}-{digits(rng, 4)}-{digits(rng, 4)}-{digits(rng, 8)}", digits(rng, 10)])
    reg = registration(rng, spaced=rng.random() < 0.5)
    total = rng.randint(4500, 32000)
    period = rng.choice([f"Period of Insurance: From 00:00 hrs on {fmt_date(start, style)} To Midnight of {fmt_date(end, style)}",
                         f"Policy Period: {fmt_date(start, style)} to {fmt_date(end, style)}"]
                        + ([f"Insurance Period {fmt_date(start, style)} - {fmt_date(end, style)}",
                            f"Cover valid from {fmt_date(start, style)} to {fmt_date(end, style)}"] if held_out else []))
    lines = [insurer, "Private Car Package Policy - Certificate of Insurance cum Policy Schedule",
             f"Policy No.: {number}", f"Insured: {rng.choice(NAMES)}, {rng.choice(CITIES)}",
             f"Registration No.: {reg}  Make/Model: Example Motors Hatch 1.2", period,
             f"Date of Issue: {fmt_date(start - timedelta(days=rng.randint(1, 5)), style)}",
             f"IDV: {inr(rng.randint(300000, 900000), rng)}   Sum Insured (Own Damage): {inr(rng.randint(300000, 900000), rng)}",
             f"Basic OD Premium: {inr(total * 6 // 10, rng)}   GST @18%: {inr(total * 15 // 100, rng)}",
             f"Total Premium (incl. GST): {inr(total, rng)}" if not held_out else f"Total Policy Premium: {inr(total, rng)}",
             "Subject to IMT endorsements and the terms of the policy wordings."]
    return lines, {"expiresOn": end.isoformat(), "number": number, "issuer": insurer, "amount": f"{total}.00"}


def _health(rng, held_out):
    insurer = rng.choice(HEALTH_INSURERS)
    start = _day(rng, date(2026, 1, 1), 300)
    end = start + timedelta(days=364)
    style = rng.choice(["dmy_slash", "d_mon_y", "dmy_dot"])
    number = rng.choice([f"P/{digits(rng, 6)}/01/{start.year}/{digits(rng, 6)}", f"{digits(rng, 2)}-{digits(rng, 2)}-{digits(rng, 7)}-00", digits(rng, 12)])
    total = rng.randint(9000, 48000)
    lines = [insurer, "Family Floater Health Insurance - Policy Schedule",
             f"Policy Number: {number}", f"Proposer: {rng.choice(NAMES)}  Date of Birth: {fmt_date(date(rng.randint(1975, 1999), rng.randint(1, 12), rng.randint(1, 28)), style)}",
             f"Policy Period: {fmt_date(start, style)} to {fmt_date(end, style)}" if not held_out
             else f"Policy valid from {fmt_date(start, style)} to {fmt_date(end, style)}",
             f"Sum Insured: {inr(rng.choice([500000, 1000000, 1500000]), rng)}  Cumulative Bonus: {inr(rng.randint(10000, 90000), rng)}",
             f"Premium Paid (incl. taxes): {inr(total, rng)}", "Cashless at network hospitals. Waiting periods apply."]
    return lines, {"expiresOn": end.isoformat(), "number": number, "issuer": insurer, "amount": f"{total}.00"}


def _life(rng, held_out):
    insurer = rng.choice(LIFE_INSURERS)
    start = _day(rng, date(2018, 1, 1), 2500)
    style = rng.choice(["dmy_slash", "d_mon_y"])
    number = digits(rng, 9)
    premium = rng.randint(12000, 90000)
    lines = [insurer, "Policy Bond - Endowment Plan",
             f"Policy Number: {number}", f"Life Assured: {rng.choice(NAMES)}",
             f"Date of Commencement: {fmt_date(start, style)}", f"Date of Maturity: {fmt_date(start.replace(year=start.year + 20), style)}",
             f"Next Premium Due: {fmt_date(date(2027, start.month, min(start.day, 28)), style)}",
             f"Annual Premium: {inr(premium, rng)}", f"Sum Assured: {inr(rng.randint(10, 50) * 100000, rng)}"]
    return lines, {"expiresOn": None, "number": number, "issuer": insurer, "amount": f"{premium}.00"}


def _puc(rng, held_out):
    code = rng.choice(list(STATES))
    tested = _day(rng, date(2026, 1, 1), 280)
    end = tested + timedelta(days=rng.choice([182, 364]))
    style = rng.choice(["dmy_slash", "dmy_dash", "d_mon_y"])
    cert = f"{code}{digits(rng, 2)}{digits(rng, 9)}"
    fee = rng.choice([60, 80, 90, 100, 110])
    issuer = f"Transport Department, Government of {STATES[code]}"
    lines = [issuer, "POLLUTION UNDER CONTROL CERTIFICATE", f"Certificate SL. No.: {cert}",
             f"Registration No.: {registration(rng, spaced=True)}   Fuel: Petrol",
             f"Date of Test: {fmt_date(tested, style)}   Time: {rng.randint(9, 18):02d}:{rng.randint(0, 59):02d}",
             (f"Validity upto: {fmt_date(end, style)}" if not held_out else f"Valid till {fmt_date(end, style)}"),
             f"CO: 0.{rng.randint(10, 99)} %   HC: {rng.randint(50, 200)} ppm", f"Fees: Rs. {fee}.00",
             f"Testing Centre: Example Fuels PUC Centre, {rng.choice(CITIES)}"]
    return lines, {"expiresOn": end.isoformat(), "number": cert, "issuer": issuer, "amount": f"{fee}.00"}


def _passport(rng, held_out):
    issue = _day(rng, date(2017, 1, 1), 3000)
    end = issue.replace(year=issue.year + 10) - timedelta(days=1)
    number = rng.choice("JKLMNPRSTUVWXYZ") + digits(rng, 7)
    dob = date(rng.randint(1975, 2000), rng.randint(1, 12), rng.randint(1, 28))
    name = rng.choice(NAMES).split()
    lines = ["REPUBLIC OF INDIA", "Ministry of External Affairs", "Type P  Country Code IND"]
    columns = [["Passport No.", number], ["Surname", name[1].upper()], ["Given Name(s)", name[0].upper()],
               ["Date of Birth", fmt_date(dob, "dmy_slash")], ["Place of Issue", rng.choice(CITIES).upper()],
               ["Date of Issue", fmt_date(issue, "dmy_slash")], ["Date of Expiry", fmt_date(end, "dmy_slash")]]
    return lines, {"expiresOn": end.isoformat(), "number": number, "issuer": "Ministry of External Affairs", "amount": None}, columns


def _pan(rng, held_out):
    pan = fake_pan(rng)
    dob = date(rng.randint(1975, 2000), rng.randint(1, 12), rng.randint(1, 28))
    lines = ["INCOME TAX DEPARTMENT", "GOVT. OF INDIA", rng.choice(NAMES).upper(), f"{rng.choice(NAMES).split()[0].upper()} FATHER",
             fmt_date(dob, "dmy_slash"), "Permanent Account Number", pan, "Signature"]
    return lines, {"expiresOn": None, "number": pan, "issuer": "INCOME TAX DEPARTMENT", "amount": None}


def _aadhaar(rng, held_out):
    number = fake_aadhaar(rng)
    dob = date(rng.randint(1975, 2000), rng.randint(1, 12), rng.randint(1, 28))
    lines = ["Unique Identification Authority of India", "Government of India", rng.choice(NAMES), f"DOB: {fmt_date(dob, 'dmy_slash')}",
             rng.choice(["Male", "Female"]), f"Aadhaar No.: {number}" if not held_out else number, "Aadhaar - Aam Aadmi ka Adhikar"]
    return lines, {"expiresOn": None, "number": number, "issuer": "Unique Identification Authority of India", "amount": None}


def _licence(rng, held_out):
    code = rng.choice(list(STATES))
    issue = _day(rng, date(2012, 1, 1), 4000)
    end = issue.replace(year=issue.year + 20) - timedelta(days=1)
    number = f"{code}{rng.randint(1, 60):02d}{issue.year}{digits(rng, 7)}"
    style = rng.choice(["dmy_dash", "dmy_slash"])
    issuer = f"Transport Department, Government of {STATES[code]}"
    lines = [issuer, "Driving Licence", f"Licence No.: {number}", f"Name: {rng.choice(NAMES)}",
             f"Date of Issue: {fmt_date(issue, style)}", f"Valid Till (NT): {fmt_date(end, style)}", "Class of Vehicle: LMV, MCWG"]
    return lines, {"expiresOn": end.isoformat(), "number": number, "issuer": issuer, "amount": None}


def _rc(rng, held_out):
    reg = registration(rng, spaced=rng.random() < 0.5)
    registered = _day(rng, date(2014, 1, 1), 3500)
    end = registered.replace(year=registered.year + 15) - timedelta(days=1)
    style = rng.choice(["dmy_slash", "d_mon_y_dash"])
    lines = ["Regional Transport Office", "Certificate of Registration (Form 23)", f"Registration No.: {reg}",
             f"Date of Registration: {fmt_date(registered, style)}", f"Reg. Valid upto: {fmt_date(end, style)}",
             f"Owner: {rng.choice(NAMES)}", f"Chassis No.: XMPL{digits(rng, 9)}  Engine No.: E{digits(rng, 8)}", "Fuel: Petrol  Colour: Grey"]
    return lines, {"expiresOn": end.isoformat(), "number": reg, "issuer": "Regional Transport Office", "amount": None}


def _warranty(rng, held_out):
    bought = _day(rng, date(2025, 6, 1), 400)
    end = bought.replace(year=bought.year + rng.choice([1, 2, 3])) - timedelta(days=1)
    serial = "SN" + digits(rng, 10)
    price = rng.randint(8000, 90000)
    style = rng.choice(["d_month_y", "mon_d_y", "d_mon_y"])
    lines = ["Example Appliances Pvt. Ltd.", "Warranty Card - Front Load Washing Machine", f"Serial No.: {serial}",
             f"Date of Purchase: {fmt_date(bought, style)}", f"Warranty valid until {fmt_date(end, style)}",
             f"Amount Paid: {inr(price, rng)}", "Keep the invoice with this card. Customer care: see the website."]
    return lines, {"expiresOn": end.isoformat(), "number": serial, "issuer": None, "amount": f"{price}.00"}


def _rent(rng, held_out):
    start = _day(rng, date(2025, 10, 1), 200)
    end = start + timedelta(days=rng.choice([333, 364]))
    style = rng.choice(["dmy_slash", "d_month_y"])
    lines = ["RENTAL AGREEMENT", f"This agreement is made at {rng.choice(CITIES)} between {rng.choice(NAMES)} (Owner) and {rng.choice(NAMES)} (Tenant).",
             f"The tenancy is for a period of 11 months from {fmt_date(start, style)} to {fmt_date(end, style)}.",
             f"The monthly rent is {inr(rng.randint(12, 60) * 1000, rng)} and the security deposit is {inr(rng.randint(1, 3) * 100000, rng)}.",
             "Either party may end it with one month's notice."]
    return lines, {"expiresOn": end.isoformat(), "number": None, "issuer": None, "amount": None}


def _form16(rng, held_out):
    lines = ["FORM NO. 16", "[See rule 31(1)(a)]", "PART A", "Certificate under section 203 of the Income-tax Act, 1961",
             f"Certificate No.: {''.join(rng.choice('ABCDEFGHJKLMNPQRSTUVWXYZ') for _ in range(7))}", "Assessment Year: 2026-27",
             "Period with the Employer: From 01-Apr-2025 To 31-Mar-2026", f"PAN of the Employee: {fake_pan(rng)}",
             f"Total tax deducted: {inr(rng.randint(20000, 300000), rng)}"]
    return lines, {"expiresOn": None, "number": None, "issuer": None, "amount": None}


TEMPLATES = {"motor_policy": _motor, "health_policy": _health, "life_policy": _life, "puc": _puc, "passport": _passport, "pan": _pan,
             "aadhaar": _aadhaar, "licence": _licence, "rc": _rc, "warranty": _warranty, "rent": _rent, "form16": _form16}


def generate(seed: int, per_kind: int, held_out: bool = False, prefix: str = "dev") -> List[SyntheticDoc]:
    """[per_kind] documents of every kind; renderings rotate so each kind is seen every way it reaches the reader."""
    rng = random.Random(seed)
    docs = []
    for k, kind in enumerate(KINDS):
        for i in range(per_kind):
            made = TEMPLATES[kind](rng, held_out)
            lines, truth = made[0], made[1]
            columns = made[2] if len(made) > 2 else []
            options = ["png", "photo_jpeg"] if kind in CARD_KINDS else RENDERINGS
            rendering = options[(i + k) % len(options)]
            docs.append(SyntheticDoc(f"{prefix}-{kind}-{i}", kind, rendering, lines, truth, held_out, columns))
    return docs


# ── Rendering ──
def render(doc: SyntheticDoc) -> bytes:
    if doc.rendering == "text_pdf":
        return _text_pdf(doc)
    image = _image(doc)
    if doc.rendering == "scan_pdf":
        return _image_pdf(image)
    if doc.rendering == "png":
        out = io.BytesIO()
        image.save(out, format="PNG")
        return out.getvalue()
    return _photo(image, random.Random(doc.id))


def _text_pdf(doc: SyntheticDoc) -> bytes:
    from fpdf import FPDF

    pdf = FPDF(format="A4")
    pdf.add_page()
    pdf.set_font("Helvetica", size=11)
    for line in doc.lines:
        pdf.multi_cell(0, 7, line, new_x="LMARGIN", new_y="NEXT")
    for label, value in doc.columns:
        pdf.cell(60, 7, label)
        pdf.cell(0, 7, value, new_x="LMARGIN", new_y="NEXT")
    return bytes(pdf.output())


def _image(doc: SyntheticDoc):
    from PIL import Image, ImageDraw, ImageFont

    card = doc.kind in CARD_KINDS
    width = 1400 if card else 1654
    size = 34 if card else 30
    font = ImageFont.load_default(size=size)
    wrapped = []
    for line in doc.lines:
        wrapped += _wrap(line, font, width - 160)
    height = 140 + (len(wrapped) + len(doc.columns) + 2) * int(size * 1.6) + (0 if card else 400)
    img = Image.new("L", (width, height), 255)
    draw = ImageDraw.Draw(img)
    y = 80
    for line in wrapped:
        draw.text((80, y), line, fill=20, font=font)
        y += int(size * 1.6)
    if doc.columns:  # card layout: labels in a row, values on the line below, in two columns
        y += int(size * 0.8)
        for i in range(0, len(doc.columns), 2):
            pair = doc.columns[i:i + 2]
            for j, (label, _) in enumerate(pair):
                draw.text((80 + j * 640, y), label, fill=90, font=ImageFont.load_default(size=size - 8))
            y += int(size * 1.2)
            for j, (_, value) in enumerate(pair):
                draw.text((80 + j * 640, y), value, fill=10, font=font)
            y += int(size * 1.7)
    return img


def _wrap(line: str, font, max_width: int) -> List[str]:
    words, out, current = line.split(" "), [], ""
    for w in words:
        trial = f"{current} {w}".strip()
        if font.getlength(trial) <= max_width:
            current = trial
        else:
            out.append(current)
            current = w
    return out + [current] if current else out


def _image_pdf(image) -> bytes:
    from fpdf import FPDF

    pdf = FPDF(format="A4")
    pdf.add_page()
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    buf.seek(0)
    pdf.image(buf, x=0, y=0, w=210)
    return bytes(pdf.output())


def _photo(image, rng: random.Random) -> bytes:
    from PIL import Image, ImageFilter

    img = image.rotate(rng.uniform(-1.5, 1.5), resample=Image.Resampling.BICUBIC, expand=True, fillcolor=235)
    img = img.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.4, 0.9)))
    px = img.load()
    for _ in range(img.width * img.height // 60):  # sensor noise
        x, y = rng.randrange(img.width), rng.randrange(img.height)
        px[x, y] = max(0, min(255, px[x, y] + rng.randint(-60, 60)))
    shade = Image.linear_gradient("L").resize(img.size).point(lambda v: 255 - v // 6)  # uneven light across the page
    img = Image.composite(img, Image.new("L", img.size, 0), shade.point(lambda v: v))
    img = img.resize((int(img.width * 0.8), int(img.height * 0.8)), Image.Resampling.BILINEAR)
    out = io.BytesIO()
    img.convert("RGB").save(out, format="JPEG", quality=55)
    return out.getvalue()
