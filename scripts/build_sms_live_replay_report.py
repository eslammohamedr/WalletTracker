import argparse
import html
import json
from collections import Counter
from pathlib import Path


def display(value):
    if value is None:
        return "—"
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, sort_keys=True)
    return str(value)


def main():
    parser = argparse.ArgumentParser(description="Build a searchable HTML report from a live SMS replay result")
    parser.add_argument("results", type=Path)
    parser.add_argument("--output", type=Path, default=Path("SMS_LIVE_REPLAY_REPORT.html"))
    parser.add_argument("--focused-results", type=Path)
    parser.add_argument("--queue-results", type=Path)
    args = parser.parse_args()
    result = json.loads(args.results.read_text(encoding="utf-8"))
    messages = result.get("message_results", [])
    counts = Counter(row.get("status", "UNKNOWN") for row in messages)
    failures = {"OUTPUT_MISMATCH", "EXPECTED_RECORD_MISSING", "EXPECTED_STATEMENT_MISSING", "UNEXPECTED_RECORD"}
    partials = {"PARTIAL_STATEMENT_FOLLOWED_BY_MATCHING_PAYMENT", "PAYMENT_PAIR_OR_PENDING_LINK_REQUIRES_REVIEW", "NO_RECORD_NO_INBOX_EVIDENCE", "STATEMENT_STATE_NOT_CAPTURED", "PARTIAL_STATEMENT_PAYMENT_COVERAGE_INFERRED"}

    rows = []
    for item in messages:
        status = item.get("status", "UNKNOWN")
        observed = item.get("record") or item.get("statement") or item.get("statement_followup") or {}
        check_text = display(item.get("checks", {}))
        rows.append(
            f'<tr data-status="{html.escape(status)}" data-inbox="{str(bool(item.get("inbox_row_verified"))).lower()}">'
            f'<td>{html.escape(display(item.get("number")))}</td>'
            f'<td>{html.escape(display(item.get("sender")))}</td>'
            f'<td>{html.escape(display(item.get("parser_type")))}</td>'
            f'<td>{html.escape(display(item.get("receiver_default_category")))}</td>'
            f'<td>{html.escape(display(item.get("expected_amount")))} {html.escape(display(item.get("expected_currency")))}</td>'
            f'<td class="sms-body">{html.escape(display(item.get("body")))}</td>'
            f'<td>{html.escape(display(observed))}</td>'
            f'<td>{"Yes" if item.get("inbox_row_verified") else "No"}</td>'
            f'<td class="{ "fail" if status in failures else "partial" if status in partials else "pass"}">{html.escape(status)}</td>'
            f'<td>{html.escape(check_text)}</td></tr>'
        )

    pass_count = sum(value for key, value in counts.items() if key.startswith("PASS"))
    fail_count = sum(value for key, value in counts.items() if key in failures)
    partial_count = sum(value for key, value in counts.items() if key in partials)
    record_count_display = result.get("record_count", "NOT CAPTURED" if result.get("error") else 0)
    pass_count_display = "NOT EVALUATED" if result.get("error") else pass_count
    fail_count_display = "NOT EVALUATED" if result.get("error") else fail_count
    partial_count_display = "NOT EVALUATED" if result.get("error") else partial_count
    inbox_verified = result.get("matched_source_sms_count")
    if inbox_verified is None:
        inbox_verified = max((batch.get("matched_source_messages", 0) for batch in result.get("inbox_batches", [])), default=0)
        result["matched_source_sms_count"] = inbox_verified
    source_path = html.escape(result.get("evidence", str(args.results)))
    run_error_panel = ""
    if result.get("error"):
        cleanup = result.get("cleanup", {})
        run_error_panel = (
            '<div class="note"><strong>Full replay evaluation did not complete.</strong> '
            f'{html.escape(display(result.get("injected", 0)))}/{html.escape(display(result.get("requested_count", 0)))} SMS were injected; '
            f'{html.escape(display(inbox_verified))} source messages were verified in the inbox. '
            f'Error: {html.escape(display(result.get("error")))}. '
            'The persisted-output snapshot was not collected, so this run has no per-message actual results. '
            f'Cleanup: {html.escape(display(cleanup))}.</div>'
        )
    focused_panel = ""
    if args.focused_results:
        focused = json.loads(args.focused_results.read_text(encoding="utf-8"))
        focused_tests = focused.get("tests", [])
        focused_passes = sum(test.get("status") == "PASS" for test in focused_tests)
        focused_rows = "".join(
            "<tr>" + "".join(f"<td>{html.escape(display(value))}</td>" for value in (
                test.get("id"), test.get("status"), test.get("expected"), test.get("actual"),
                " | ".join(test.get("messages", [test.get("body", "")]))
            )) + "</tr>"
            for test in focused_tests
        )
        focused_panel = f'''<h2>Post-fix focused live regressions</h2>
<div class="note"><strong>{focused_passes}/{len(focused_tests)} focused cases passed.</strong> These real Android SMS runs use a fresh disposable account and validate the foreign-currency card conversion and unequal-amount Banque Misr payment reconciliation. They are targeted follow-up evidence, not a replacement for the full replay results below. Test account cleanup: {html.escape(display(focused.get("cleanup", {})))}.</div>
<div class="wrap"><table><thead><tr><th>Case</th><th>Result</th><th>Expected</th><th>Observed</th><th>SMS input</th></tr></thead><tbody>{focused_rows}</tbody></table></div>'''
    queue_panel = ""
    if args.queue_results:
        queue = json.loads(args.queue_results.read_text(encoding="utf-8"))
        queue_panel = f'''<h2>SMS receiver burst / ANR regression</h2>
<div class="note"><strong>{html.escape(display(queue.get("status")))} — {html.escape(display(queue.get("injected")))}/{html.escape(display(queue.get("requested")))} SMS delivered.</strong> Fresh disposable account; {html.escape(display(queue.get("record_count")))} persisted records; final balance {html.escape(display(queue.get("account_balance")))} (expected {html.escape(display(queue.get("expected", {}).get("account_balance")))}); duplicates {html.escape(display(queue.get("duplicate_record_ids")))}; invalid outputs {html.escape(display(queue.get("invalid_record_count")))}; ANR evidence {html.escape(display(queue.get("anr_count")))}. Cleanup deleted the temporary account and its records. This targeted 40-message run supports the receiver queue fix but does not replace rerunning the historical 1,088-message replay.</div>'''
    batch_rows = "".join(
        "<tr>" + "".join(f"<td>{html.escape(display(batch.get(key)))}</td>" for key in
        ("through_source_number", "inbox_rows", "matched_source_messages")) +
        f"<td>{len(batch.get('missing_source_numbers', []))}</td></tr>"
        for batch in result.get("inbox_batches", [])
    )
    document = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Live SMS Export Replay Report</title><style>
