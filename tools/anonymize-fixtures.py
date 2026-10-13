#!/usr/bin/env python3
"""Turn recorded fixtures (tools/fixtures-local) into test fixtures.

Replaces player IDs (they contain MAC addresses), IP addresses and the NAS
name, and trims long browse results to three entries.
"""
import html, pathlib, re

SRC = pathlib.Path(__file__).parent / "fixtures-local"
DST = pathlib.Path(__file__).parent.parent / "sonos/src/test/resources/fixtures"
DST.mkdir(parents=True, exist_ok=True)

ids, ips = {}, {}

def anon(text):
    def rid(m):
        ids.setdefault(m.group(0), f"RINCON_00000000000{len(ids) + 1}01400")
        return ids[m.group(0)]
    text = re.sub(r"RINCON_[0-9A-F]{12}01400", rid, text)
    def ip(m):
        ips.setdefault(m.group(0), f"10.0.0.{len(ips) + 11}")
        return ips[m.group(0)]
    text = re.sub(r"192\.168\.\d+\.\d+", ip, text)
    text = re.sub(r"(?i)bender", "nas", text)
    text = re.sub(r"<serialNum>[^<]*</serialNum>", "<serialNum>00-00-00-00-00-00:0</serialNum>", text)
    text = re.sub(r"<MACAddress>[^<]*</MACAddress>", "<MACAddress>00:00:00:00:00:00</MACAddress>", text)
    return text

def trim(text, keep=3):
    m = re.search(r"<Result>(.*?)</Result>", text, re.S)
    if not m:
        return text
    didl = html.unescape(m.group(1))
    items = re.findall(r"<(?:item|container) .*?</(?:item|container)>", didl, re.S)[:keep]
    head = didl[: didl.index(">") + 1]
    new = head + "".join(items) + "</DIDL-Lite>"
    text = text.replace(m.group(1), html.escape(new, quote=True))
    return re.sub(r"<NumberReturned>\d+</NumberReturned>", f"<NumberReturned>{len(items)}</NumberReturned>", text)

for f in sorted(SRC.glob("*.xml")):
    text = f.read_text()
    if f.name.startswith("Browse-"):
        text = trim(text)
    (DST / f.name).write_text(anon(text))
print(len(list(DST.glob("*.xml"))), "fixtures,", len(ids), "players")
