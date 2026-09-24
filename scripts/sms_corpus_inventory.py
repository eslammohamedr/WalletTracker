import hashlib
import json
import re
from collections import Counter
from pathlib import Path


sources = [
    Path("app/src/test/resources/sms_export.txt"),
    Path("sms_export (1).txt"),
    Path("sms_export(2).txt"),
]
destination = Path("app/build/device-scenarios")
destination.mkdir(parents=True, exist_ok=True)
messages = {}
summary = []
for source in sources:
    content = source.read_text(encoding="utf-8-sig")
    blocks = re.split(r"(?m)^SMS #(\d+)\s*$", content)
    count = 0
    for offset in range(1, len(blocks), 2):
        number, block = blocks[offset:offset + 2]
        sender = re.search(r"(?m)^Sender\s*:\s*(.+)$", block)
        received = re.search(r"(?m)^Date\s*:\s*(.+)$", block)
        body = re.search(r"Body\s*:\s*\n(.*?)(?=\n--- App extracted ---|\n[─━]{3,}|\Z)", block, re.S)
        if not sender or not body:
            raise ValueError(f"Cannot parse {source}: SMS #{number}")
        sender_text, body_text = sender.group(1).strip(), body.group(1).strip()
        fingerprint = hashlib.sha256((sender_text + "\n" + body_text).encode()).hexdigest()
        entry = messages.setdefault(fingerprint, {
            "id": fingerprint[:16], "sender": sender_text, "body": body_text,
            "sources": [], "expected": None,
        })
        entry["sources"].append({
            "file": str(source), "sms_number": int(number),
            "exported_date": received.group(1).strip() if received else None,
        })
        count += 1
    declared = re.search(r"Total messages:\s*(\d+)", content)
    if declared and count != int(declared.group(1)):
        raise ValueError(f"Count mismatch in {source}: parsed {count}, declared {declared.group(1)}")
    summary.append({"source": str(source), "messages": count, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()})

report = {
    "sources": summary, "total_entries": sum(item["messages"] for item in summary),
    "unique_sender_body_pairs": len(messages),
    "senders": dict(Counter(entry["sender"] for entry in messages.values())),
    "note": "Inventory only, not test results. App-extracted fields are not an independent correctness oracle. Expected outcomes require requirements-based review. Every source occurrence retains its exported date: equal bodies may be distinct genuine transactions. Text replay changes SMS receive timestamps.",
}
(destination / "sms-manifest.json").write_text(json.dumps(list(messages.values()), ensure_ascii=False, indent=2), encoding="utf-8")
(destination / "inventory.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=True, indent=2))