body{{font:15px/1.5 system-ui,sans-serif;margin:24px;color:#e5e7eb;background:#111827}}h1,h2{{margin:.4em 0;color:#f9fafb}}.note{{background:#332b17;color:#fde68a;padding:14px;border-left:4px solid #e0a400;margin:18px 0}}.cards{{display:flex;gap:12px;flex-wrap:wrap}}.card{{background:#1f2937;padding:14px 20px;border-radius:8px;min-width:120px;box-shadow:0 1px 4px #0008;border:1px solid #374151}}.card strong{{font-size:24px;display:block;color:#f9fafb}}.filters{{margin:18px 0;display:flex;gap:12px;flex-wrap:wrap}}input,select{{padding:9px;font:inherit;color:#e5e7eb;background:#1f2937;border:1px solid #4b5563}}table{{border-collapse:collapse;width:100%;background:#1f2937}}th,td{{padding:8px;border-bottom:1px solid #374151;text-align:left;vertical-align:top}}th{{position:sticky;top:0;background:#374151;color:#f9fafb}}.wrap{{overflow:auto;max-height:70vh}}.pass{{color:#4ade80}}.partial{{color:#fbbf24}}.fail{{color:#f87171}}code{{overflow-wrap:anywhere;color:#c4b5fd}}
</style></head><body>
<h1>Live SMS Export Replay</h1>
{focused_panel}
{queue_panel}
{run_error_panel}
<p><strong>Run:</strong> {html.escape(display(result.get('started_at')))} · <strong>device:</strong> {html.escape(display(result.get('device')))} · <strong>result:</strong> {html.escape(display(result.get('status')))}</p>
<div class="note"><strong>Interpretation:</strong> This replay compares live receiver output to the current JVM parser’s extracted fields; those parser fields and archived SMS export labels are not independently adjudicated ground truth. A matching row proves receiver/parser parity for the checked fields, not that every category is semantically correct. Messages without verified Inbox evidence or persisted output are not passes.</div>
<div class="cards"><div class="card"><strong>{result.get('injected', 0)}/{result.get('requested_count', 0)}</strong>send attempts</div><div class="card"><strong>{result.get('matched_source_sms_count', 0)}/{result.get('requested_count', 0)}</strong>Inbox bodies verified</div><div class="card"><strong>{record_count_display}</strong>Room records</div><div class="card"><strong>{pass_count_display}</strong>parity passes</div><div class="card"><strong>{fail_count_display}</strong>failures</div><div class="card"><strong>{partial_count_display}</strong>partial/review</div></div>
<h2>Batch delivery evidence</h2><div class="wrap"><table><thead><tr><th>Through source number</th><th>Inbox rows</th><th>Matched</th><th>Missing</th></tr></thead><tbody>{batch_rows}</tbody></table></div>
<h2>Per-message results</h2><div class="filters"><input id="search" type="search" placeholder="Search SMS number, sender, category, status…"><select id="status"><option value="">All statuses</option>{''.join(f'<option>{html.escape(key)}</option>' for key in sorted(counts))}<option value="_inbox_missing">Inbox evidence missing</option></select></div>
<div class="wrap"><table><thead><tr><th>#</th><th>Sender</th><th>SMS body</th><th>Parser type</th><th>Expected category</th><th>Oracle amount/currency</th><th>Observed output</th><th>Inbox</th><th>Status</th><th>Assertions</th></tr></thead><tbody id="rows">{''.join(rows)}</tbody></table></div>
<p>Raw evidence: <code>{source_path}</code></p>
<script>const search=document.querySelector('#search'),status=document.querySelector('#status');function filter(){{const q=search.value.toLowerCase();for(const row of document.querySelectorAll('#rows tr')){{const isInboxMissing=status.value==='_inbox_missing'&&row.dataset.inbox==='false';const statusOk=!status.value||status.value==='_inbox_missing'?(!status.value||isInboxMissing):row.dataset.status===status.value;row.hidden=!(statusOk&&row.innerText.toLowerCase().includes(q));}}}}search.addEventListener('input',filter);status.addEventListener('change',filter);</script>
</body></html>"""
    args.output.write_text(document, encoding="utf-8")
    print(f"Wrote {args.output} with {len(messages)} message rows")


if __name__ == "__main__":
    main()
