#!/usr/bin/env node

import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const allowedWorkflowTools = new Set([
  "workflow_config_get",
  "workflow_config_validate",
  "workflow_list",
  "workflow_status",
]);
const requiredWorkflowTools = new Set([
  "workflow_config_get",
  "workflow_config_validate",
  "workflow_list",
]);
const forbiddenWrapperNames = [
  "workflowStart",
  "workflowSourceRead",
  "workflowUpdate",
  "workflowPreview",
  "workflowGenerate",
  "workflowValidate",
  "workflowDeploy",
  "workflowVerify",
  "workflowPatch",
  "workflowReport",
];

function sourceFiles(root) {
  const files = [];
  for (const entry of readdirSync(root)) {
    const path = join(root, entry);
    if (statSync(path).isDirectory()) {
      files.push(...sourceFiles(path));
    } else if (path.endsWith(".ts")) {
      files.push(path);
    }
  }
  return files;
}

const seen = new Set();
for (const file of sourceFiles("src")) {
  const text = readFileSync(file, "utf8");
  const toolCalls = [...text.matchAll(/call\(['"](workflow_[^'"]+)['"]/g)].map(match => match[1]);
  for (const tool of toolCalls) {
    seen.add(tool);
    assert.ok(allowedWorkflowTools.has(tool), `VS Code plugin must not call mutating workflow tool '${tool}' in ${file}`);
  }

  for (const wrapper of forbiddenWrapperNames) {
    assert.equal(
      text.includes(`function ${wrapper}`) || text.includes(`const ${wrapper}`),
      false,
      `VS Code plugin must not expose ${wrapper}; workflow mutation belongs to the external agent`
    );
  }
}

for (const tool of requiredWorkflowTools) {
  assert.ok(seen.has(tool), `VS Code plugin should expose read-only workflow status/config tool '${tool}'`);
}
