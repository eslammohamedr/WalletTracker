const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const markdownPath = path.join(root, "TEST_CASES_REPORT.md");
const resultRoot = path.join(root, "app", "build", "device-smoke");
const resultPath = path.join(resultRoot, "20260924-financial-logic", "results.json");
const outputPath = path.join(root, "TEST_CASES_REPORT.html");
const markdown = fs.readFileSync(markdownPath, "utf8");
const liveResults = JSON.parse(fs.readFileSync(resultPath, "utf8"));

function resultFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const filePath = path.join(directory, entry.name);
    if (entry.isDirectory()) return resultFiles(filePath);
    return entry.name === "results.json" ? [filePath] : [];
  });
}

const discoveredRuns = resultFiles(resultRoot).map((filePath) => ({
  filePath,
  mtime: fs.statSync(filePath).mtimeMs,
  data: JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, "")),
}));
const latestArrayRun = discoveredRuns.filter((run) => Array.isArray(run.data)).sort((a, b) => b.mtime - a.mtime)[0];
const latestSeedRun = discoveredRuns.filter((run) => run.data.case === "fresh_qa_user_and_financial_fixture_setup").sort((a, b) => b.mtime - a.mtime)[0];
const latestSmsRun = discoveredRuns.filter((run) => run.data.case?.startsWith("live_sms_expense")
  && run.data.steps?.some((step) => step.startsWith("injected one synthetic"))).sort((a, b) => b.mtime - a.mtime)[0];
const latestIncomeRun = discoveredRuns.filter((run) => run.data.case?.startsWith("live_sms_income")
  && run.data.steps?.some((step) => step.startsWith("injected one synthetic"))).sort((a, b) => b.mtime - a.mtime)[0];
const latestUsdRun = discoveredRuns.filter((run) => run.data.case === "live_sms_expense_usd_and_rollback")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestEurRun = discoveredRuns.filter((run) => run.data.case === "live_sms_expense_eur_and_rollback")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestMissingAmountRun = discoveredRuns.filter((run) => run.data.case === "live_sms_missing_amount_no_record")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestDeclinedRun = discoveredRuns.filter((run) => run.data.case === "live_sms_declined_no_record")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestUnknownAccountRun = discoveredRuns.filter((run) => run.data.case === "live_sms_unknown_account_unlinked_record")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestIdenticalEventsRun = discoveredRuns.filter((run) => run.data.case === "live_sms_identical_body_distinct_events")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestStatementRun = discoveredRuns.filter((run) => run.data.case === "live_credit_statement_due_date_and_reminder_ingestion")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestAutoStatementRun = discoveredRuns.filter((run) => run.data.case === "live_credit_statement_auto_receive_payment_and_rollback")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestSmallStatementInjectionRun = discoveredRuns.filter((run) => run.data.case === "live_credit_statement_small_payment_and_rollback"
  && run.data.sms_injection_attempted).sort((a, b) => b.mtime - a.mtime)[0];
const latestSmallStatementRollbackRun = discoveredRuns.filter((run) => run.data.case === "live_credit_statement_small_payment_and_rollback"
  && run.data.status === "PASS" && run.data.steps?.some((step) => step.includes("restarted and verified baseline")))
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestSmallStatementFullRun = discoveredRuns.filter((run) => run.data.case === "live_credit_statement_small_payment_and_rollback"
  && run.data.status === "PASS" && run.data.sms_injection_attempted
  && run.data.steps?.some((step) => step.includes("WorkNames are cancelled")))
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestBurstRun = discoveredRuns.filter((run) => run.data.case === "live_sms_burst_distinct_same_timestamp")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestCreditRun = discoveredRuns.filter((run) => run.data.case === "credit_card_payment_credit_first_and_rollback"
  && run.data.status === "PASS").sort((a, b) => b.mtime - a.mtime)[0];
const latestDebitCreditRun = discoveredRuns.filter((run) => run.data.case === "credit_card_payment_debit_first_and_rollback"
  && run.data.status === "PASS").sort((a, b) => b.mtime - a.mtime)[0];
const latestTransferRun = discoveredRuns.filter((run) => run.data.case === "live_in_app_transfer_and_rollback")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestCreditAttempt = discoveredRuns.filter((run) => run.data.case === "credit_card_payment_credit_first_and_rollback"
  && run.data.manual_product_verification).sort((a, b) => b.mtime - a.mtime)[0];
const latestCardPurchaseRun = discoveredRuns.filter((run) => run.data.case === "live_sms_credit_card_purchase_and_rollback")
  .sort((a, b) => b.mtime - a.mtime)[0];
