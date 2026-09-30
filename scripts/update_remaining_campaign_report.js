const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const directory = path.join(root, "app/build/device-smoke");
const caseIds = [...Array.from({ length: 17 }, (_, index) => `ON-${String(index + 1).padStart(2, "0")}`), "AI-01", "AI-02", "AI-03", "AI-04", "AI-05", "AI-06", "DATA-07", "APP-08", "APP-12"];

function files(folder) {
  return fs.readdirSync(folder, { withFileTypes: true }).flatMap((entry) => {
    const filename = path.join(folder, entry.name);
    return entry.isDirectory() ? files(filename) : entry.name === "results.json" ? [filename] : [];
  });
}

const runs = files(directory).flatMap((filename) => {
  const data = JSON.parse(fs.readFileSync(filename, "utf8").replace(/^\uFEFF/, ""));
  return caseIds.includes(data.case) && data.finished_at && data.status !== "IN_PROGRESS"
    ? [{ data, filename, time: Date.parse(data.finished_at) }] : [];
}).sort((first, second) => second.time - first.time);

let markdown = fs.readFileSync(path.join(root, "TEST_CASES_REPORT.md"), "utf8");
const summary = [];
for (const caseId of caseIds) {
  const branches = caseId === "ON-07" ? ["keep_separate", "accept_merge"] : caseId === "ON-09" ? ["new_cash", "existing_cash"] : ["default"];
  const latest = branches.map((branch) => runs.find((run) => run.data.case === caseId && (run.data.branch || "default") === branch));
  const present = latest.filter(Boolean);
  let status = "NOT_RUN";
  if (present.length) {
    status = present.some((run) => run.data.status === "FAIL") ? "FAIL"
      : present.some((run) => run.data.status === "ERROR") ? "ERROR"
        : present.length === branches.length && present.every((run) => run.data.status === "PASS") ? "PASS" : "PARTIAL";
  }
  summary.push({ case: caseId, status, evidence: present.map((run) => path.relative(root, run.filename).replaceAll("\\", "/")) });
  if (!present.length) continue;
  const label = { PASS: "Passed", FAIL: "Failed", ERROR: "Inconclusive / runner error", PARTIAL: "Partial" }[status];
  const details = present.map((run) => {
    const evidence = path.relative(root, run.filename).replaceAll("\\", "/");
    const error = run.data.error ? ` ${run.data.error.split("\n")[0].replaceAll("|", "/").slice(0, 420)}.` : "";
    const branch = run.data.branch && run.data.branch !== "default" ? `${run.data.branch}: ` : "";
    const accounts = run.data.actual?.accounts?.map((account) => `${account.name}: ${account.amount} ${account.currency}`).join("; ");
    const values = accounts ? ` Observed accounts: ${accounts}. Records: ${run.data.actual.records?.length ?? "unknown"}.` : "";
    const checks = run.data.checks ? ` Checks: ${run.data.checks.filter((item) => item.status === "PASS").length}/${run.data.checks.length} passed. ${run.data.checks.filter((item) => item.status !== "PASS").map((item) => `${item.name}: ${item.error || item.status}`).join("; ").replaceAll("|", "/").slice(0, 650)}` : "";
    return `${branch}${run.data.status}.${error}${values}${checks} Evidence: [results](${evidence}).`;
  }).join(" ");
  const replacement = `${label} — ${details} Debug APK on disposable QA emulator; initialization and cleanup are recorded per run.`;
  markdown = markdown.split(/\r?\n/).map((line) => {
    if (!line.startsWith(`| ${caseId} |`)) return line;
    const columns = line.split("|");
    columns[columns.length - 2] = ` ${replacement} `;
    return columns.join("|");
  }).join("\n");
}
const counts = Object.fromEntries(["PASS", "FAIL", "ERROR", "PARTIAL", "NOT_RUN"].map((status) => [status, summary.filter((item) => item.status === status).length]));
const campaignSize = caseIds.length;
const banner = `## Remaining ${campaignSize}-Case Campaign\n\nUpdated ${new Date().toISOString()}. ${counts.PASS} passed, ${counts.FAIL} failed, ${counts.ERROR} runner/setup errors, ${counts.PARTIAL} partial, ${counts.NOT_RUN} not executed. Failed and runner-error cases are not passes.\n\nEnvironment: real debug APK, Firebase test identities, and emulator UI. Historical SMS are seeded through the Android provider; Android 37 restricted-message access is enabled only on the disposable QA AVD for these fixtures. AI fault-injection cases use a localhost dependency stub with dummy keys; they do not certify external provider availability. Pixel 7 Pro is not used.\n\n`;
markdown = markdown.replace(/## Remaining \d+-Case Campaign\n[\s\S]*?Pixel 7 Pro is not used\.\n\n/, "");
const headingEnd = markdown.indexOf("\n");
markdown = markdown.slice(0, headingEnd + 1) + "\n" + banner + markdown.slice(headingEnd + 1).trimStart();
fs.writeFileSync(path.join(root, "TEST_CASES_REPORT.md"), markdown);
fs.writeFileSync(path.join(directory, "remaining-campaign-summary.json"), JSON.stringify({ updated_at: new Date().toISOString(), counts, cases: summary }, null, 2));
process.stdout.write(JSON.stringify(counts) + "\n");
