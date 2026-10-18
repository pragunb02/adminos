"""Issuers the field rules recognise (AOS-717): insurers registered with IRDAI, and the government bodies that issue
identity, tax and vehicle documents. Data, not code: a new issuer is one line. Each pattern matches the name as it is
printed, so the proposal's value is that exact text."""

import re

_NAMES = [
    # General and health insurers
    r"HDFC\s+ERGO(?:\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"ICICI\s+Lombard(?:\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Bajaj\s+(?:Allianz|General)(?:\s+General)?\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?",
    r"Tata\s+AIG(?:\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"The\s+New\s+India\s+Assurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?|New\s+India\s+Assurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?",
    r"United\s+India\s+Insurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?",
    r"The\s+Oriental\s+Insurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?|Oriental\s+Insurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?",
    r"National\s+Insurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?",
    r"Star\s+Health(?:\s+and\s+Allied\s+Insurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Niva\s+Bupa(?:\s+Health\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Care\s+Health\s+Insurance(?:\s+(?:Limited|Ltd\.?))?",
    r"Aditya\s+Birla\s+(?:Health|Sun\s+Life)\s+Insurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?",
    r"SBI\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?",
    r"Reliance\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?",
    r"Go\s+Digit\s+General\s+Insurance(?:\s+(?:Limited|Ltd\.?))?|Digit\s+Insurance",
    r"Acko\s+General\s+Insurance(?:\s+(?:Limited|Ltd\.?))?",
    r"Kotak\s+(?:Mahindra\s+)?General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?",
    r"Cholamandalam\s+MS\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?",
    r"Future\s+Generali(?:\s+India)?(?:\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"IFFCO[\s-]+Tokio(?:\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Royal\s+Sundaram(?:\s+General\s+Insurance(?:\s+Co(?:mpany|\.))?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Liberty\s+General\s+Insurance(?:\s+(?:Limited|Ltd\.?))?",
    r"Universal\s+Sompo(?:\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Manipal\s*Cigna(?:\s+Health\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Magma\s+(?:HDI\s+)?General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?",
    r"Shriram\s+General\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?",
    # Life insurers
    r"Life\s+Insurance\s+Corporation\s+of\s+India",
    r"HDFC\s+Life(?:\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"ICICI\s+Prudential(?:\s+Life\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"SBI\s+Life(?:\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"(?:Axis\s+)?Max\s+Life(?:\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"Tata\s+AIA(?:\s+Life(?:\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?)?",
    r"Bajaj\s+(?:Allianz\s+)?Life(?:\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    r"PNB\s+MetLife(?:\s+India)?(?:\s+Insurance(?:\s+Company)?(?:\s+(?:Limited|Ltd\.?))?)?",
    # Government
    r"Unique\s+Identification\s+Authority\s+of\s+India",
    r"Income\s+Tax\s+Department",
    r"Ministry\s+of\s+External\s+Affairs",
    r"Ministry\s+of\s+Road\s+Transport\s+(?:and|&)\s+Highways",
    r"(?:Regional|District)\s+Transport\s+(?:Office|Authority)",
    r"Transport\s+Department(?:,\s+Government\s+of\s+(?:(?:Andhra|Arunachal|Himachal|Madhya|Uttar)\s+Pradesh|Tamil\s+Nadu|West\s+Bengal|[A-Z][a-z]+))?",
]

# Names never run across lines: whitespace in a name means spaces or tabs only. Case-insensitive (cards print capitals).
ISSUERS = [re.compile(r"\b(?:" + name.replace(r"\s+", r"[ \t]+").replace(r"\s*", r"[ \t]*") + r")(?![A-Za-z])", re.I) for name in _NAMES]