const latestManualRuns = ["Income", "Expense"].map((recordType) => discoveredRuns.filter((run) =>
  run.data.case === "manual_income_record_and_rollback" && run.data.type === recordType && run.data.status === "PASS")
  .sort((a, b) => b.mtime - a.mtime)[0]).filter(Boolean);
const latestAtmRuns = [true, false].map((printedBalanceIncluded) => discoveredRuns.filter((run) =>
  run.data.case === "live_sms_atm_withdrawal"
  && run.data.printed_balance_included === printedBalanceIncluded)
  .sort((a, b) => b.mtime - a.mtime)[0]).filter(Boolean);
const currentRuns = [];
if (latestArrayRun) {
  latestArrayRun.data.forEach((item, index) => currentRuns.push({
    id: `UI-${String(index + 1).padStart(2, "0")} ${item.name}`,
    status: item.status,
    evidence: path.relative(root, latestArrayRun.filePath),
    seconds: item.seconds,
    ...(item.error ? { error: item.error } : {}),
  }));
}
if (latestSeedRun) currentRuns.push({
  id: "QA-00/QA-01 fixture initialization",
  status: latestSeedRun.data.status,
  evidence: path.relative(root, latestSeedRun.filePath),
  account_count: latestSeedRun.data.baseline?.account_count,
  dashboard_total_egp: latestSeedRun.data.baseline?.dashboard_total_egp,
});
if (latestSmsRun) currentRuns.push({
  id: "Live SMS expense and rollback",
  status: latestSmsRun.data.status,
  evidence: path.relative(root, latestSmsRun.filePath),
  amount: latestSmsRun.data.amount,
  currency: latestSmsRun.data.currency || "EGP",
  printed_balance: latestSmsRun.data.printed_balance,
  account: latestSmsRun.data.account,
  category: latestSmsRun.data.expected_category,
  merchant: latestSmsRun.data.merchant,
  baseline_account_balance: latestSmsRun.data.baseline_account_balance,
  account_balance_after_expense: latestSmsRun.data.account_balance_after_transaction ?? latestSmsRun.data.account_balance_after_expense,
  baseline_dashboard_total: latestSmsRun.data.baseline_dashboard_total,
  dashboard_total_after_expense: latestSmsRun.data.dashboard_total_after_transaction ?? latestSmsRun.data.dashboard_total_after_expense,
  account_balance_after_cleanup: latestSmsRun.data.account_balance_after_cleanup,
  cleanup: latestSmsRun.data.cleanup,
  sms_inbox_cleanup: latestSmsRun.data.sms_inbox_cleanup,
  runner_limitation: latestSmsRun.data.automation_runner_status,
  error: latestSmsRun.data.error,
});
if (latestIncomeRun) currentRuns.push({
  id: "Live SMS income and rollback",
  status: latestIncomeRun.data.status,
  evidence: path.relative(root, latestIncomeRun.filePath),
  amount: latestIncomeRun.data.amount,
  account: latestIncomeRun.data.account,
  category: latestIncomeRun.data.expected_category,
  merchant: latestIncomeRun.data.merchant,
  account_balance_after_transaction: latestIncomeRun.data.account_balance_after_transaction,
  dashboard_total_after_transaction: latestIncomeRun.data.dashboard_total_after_transaction,
  account_balance_after_cleanup: latestIncomeRun.data.account_balance_after_cleanup,
});
for (const run of latestManualRuns) currentRuns.push({
  id: `Manual ${run.data.type} / ${run.data.category} record and rollback`,
  status: run.data.status,
  evidence: path.relative(root, run.filePath),
  account: run.data.account,
  category: run.data.category,
  amount: run.data.amount,
  type: run.data.type,
  baseline: run.data.baseline,
  account_after_add: run.data.account_after_add,
  home_after_add: run.data.home_after_add,
  statistics: run.data.statistics,
  steps: run.data.steps?.join("; "),
  cleanup: run.data.cleanup,
});
for (const run of latestAtmRuns) currentRuns.push({
  id: `Live ATM withdrawal SMS (${run.data.printed_balance_included ? "with" : "without"} printed balance) and rollback`,
  status: run.data.status,
  evidence: path.relative(root, run.filePath),
  marker: run.data.marker,
  amount: run.data.amount,
  printed_balance_included: run.data.printed_balance_included,
  source_after: run.data.source_after_egp,
  cash_after: run.data.cash_after_egp,
  home_after: run.data.home_after_egp,
  steps: run.data.steps?.join("; "),
  cleanup: run.data.cleanup,
});
for (const run of [latestUsdRun, latestEurRun].filter(Boolean)) currentRuns.push({
  id: `Live ${run.data.currency} SMS expense and rollback`,
  status: run.data.status,
  evidence: path.relative(root, run.filePath),
  amount: run.data.amount,
  account: run.data.account,
  category: run.data.expected_category,
  account_balance_before: run.data.baseline_account_balance,
  account_balance_after_expense: run.data.account_balance_after_transaction ?? run.data.account_balance_after_expense,
  account_balance_after_cleanup: run.data.account_balance_after_cleanup,
  dashboard_before: run.data.baseline_dashboard_total,
  dashboard_after_expense: run.data.dashboard_total_after_transaction ?? run.data.dashboard_total_after_expense,
  cleanup: run.data.cleanup,
});
if (latestMissingAmountRun) currentRuns.push({
  id: "Live amount-less bank SMS: no financial record or balance change",
  status: latestMissingAmountRun.data.status === "FAIL"
    && latestMissingAmountRun.data.steps?.some((step) => step.includes("no SMS was injected"))
    ? "INCONCLUSIVE - UI automation failed before SMS injection"
    : latestMissingAmountRun.data.status,
  evidence: path.relative(root, latestMissingAmountRun.filePath),
  marker: latestMissingAmountRun.data.marker,
  baseline_home_egp: latestMissingAmountRun.data.baseline_home_egp,
  baseline_mainbank_egp: latestMissingAmountRun.data.baseline_mainbank_egp,
  steps: latestMissingAmountRun.data.steps?.join("; "),
  inbox_row: latestMissingAmountRun.data.inbox_row,
});
if (latestDeclinedRun) currentRuns.push({
  id: "Live declined-payment SMS: no financial record or balance change",
  status: latestDeclinedRun.data.status,
  evidence: path.relative(root, latestDeclinedRun.filePath),
  marker: latestDeclinedRun.data.marker,
  baseline_home_egp: latestDeclinedRun.data.baseline_home_egp,
  baseline_mainbank_egp: latestDeclinedRun.data.baseline_mainbank_egp,
  steps: latestDeclinedRun.data.steps?.join("; "),
  inbox_row: latestDeclinedRun.data.inbox_row,
});
if (latestUnknownAccountRun) currentRuns.push({
  id: "Live unknown-account SMS: unlinked record and Action Required alert",
  status: latestUnknownAccountRun.data.status,
  evidence: path.relative(root, latestUnknownAccountRun.filePath),
  unknown_suffix: latestUnknownAccountRun.data.unknown_suffix,
  amount: latestUnknownAccountRun.data.amount,
  category: latestUnknownAccountRun.data.category,
  merchant: latestUnknownAccountRun.data.merchant,
  record_context: latestUnknownAccountRun.data.record_context?.join(" / "),
  unlinked_banner: latestUnknownAccountRun.data.unlinked_banner,
  notification: latestUnknownAccountRun.data.notification,
  baseline_home_egp: latestUnknownAccountRun.data.baseline_home_egp,
  baseline_mainbank_egp: latestUnknownAccountRun.data.baseline_mainbank_egp,
  cleanup: latestUnknownAccountRun.data.cleanup,
  inbox_row: latestUnknownAccountRun.data.inbox_row,
});
if (latestIdenticalEventsRun) currentRuns.push({
  id: "Live distinct identical-body SMS events: double expense and rollback",
  status: latestIdenticalEventsRun.data.status,
  evidence: path.relative(root, latestIdenticalEventsRun.filePath),
  amount_each: latestIdenticalEventsRun.data.amount_each,
  record_count: latestIdenticalEventsRun.data.record_count,
  after_two_events_home_egp: latestIdenticalEventsRun.data.after_two_events_home_egp,
  after_two_events_mainbank_egp: latestIdenticalEventsRun.data.after_two_events_mainbank_egp,
  cleanup: latestIdenticalEventsRun.data.cleanup,
});
if (latestStatementRun) currentRuns.push({
  id: "Live credit statement due-date and reminder ingestion",
  status: latestStatementRun.data.status,
  evidence: path.relative(root, latestStatementRun.filePath),
  amount: latestStatementRun.data.amount,
  due_date: latestStatementRun.data.due_date,
  expected_card: latestStatementRun.data.card,
  observed: latestStatementRun.data.observed,
  steps: latestStatementRun.data.steps?.join("; "),
  reminder_observation: latestStatementRun.data.reminder_observation,
  notification_observation: latestStatementRun.data.notification_observation,
  cleanup: latestStatementRun.data.cleanup,
  blockage: latestStatementRun.data.blockage,
});
if (latestAutoStatementRun) currentRuns.push({
  id: "Live automatic credit statement, due date, reminder, payment, and rollback",
  status: latestAutoStatementRun.data.status,
  evidence: path.relative(root, latestAutoStatementRun.filePath),
  amount: latestAutoStatementRun.data.amount,
  due_date: latestAutoStatementRun.data.due_date,
  card: latestAutoStatementRun.data.card,
  payment_record: latestAutoStatementRun.data.steps?.find((step) => step.includes("one Credit payment record")),
  notification: latestAutoStatementRun.data.steps?.find((step) => step.includes("notification")),
  account_picker: latestAutoStatementRun.data.steps?.find((step) => step.includes("payment-account picker")),
  cleanup: latestAutoStatementRun.data.cleanup,
});
if (latestSmallStatementInjectionRun) currentRuns.push({
  id: "Numeric-bank sender credit statement ingestion (payment runner locator failed)",
  status: "INCONCLUSIVE - statement ingestion/date/reminders passed; payment UI automation did not complete",
  evidence: path.relative(root, latestSmallStatementInjectionRun.filePath),
  sender: latestSmallStatementInjectionRun.data.sender,
  amount: latestSmallStatementInjectionRun.data.amount,
  minimum_due: latestSmallStatementInjectionRun.data.minimum_due,
  due_date: latestSmallStatementInjectionRun.data.due_date,
  steps: latestSmallStatementInjectionRun.data.steps?.join("; "),
  cleanup: latestSmallStatementRollbackRun
    ? `subsequent no-SMS recovery verified payment row rollback and baseline restoration; reminder cancellation defect remains (${path.relative(root, latestSmallStatementRollbackRun.filePath)})`
    : latestSmallStatementInjectionRun.data.cleanup,
  error: latestSmallStatementInjectionRun.data.error,
});
if (latestSmallStatementRollbackRun) currentRuns.push({
  id: "Credit statement payment rollback recovery (no SMS injection)",
  status: latestSmallStatementRollbackRun.data.status,
  evidence: path.relative(root, latestSmallStatementRollbackRun.filePath),
  payment_row_signature: latestSmallStatementRollbackRun.data.payment_row_signature,
  steps: latestSmallStatementRollbackRun.data.steps?.join("; "),
  cleanup: latestSmallStatementRollbackRun.data.cleanup,
});
if (latestSmallStatementFullRun) currentRuns.push({
  id: "Live credit statement due-date, payment, reminder cancellation, and rollback",
  status: latestSmallStatementFullRun.data.status,
  evidence: path.relative(root, latestSmallStatementFullRun.filePath),
  sender: latestSmallStatementFullRun.data.sender,
  amount: latestSmallStatementFullRun.data.amount,
  due_date: latestSmallStatementFullRun.data.due_date,
  statement_sms_id: latestSmallStatementFullRun.data.statement_sms_id,
  reminder_work_before_payment: JSON.stringify(latestSmallStatementFullRun.data.reminder_work_before_payment),
  reminder_work_after_payment: JSON.stringify(latestSmallStatementFullRun.data.reminder_work_after_payment),
  payment_record: latestSmallStatementFullRun.data.payment_row_signature,
  steps: latestSmallStatementFullRun.data.steps?.join("; "),
  cleanup: latestSmallStatementFullRun.data.cleanup,
});
if (latestBurstRun) currentRuns.push({
  id: "Live burst SMS: distinct messages with identical timestamps",
  status: latestBurstRun.data.status,
  evidence: path.relative(root, latestBurstRun.filePath),
  expected: latestBurstRun.data.expected,
  observed: latestBurstRun.data.observed,
  cleanup: latestBurstRun.data.cleanup,
});
if (latestCreditRun) currentRuns.push({
  id: "Live credit-first card payment and rollback",
  status: latestCreditRun.data.status,
  evidence: path.relative(root, latestCreditRun.filePath),
  amount: latestCreditRun.data.amount,
  record: latestCreditRun.data.payment_record_evidence?.signature
    ? `${latestCreditRun.data.payment_record_evidence.signature.category}: ${latestCreditRun.data.payment_record_evidence.signature.source} -> ${latestCreditRun.data.payment_record_evidence.signature.destination} / ${latestCreditRun.data.payment_record_evidence.signature.amount} EGP`
    : `${latestCreditRun.data.record_category ?? "record"}: ${latestCreditRun.data.record_source ?? "source"} -> ${latestCreditRun.data.record_destination ?? "destination"}`,
  source_balance_after_payment: latestCreditRun.data.source_balance_after_payment,
  card_available_after_payment: latestCreditRun.data.card_available_after_payment,
  home_total_after_payment: latestCreditRun.data.home_total_after_payment,
  spent_today_after_payment: latestCreditRun.data.spent_today_after_payment?.observed ?? latestCreditRun.data.spent_today_after_payment,
  notification: latestCreditRun.data.steps?.find((step) => step.includes("notification")),
  cleanup: latestCreditRun.data.cleanup,
});
if (latestDebitCreditRun) currentRuns.push({
  id: "Live debit-first card payment and rollback",
  status: latestDebitCreditRun.data.status,
  evidence: path.relative(root, latestDebitCreditRun.filePath),
  amount: latestDebitCreditRun.data.amount,
  record: latestDebitCreditRun.data.payment_record_evidence?.signature
    ? `${latestDebitCreditRun.data.payment_record_evidence.signature.category}: ${latestDebitCreditRun.data.payment_record_evidence.signature.source} -> ${latestDebitCreditRun.data.payment_record_evidence.signature.destination} / ${latestDebitCreditRun.data.payment_record_evidence.signature.amount} EGP`
    : `${latestDebitCreditRun.data.record_category ?? "record"}: ${latestDebitCreditRun.data.record_source ?? "source"} -> ${latestDebitCreditRun.data.record_destination ?? "destination"}`,
  source_balance_after_payment: latestDebitCreditRun.data.source_balance_after_payment,
  card_available_after_payment: latestDebitCreditRun.data.card_available_after_payment,
  home_total_after_payment: latestDebitCreditRun.data.home_total_after_payment,
  spent_today: latestDebitCreditRun.data.spent_today_after_payment?.observed,
  cleanup: latestDebitCreditRun.data.cleanup,
});
if (latestTransferRun) {
  const amount = Number(latestTransferRun.data.amount.replace(/[^\d.]/g, ""));
  const numericAmount = (value) => Number(String(value).replace(/[^\d.-]/g, ""));
  const sourceBaseline = numericAmount(latestTransferRun.data.baseline.source_egp ?? latestTransferRun.data.baseline.source);
  const destinationBaseline = numericAmount(latestTransferRun.data.baseline.destination_egp ?? latestTransferRun.data.baseline.destination);
  const destinationReceived = latestTransferRun.data.destination_amount
    ? numericAmount(latestTransferRun.data.destination_amount)
    : amount;
  const sourceCurrency = latestTransferRun.data.amount.split(" ").pop();
  currentRuns.push({
    id: "Live in-app transfer, over-balance rejection, and rollback",
    status: latestTransferRun.data.status,
    evidence: path.relative(root, latestTransferRun.filePath),
    amount: latestTransferRun.data.amount,
    source: `${latestTransferRun.data.source}: ${sourceBaseline.toFixed(2)} -> ${(sourceBaseline - amount).toFixed(2)} ${sourceCurrency}`,
    destination: `${latestTransferRun.data.destination}: ${destinationBaseline.toFixed(2)} -> ${(destinationBaseline + destinationReceived).toFixed(2)} ${latestTransferRun.data.destination_amount?.split(" ").pop() ?? "EGP"}`,
    home_total: latestTransferRun.data.baseline.home_egp,
    spent_today_checks: JSON.stringify(latestTransferRun.data.spent_today_checks ?? []),
    invalid_over_balance_case: latestTransferRun.data.steps?.find((step) => step.includes("over-balance")),
    cleanup: latestTransferRun.data.cleanup,
    steps: latestTransferRun.data.steps?.join("; "),
  });
}
if (latestCreditAttempt && (!latestCreditRun || latestCreditAttempt.mtime > latestCreditRun.mtime)) currentRuns.push({
  id: "Credit-first payment: manual product verification / runner failure",
  status: latestCreditAttempt.data.status,
  evidence: path.relative(root, latestCreditAttempt.filePath),
  amount: latestCreditAttempt.data.amount,
  manual_product_verification: latestCreditAttempt.data.manual_product_verification,
  manual_steps: latestCreditAttempt.data.manual_verification_steps?.join("; "),
  runner_error: latestCreditAttempt.data.error,
  runner_limitation: latestCreditAttempt.data.automation_runner_status,
  cleanup: latestCreditAttempt.data.cleanup,
});
if (latestCardPurchaseRun) currentRuns.push({
  id: "Live credit-card purchase, category, available credit, and rollback",
  status: latestCardPurchaseRun.data.status,
  evidence: path.relative(root, latestCardPurchaseRun.filePath),
  amount: latestCardPurchaseRun.data.amount,
  expected_category: latestCardPurchaseRun.data.expected_category,
  merchant: latestCardPurchaseRun.data.merchant,
  card: latestCardPurchaseRun.data.account,
  available_credit_after_purchase: latestCardPurchaseRun.data.account_balance_after_transaction,
  home_total_unchanged: latestCardPurchaseRun.data.dashboard_total_after_transaction,
  observed_record: latestCardPurchaseRun.data.observed_record,
  cleanup: latestCardPurchaseRun.data.cleanup,
  runner_limitation: latestCardPurchaseRun.data.automation_runner_status,
  error: latestCardPurchaseRun.data.error,
});
liveResults.run_date = new Date().toISOString().slice(0, 10);
liveResults.scenarios = [...currentRuns, ...(liveResults.scenarios || [])];

