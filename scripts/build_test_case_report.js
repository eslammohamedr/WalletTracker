const fs = require("fs");
const path = require("path");
const {
  Document,
  HeadingLevel,
  Packer,
  Paragraph,
  TextRun,
  AlignmentType,
} = require("docx");

const root = path.resolve(__dirname, "..");
const markdownPath = path.join(root, "TEST_CASES_REPORT.md");
const outputPath = path.join(root, "TEST_CASES_REPORT.docx");
const lines = fs.readFileSync(markdownPath, "utf8").split(/\r?\n/);
const content = [];

function clean(value) {
  return value
    .replace(/<br\s*\/?>/gi, "; ")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\\\|/g, "|")
    .trim();
}

function addCase(cells, headers) {
  const id = clean(cells[0]);
  const title = clean(cells[1]);
  content.push(new Paragraph({
    text: `${id} — ${title}`,
    heading: HeadingLevel.HEADING_3,
    keepNext: true,
    spacing: { before: 160, after: 60 },
  }));
  for (let index = 2; index < cells.length; index += 1) {
    const label = clean(headers[index] || `Field ${index}`);
    const value = clean(cells[index]);
    if (!value) continue;
    content.push(new Paragraph({
      children: [
        new TextRun({ text: `${label}: `, bold: true }),
        new TextRun(value),
      ],
      spacing: { after: 50 },
    }));
  }
}

for (let index = 0; index < lines.length; index += 1) {
  const line = lines[index].trim();
  if (!line || line === "---") continue;
  if (line.startsWith("|")) {
    const rows = [];
    while (index < lines.length && lines[index].trim().startsWith("|")) {
      const row = lines[index].trim();
      const cells = row.slice(1, -1).split("|").map((cell) => cell.trim());
      if (!cells.every((cell) => /^:?-{3,}:?$/.test(cell))) rows.push(cells);
      index += 1;
    }
    index -= 1;
    if (rows.length > 1) {
      const headers = rows[0];
      for (const row of rows.slice(1)) {
        if (headers[0].toLowerCase() === "id") {
          addCase(row, headers);
        } else {
          const label = row.map(clean).join(" | ");
          content.push(new Paragraph({ text: `- ${label}`, indent: { left: 360 }, spacing: { after: 60 } }));
        }
      }
    }
    continue;
  }
  const heading = line.match(/^(#{1,3})\s+(.+)$/);
  if (heading) {
    const level = heading[1].length;
    const headingLevel = level === 1 ? HeadingLevel.HEADING_1 :
      level === 2 ? HeadingLevel.HEADING_2 : HeadingLevel.HEADING_3;
    content.push(new Paragraph({
      text: clean(heading[2]),
      heading: headingLevel,
      keepNext: true,
      spacing: { before: level === 1 ? 260 : 180, after: 100 },
    }));
    continue;
  }
  if (line.startsWith("- ")) {
    content.push(new Paragraph({ text: `- ${clean(line.slice(2))}`, indent: { left: 360 }, spacing: { after: 60 } }));
    continue;
  }
  content.push(new Paragraph({ text: clean(line), spacing: { after: 80 } }));
}

const document = new Document({
  creator: "WalletTrackers QA",
  title: "WalletTrackers Real-App Test Case Report",
  subject: "Requirements-based Android UI and SMS test cases",
  description: "Execution results, prerequisites, steps, and expected outcomes for real-app testing.",
  styles: {
    default: {
      document: { run: { font: "Aptos", size: 20, color: "243047" } },
      heading1: { run: { font: "Aptos Display", size: 34, bold: true, color: "253C78" }, paragraph: { spacing: { before: 320, after: 140 } } },
      heading2: { run: { font: "Aptos Display", size: 27, bold: true, color: "36599A" }, paragraph: { spacing: { before: 240, after: 110 } } },
      heading3: { run: { font: "Aptos", size: 22, bold: true, color: "253C78" }, paragraph: { spacing: { before: 180, after: 60 } } },
    },
  },
  sections: [{
    properties: {
      page: {
        margin: { top: 900, right: 1000, bottom: 900, left: 1000 },
      },
    },
    children: [
      new Paragraph({
        text: "REAL-APP TEST CASE REPORT",
        heading: HeadingLevel.TITLE,
        alignment: AlignmentType.CENTER,
        spacing: { after: 160 },
      }),
      ...content,
    ],
  }],
});

Packer.toBuffer(document).then((buffer) => {
  fs.writeFileSync(outputPath, buffer);
  process.stdout.write(`Created ${outputPath}\n`);
});
