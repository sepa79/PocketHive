import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { SaxesParser } from "saxes";
import { parseDocument } from "yaml";

import { extractFences, publishedDocs } from "./docs-scope.mjs";

const DATA_LANGUAGES = new Set(["json", "xml", "yaml", "yml"]);
const BASH_LANGUAGES = new Set(["bash", "sh", "shell"]);
const POWERSHELL_LANGUAGES = new Set(["powershell", "pwsh"]);
const BATCH_LANGUAGES = new Set(["bat", "batch", "cmd"]);

function log(message) {
  console.log(`[docs-content] ${message}`);
}

function commandWorks(command, args) {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  return !result.error && result.status === 0;
}

function findBash() {
  const configured = process.env.DOCS_TEST_BASH_EXECUTABLE?.trim();
  if (configured) {
    if (!existsSync(configured)) {
      throw new Error(`DOCS_TEST_BASH_EXECUTABLE does not exist: ${configured}`);
    }
    return configured;
  }

  const candidates =
    process.platform === "win32"
      ? [
          "C:\\Program Files\\Git\\bin\\bash.exe",
          "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
        ]
      : ["bash"];
  return candidates.find(
    (candidate) =>
      (candidate === "bash" || existsSync(candidate)) &&
      commandWorks(candidate, ["--version"]),
  );
}

function findPowerShell() {
  const configured = process.env.DOCS_TEST_POWERSHELL_EXECUTABLE?.trim();
  if (configured) {
    if (!existsSync(configured)) {
      throw new Error(
        `DOCS_TEST_POWERSHELL_EXECUTABLE does not exist: ${configured}`,
      );
    }
    return configured;
  }

  const candidates = process.platform === "win32" ? ["powershell.exe", "pwsh"] : ["pwsh"];
  return candidates.find((candidate) =>
    commandWorks(candidate, ["-NoProfile", "-NonInteractive", "-Command", "$PSVersionTable.PSVersion.ToString()"]),
  );
}

function validateJson(content) {
  JSON.parse(content);
}

function validateYaml(content) {
  const document = parseDocument(content, { prettyErrors: true, uniqueKeys: true });
  if (document.errors.length > 0) {
    throw document.errors[0];
  }
}

function validateXml(content) {
  let parsingError;
  const parser = new SaxesParser({ xmlns: true });
  parser.onerror = (error) => {
    parsingError = error;
  };
  parser.write(content).close();
  if (parsingError) throw parsingError;
}

function validateBash(executable, content) {
  const result = spawnSync(executable, ["-n"], {
    encoding: "utf8",
    input: content,
    windowsHide: true,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || `exit ${result.status}`).trim());
  }
}

function validatePowerShell(executable, content) {
  const parserScript = [
    "$source = [Console]::In.ReadToEnd()",
    "$tokens = $null",
    "$parseErrors = $null",
    "[System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors) | Out-Null",
    "if ($parseErrors.Count -gt 0) {",
    "  $parseErrors | ForEach-Object { [Console]::Error.WriteLine($_.Message) }",
    "  exit 1",
    "}",
  ].join("\n");
  const result = spawnSync(
    executable,
    ["-NoProfile", "-NonInteractive", "-Command", parserScript],
    {
      encoding: "utf8",
      input: content,
      windowsHide: true,
    },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || `exit ${result.status}`).trim());
  }
}

function recordFailure(failures, fence, error) {
  const message = String(error?.message || error).split(/\r?\n/)[0];
  failures.push(`${fence.relativePath}:${fence.line} [${fence.language}] ${message}`);
}

async function main() {
  const docs = await publishedDocs();
  const bash = findBash();
  const powerShell = findPowerShell();
  const counts = new Map();
  const failures = [];
  let totalFences = 0;
  let checkedData = 0;
  let checkedShell = 0;
  let skippedShell = 0;

  for (const doc of docs) {
    const source = await readFile(doc.path, "utf8");
    for (const fence of extractFences(source, doc.relativePath)) {
      totalFences += 1;
      const language = fence.language || "unlabeled";
      counts.set(language, (counts.get(language) || 0) + 1);

      try {
        if (fence.language === "json") {
          validateJson(fence.content);
          checkedData += 1;
        } else if (fence.language === "yaml" || fence.language === "yml") {
          validateYaml(fence.content);
          checkedData += 1;
        } else if (fence.language === "xml") {
          validateXml(fence.content);
          checkedData += 1;
        } else if (BASH_LANGUAGES.has(fence.language)) {
          if (bash) {
            validateBash(bash, fence.content);
            checkedShell += 1;
          } else {
            skippedShell += 1;
          }
        } else if (POWERSHELL_LANGUAGES.has(fence.language)) {
          if (powerShell) {
            validatePowerShell(powerShell, fence.content);
            checkedShell += 1;
          } else {
            skippedShell += 1;
          }
        } else if (BATCH_LANGUAGES.has(fence.language)) {
          skippedShell += 1;
        }
      } catch (error) {
        recordFailure(failures, fence, error);
      }
    }
  }

  const languages = [...counts.entries()]
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))
    .map(([language, count]) => `${language}=${count}`)
    .join(", ");
  const declaredData = [...counts.entries()]
    .filter(([language]) => DATA_LANGUAGES.has(language))
    .reduce((sum, [, count]) => sum + count, 0);

  log(`Published scope: ${docs.length} Markdown/MDX files`);
  log(`Code fences: ${totalFences} (${languages})`);
  log(`Structured examples checked: ${checkedData}/${declaredData}`);
  log(`Shell blocks syntax-checked: ${checkedShell}; explicitly skipped: ${skippedShell}`);
  log(`Bash parser: ${bash || "not available"}`);
  log(`PowerShell parser: ${powerShell || "not available"}`);

  if (failures.length > 0) {
    console.error(`\n[docs-content] FAIL ${failures.length} invalid example(s):`);
    for (const failure of failures) console.error(`- ${failure}`);
    process.exitCode = 1;
    return;
  }

  log("PASS all testable published examples and shell blocks");
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