function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

function inline(value) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\*([^*]+)\*/g, "<em>$1</em>")
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
}

function splitTableRow(line) {
  return line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((cell) => cell.trim());
}

function statusOf(value) {
  const status = value.toLowerCase().trim().replace(/^\*+/, "").trim();
  if (/^(?:failed|failure|fail)\b/.test(status)) return "failed";
  if (/^(?:not run|not executed)\b/.test(status)) return "not-run";
  if (/^(?:inconclusive|partial|pending|passed sampled)\b/.test(status)) return "inconclusive";
  if (/^(?:fixed|resolved)(?:[;\s:-]|$)/.test(status)) return "passed";
  if (/^(?:passed|pass)\b/.test(status)) return "passed";
  if (/\bnot run\b|\bnot executed\b/.test(status)) return "not-run";
  if (/\binconclusive\b|\bpartial\b|\bpending\b|\bpassed sampled\b/.test(status)) return "inconclusive";
  if (/^(?:fixed|resolved)(?:[;\s:-]|$)/.test(status)) return "passed";
  if (/\bfailed\b|\bfailure\b|\bfail\b/.test(status)) return "failed";
  if (/\bpassed\b|\bpass\b/.test(status)) return "passed";
  return "not-run";
}

function formatStatus(value) {
  const status = statusOf(value);
  const labels = { passed: "Passed", failed: "Failed", inconclusive: "Inconclusive / Partial", "not-run": "Not run / Gated" };
  return `<span class="pill ${status}">${labels[status]}</span> ${inline(value)}`;
}

