#!/usr/bin/env node
import { runScenarioConfigMigration } from "./index.mjs";

const USAGE = `Usage:
  scenario-config-migrate check [--json] <path...>
  scenario-config-migrate migrate [--dry-run] [--json] <path...>
`;

async function main(argv) {
  const args = [...argv];
  const command = args.shift();
  const json = removeFlag(args, "--json");
  const dryRun = removeFlag(args, "--dry-run");

  if (!["check", "migrate"].includes(command) || args.length === 0) {
    process.stderr.write(USAGE);
    process.exitCode = 2;
    return;
  }
  if (dryRun && command !== "migrate") {
    process.stderr.write("--dry-run is only valid for migrate\n");
    process.exitCode = 2;
    return;
  }

  const result = await runScenarioConfigMigration({ command, paths: args, dryRun });
  if (json) {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  } else {
    process.stdout.write(formatText(result));
  }
  process.exitCode = result.ok ? 0 : 1;
}

function removeFlag(args, flag) {
  const index = args.indexOf(flag);
  if (index < 0) return false;
  args.splice(index, 1);
  return true;
}

function formatText(result) {
  const lines = [];
  for (const file of result.files) {
    const prefix = file.findings.length > 0 ? "FAIL" : file.changed ? (result.dryRun ? "WOULD UPDATE" : "UPDATED") : "OK";
    lines.push(`${prefix} ${file.path}`);
    for (const finding of file.findings) {
      lines.push(`  ${finding.severity.toUpperCase()} ${finding.code} ${finding.path}`);
      lines.push(`    ${finding.message}`);
      if (finding.fix) lines.push(`    fix: ${finding.fix}`);
    }
    for (const operation of file.operations) {
      if (operation.action === "move") {
        lines.push(`  move ${operation.sourcePath} -> ${operation.targetPath}`);
      } else if (operation.action === "remove") {
        lines.push(`  remove ${operation.sourcePath}`);
      }
    }
  }
  lines.push(
    `summary files=${result.summary.files} changed=${result.summary.changed} operations=${result.summary.operations} findings=${result.summary.findings} errors=${result.summary.errors}`
  );
  return `${lines.join("\n")}\n`;
}

main(process.argv.slice(2)).catch((error) => {
  process.stderr.write(`${error.stack || error.message || error}\n`);
  process.exitCode = 1;
});
