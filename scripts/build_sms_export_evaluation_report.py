import html
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/build/reports/sms-export-evaluation.json"
OUTPUT = ROOT / "SMS_EXPORT_EVALUATION.html"


def text(value):
    return html.escape("" if value is None else str(value))


def main():
    report = json.loads(SOURCE.read_text(encoding="utf-8"))
    messages = report["messages"]
    if len(messages) != 1088:
        raise SystemExit(f"Expected all 1,088 export messages, found {len(messages)}")
    counts = Counter()
    rows = []
    for message in messages:
        expected = message["expected"]
        actual = message["actual"]
        comparison = message["comparison"]
        receiver_category = {
            "Statement": "Credit Card",
            "AtmWithdrawal": "Transfer",
            "CardPayment": "Credit Payment",
            "CreditCardReceived": "Credit Payment",
        }.get(actual.get("type"), actual.get("category"))
        deltas = {
            "type": comparison["type_differs"],
            "category": bool(expected.get("category")) and expected["category"] != receiver_category,
            "keyword_category": bool(expected.get("category")) and expected["category"] != actual.get("category"),
            "amount": comparison["amount_differs"],
            "digits": comparison["digits_differs"],
        }
        flags = [name for name, differs in deltas.items() if differs]
        for flag in flags:
            counts[flag] += 1
        status = "Review" if flags else "No parser/archive delta"
        flags_html = ", ".join(map(text, flags)) or "—"
        rows.append(
            f"<tr data-review='{'yes' if flags else 'no'}'><td>{message['number']}</td>"
            f"<td>{text(message['sender'])}</td><td>{'Tracked' if message['tracked_in_export'] else 'Untracked'}</td>"
            f"<td>{text(expected.get('type'))}</td><td>{text(expected.get('category'))}</td><td>{text(expected.get('amount'))}</td>"
            f"<td>{text(actual.get('type'))}</td><td>{text(actual.get('category'))}</td>"
            f"<td>{text(receiver_category)}</td><td>{text(actual.get('amount'))}</td><td>{flags_html}</td>"
            f"<td><details><summary>View SMS</summary><pre>{text(message.get('body'))}</pre></details></td></tr>"
        )
    type_pairs = Counter(
        (message["expected"].get("type"), message["actual"].get("type"))
        for message in messages if message["comparison"]["type_differs"]
    )
    pair_html = "".join(
        f"<li>{text(old)} → {text(new)}: {count}</li>" for (old, new), count in type_pairs.most_common()
    ) or "<li>None</li>"
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SMS export parser evaluation</title>
<style>
body{{font:15px system-ui,sans-serif;margin:24px;color:#172033;background:#f7f9fc}}h1{{margin-bottom:4px}}.note{{max-width:1100px;padding:12px;background:#fff4d4;border:1px solid #e8ce83;border-radius:8px}}.cards{{display:flex;flex-wrap:wrap;gap:10px;margin:18px 0}}.card{{background:white;padding:12px 18px;border:1px solid #dce3ed;border-radius:8px;min-width:120px}}.card b{{display:block;font-size:24px}}input,select{{padding:9px;margin:6px 8px 12px 0}}table{{border-collapse:collapse;width:100%;background:white;font-size:13px}}th,td{{border:1px solid #d9e0ea;padding:7px;text-align:left;vertical-align:top}}th{{position:sticky;top:0;background:#e9eff7}}tr[data-review=yes]{{background:#fff9ec}}pre{{white-space:pre-wrap;max-width:650px;margin:4px 0}}.muted{{color:#536078}}
</style></head><body>
<h1>SMS export parser evaluation</h1>
<p class="muted">Source: app/src/test/resources/sms_export.txt · {len(messages)} messages · parser implementation exercised by the JVM test.</p>
<p class="note"><b>Interpretation:</b> archived “App extracted” values are historical app output, not independently verified ground truth. The receiver-default category column models special ATM/statement/card-payment overrides plus keyword fallback, but it does not call AI or apply user rules. Deltas are review candidates, not automatic bug verdicts. Bank recognition is not the same as whether a historical message was tracked, so that comparison is intentionally not used as a verdict.</p>
<div class="cards"><div class="card"><b>{len(messages)}</b>messages evaluated</div>
<div class="card"><b>{counts['type']}</b>type deltas</div><div class="card"><b>{counts['category']}</b>receiver-default category deltas</div>
<div class="card"><b>{counts['keyword_category']}</b>keyword-only category deltas</div>
<div class="card"><b>{counts['amount']}</b>amount deltas</div><div class="card"><b>{counts['digits']}</b>digits deltas</div></div>
<h2>Type delta breakdown</h2><ul>{pair_html}</ul>
<label><input id="reviewOnly" type="checkbox"> Show delta rows only</label>
<label>Search <input id="search" type="search" placeholder="SMS number, sender, category, body"></label>
<table><thead><tr><th>#</th><th>Sender</th><th>Export status</th><th>Archived type</th><th>Archived category</th><th>Archived amount</th><th>Parser type</th><th>Keyword category</th><th>Receiver default category</th><th>Parser amount</th><th>Delta fields</th><th>Message</th></tr></thead><tbody>{''.join(rows)}</tbody></table>
<script>const search=document.querySelector('#search'),only=document.querySelector('#reviewOnly');function filter(){{const q=search.value.toLowerCase();for(const row of document.querySelectorAll('tbody tr')){{const match=row.innerText.toLowerCase().includes(q);row.hidden=!match||(only.checked&&row.dataset.review!=='yes')}}}}search.addEventListener('input',filter);only.addEventListener('change',filter);</script>
</body></html>"""
    OUTPUT.write_text(page, encoding="utf-8")
    print(f"Wrote {OUTPUT} with {len(messages)} SMS rows; delta counts: {dict(counts)}")


if __name__ == "__main__":
    main()