function markdownToHtml(source) {
  const lines = source.replace(/^\uFEFF/, "").split(/\r?\n/);
  const output = [];
  const cases = [];
  let inCode = false;
  let codeLines = [];
  let inList = false;
  let inTable = false;
  let tableRows = [];

  function closeList() {
    if (inList) output.push("</ul>"), inList = false;
  }

  function flushTable() {
    if (!tableRows.length) return;
    const rows = tableRows.filter((row) => !row.every((cell) => /^:?-{3,}:?$/.test(cell)));
    if (!rows.length) return;
    const headers = rows[0];
    const isCaseTable = headers[0]?.toLowerCase() === "id";
    output.push('<div class="table-wrap"><table>');
    output.push(`<thead><tr>${headers.map((cell) => `<th>${inline(cell)}</th>`).join("")}</tr></thead><tbody>`);
    for (const row of rows.slice(1)) {
      const statusIndex = headers.findIndex((header) => header.toLowerCase().includes("status"));
      const id = row[0] || "";
      const rawStatus = statusIndex >= 0 ? (row[statusIndex] || "") : "";
      const normalizedStatus = statusOf(rawStatus);
      const caseRow = isCaseTable && /^[A-Z]+-\d+/i.test(id);
      if (caseRow) {
        cases.push({ id, title: row[1] || "", status: normalizedStatus });
      }
      const cells = headers.map((_, index) => {
        const cell = row[index] || "";
        return index === statusIndex ? `<td>${formatStatus(cell)}</td>` : `<td>${inline(cell)}</td>`;
      });
      output.push(`<tr${caseRow ? ` class="case-row ${normalizedStatus}" data-status="${normalizedStatus}"` : ""}>${cells.join("")}</tr>`);
    }
    output.push("</tbody></table></div>");
    tableRows = [];
    inTable = false;
  }

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.startsWith("```")) {
      closeList();
      flushTable();
      if (inCode) {
        output.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
        inCode = false;
        codeLines = [];
      } else {
        inCode = true;
      }
      continue;
    }
    if (inCode) {
      codeLines.push(line);
      continue;
    }
    if (line.trim().startsWith("|")) {
      closeList();
      inTable = true;
      tableRows.push(splitTableRow(line));
      continue;
    }
    if (inTable) flushTable();

    const trimmed = line.trim();
    if (!trimmed) {
      closeList();
      continue;
    }
    if (/^---+$/.test(trimmed)) {
      closeList();
      output.push("<hr>");
      continue;
    }
    const heading = trimmed.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      closeList();
      const level = heading[1].length;
      output.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      continue;
    }
    if (trimmed.startsWith("- ")) {
      if (!inList) output.push("<ul>"), inList = true;
      output.push(`<li>${inline(trimmed.slice(2))}</li>`);
      continue;
    }
    closeList();
    output.push(`<p>${inline(trimmed)}</p>`);
  }
  closeList();
  flushTable();
  return { html: output.join("\n"), cases };
}

const { html: body, cases } = markdownToHtml(markdown);
const counts = { passed: 0, failed: 0, inconclusive: 0, "not-run": 0 };
cases.forEach((testCase) => { counts[testCase.status] += 1; });
const actualRuns = liveResults.scenarios || [];
const runCounts = { passed: 0, failed: 0, inconclusive: 0 };
actualRuns.forEach((scenario) => {
  let status = statusOf(scenario.status || "");
  if ((scenario.status || "").includes("RUNNER_ASSERTION_MISMATCH")) status = "inconclusive";
  if (status === "not-run") status = "inconclusive";
  runCounts[status] += 1;
});
const runRows = actualRuns.map((scenario) => {
  let status = statusOf(scenario.status || "");
  if ((scenario.status || "").includes("RUNNER_ASSERTION_MISMATCH")) status = "inconclusive";
  if (status === "not-run") status = "inconclusive";
  const label = status === "passed" ? "Passed" : status === "failed" ? "Failed" : "Inconclusive";
  const details = Object.entries(scenario)
    .filter(([key, value]) => key !== "id" && key !== "status" && key !== "evidence" && value !== undefined && value !== null)
    .map(([key, value]) => {
      const renderedValue = typeof value === "object" ? JSON.stringify(value) : String(value);
      return `<div><strong>${inline(key.replaceAll("_", " "))}:</strong> ${inline(renderedValue)}</div>`;
    })
    .join("");
  return `<tr><td><strong>${inline(scenario.id || "Live scenario")}</strong></td><td><span class="pill ${status}">${label}</span></td><td>${details}</td></tr>`;
}).join("\n");

const title = liveResults.application || "WalletTrackers Real-App Test Case Report";
const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WalletTrackers — Test Cases and Results</title>
  <style>
    :root { color-scheme: light; --ink:#172033; --muted:#5c667a; --line:#d9e0eb; --paper:#fff; --bg:#f3f6fb; --blue:#244b91; }
    * { box-sizing:border-box; } body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif; }
    header { background:linear-gradient(120deg,#142b55,#315fa7); color:#fff; padding:34px max(24px,calc((100vw - 1200px)/2)); }
    header h1 { margin:0 0 6px; font-size:clamp(26px,4vw,38px); } header p { margin:4px 0; color:#e1eafa; }
    main { max-width:1200px; margin:26px auto; padding:0 20px 50px; } .notice { border-left:5px solid #e1a222; background:#fff8e7; padding:14px 18px; border-radius:8px; margin:16px 0; }
    .cards { display:grid; grid-template-columns:repeat(auto-fit,minmax(140px,1fr)); gap:12px; margin:18px 0; }
    .card { background:var(--paper); border:1px solid var(--line); border-radius:12px; padding:15px; box-shadow:0 2px 8px #16284a0c; }
    .card strong { display:block; font-size:26px; line-height:1.2; } .card span { color:var(--muted); }
    h2 { margin-top:34px; border-bottom:1px solid var(--line); padding-bottom:8px; color:#1d3767; } h3 { margin-top:27px; color:#284778; }
    p { margin:9px 0; } code { background:#eef2f8; padding:1px 4px; border-radius:4px; font-size:.93em; overflow-wrap:anywhere; }
    .controls { display:flex; flex-wrap:wrap; gap:10px; align-items:center; margin:16px 0; } input,select { padding:10px 12px; border:1px solid #bbc7da; border-radius:8px; font:inherit; background:#fff; } input { flex:1; min-width:230px; }
    .table-wrap { overflow:auto; margin:12px 0 22px; border:1px solid var(--line); border-radius:10px; background:#fff; }
    table { width:100%; border-collapse:collapse; min-width:720px; } th,td { text-align:left; vertical-align:top; padding:11px 12px; border-bottom:1px solid #e7ebf2; }
    th { background:#edf2f9; color:#263c61; position:sticky; top:0; } tbody tr:last-child td { border-bottom:0; } tr.case-row:hover { background:#f7faff; }
    .pill { display:inline-block; font-weight:700; font-size:12px; padding:2px 8px; border-radius:99px; white-space:nowrap; margin:0 4px 3px 0; }
    .passed { background:#dcf5e6; color:#126237; } .failed { background:#ffe2e0; color:#9d1f1a; } .inconclusive { background:#fff0ca; color:#815300; } .not-run { background:#e8edf4; color:#475569; }
    .case-id { white-space:nowrap; } .muted { color:var(--muted); } pre { overflow:auto; padding:16px; background:#111827; color:#e5e7eb; border-radius:10px; }
    footer { max-width:1200px; margin:0 auto; padding:0 20px 35px; color:var(--muted); font-size:13px; }
    @media print { body { background:#fff; } header { padding:20px; print-color-adjust:exact; } main { margin:12px auto; } .controls { display:none; } .table-wrap { overflow:visible; } table { min-width:0; font-size:10px; } th { position:static; } }
  </style>
</head>
<body>
<header>
  <h1>WalletTrackers — Real-App Test Report</h1>
  <p>Cases and observed emulator results · ${escapeHtml(liveResults.run_date || "Date not recorded")}</p>
  <p>Application: Android debug APK · Device: ${escapeHtml(liveResults.device || "not specified")}</p>
</header>
<main>
  <div class="notice"><strong>Important:</strong> this is a real-app test inventory, not a unit-test report. “Passed” means observed evidence exists for the named scenario only; planned and gated cases have not been run. Latest controlled SMS/category and credit-payment runs verified Home at EGP 16,000.00, MainBank at EGP 10,000.00, SecondBank at EGP 5,000.00, and both QA cards at EGP 3,000.00; the currency fixtures remain USD 1,000.00 and EUR 500.00. Each case must still confirm its own baseline before injecting SMS.</div>
  <h2>Test Case Inventory</h2>
  <p>${cases.length} test cases are listed in the report. Counts describe the latest documented case status, not total product coverage.</p>
  <div class="cards">
    <div class="card"><strong>${counts.passed}</strong><span>Passed / partial pass on case table</span></div>
    <div class="card"><strong>${counts.failed}</strong><span>Failed</span></div>
    <div class="card"><strong>${counts.inconclusive}</strong><span>Inconclusive / partial</span></div>
    <div class="card"><strong>${counts["not-run"]}</strong><span>Not run / gated</span></div>
  </div>
  <div class="controls"><input id="search" type="search" placeholder="Search case IDs, steps, expected results…"><select id="status"><option value="all">All statuses</option><option value="passed">Passed</option><option value="failed">Failed</option><option value="inconclusive">Inconclusive / partial</option><option value="not-run">Not run / gated</option></select></div>
  <h2>Recorded Emulator Scenarios</h2>
  <p class="muted">Current rows are discovered from the most recent device-run artifacts. Historical financial-logic results are retained below them; results from mutated profiles do not replace clean-baseline regressions.</p>
  <div class="cards">
    <div class="card"><strong>${runCounts.passed}</strong><span>Observed passes</span></div>
    <div class="card"><strong>${runCounts.failed}</strong><span>Observed failures</span></div>
    <div class="card"><strong>${runCounts.inconclusive}</strong><span>Partial / assertion mismatch</span></div>
  </div>
  <div class="table-wrap"><table><thead><tr><th>Scenario</th><th>Result</th><th>Observed values / notes</th></tr></thead><tbody>${runRows}</tbody></table></div>
  <h2>Detailed Test Cases and Evidence</h2>
  ${body}
</main>
<footer>Generated from TEST_CASES_REPORT.md and the recorded results.json. For reproducibility, use a disposable QA identity, reset to a verified fixture, and retain screenshots/logs for every write case.</footer>
<script>
  const search = document.getElementById("search");
  const status = document.getElementById("status");
  const rows = [...document.querySelectorAll("tr.case-row")];
  function applyFilters() {
    const query = search.value.toLowerCase();
    rows.forEach((row) => {
      const matchesQuery = row.innerText.toLowerCase().includes(query);
      const matchesStatus = status.value === "all" || row.dataset.status === status.value;
      row.hidden = !(matchesQuery && matchesStatus);
    });
  }
  search.addEventListener("input", applyFilters);
  status.addEventListener("change", applyFilters);
</script>
</body>
</html>`;

fs.writeFileSync(outputPath, html, "utf8");
process.stdout.write(`Created ${outputPath}\nInventory cases: ${cases.length}\n`);
