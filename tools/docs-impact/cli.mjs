#!/usr/bin/env node
import path from "node:path";
import { canonicalJson } from "./canonical.mjs";
import { CLASSIFICATION } from "./constants.mjs";
import { classifyRepository } from "./classify.mjs";

const USAGE = `Usage:
  node tools/docs-impact/cli.mjs classify \\
    --repo <absolute-repository-root> \\
    --git-executable <absolute-trusted-git-executable> \\
    --repository-id <owner/name> \\
    --base <full-base-commit-id> \\
    --head <full-head-commit-id> \\
    --policy-path <repository-relative-policy-yaml>

The command reads committed Git objects and writes one canonical JSON analysis
to stdout. It never executes candidate code or mutates the repository.

Exit codes are fail-closed and are not a readiness decision:
  0   NO_ACTION_REQUIRED
  2   POLICY_ERROR or analysis could not start
  3   ACTION_REQUIRED; a later trusted readiness stage must decide completion
  64  Invalid command usage
`;

function parseArguments(argv) {
  if (argv.length === 1 && ["--help", "-h"].includes(argv[0])) {
    return { help: true };
  }
  if (argv[0] !== "classify") {
    throw new Error("The explicit 'classify' command is required");
  }
  const values = {};
  for (let index = 1; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (!flag?.startsWith("--") || value === undefined || value.startsWith("--")) {
      throw new Error(`Invalid argument pair at ${flag ?? "end of command"}`);
    }
    const key = flag.slice(2);
    if (!new Set([
      "repo",
      "git-executable",
      "repository-id",
      "base",
      "head",
      "policy-path"
    ]).has(key)) {
      throw new Error(`Unknown argument ${flag}`);
    }
    if (Object.hasOwn(values, key)) {
      throw new Error(`Duplicate argument ${flag}`);
    }
    values[key] = value;
  }
  for (const required of [
    "repo",
    "git-executable",
    "repository-id",
    "base",
    "head",
    "policy-path"
  ]) {
    if (!values[required]) {
      throw new Error(`Missing required argument --${required}`);
    }
  }
  if (!path.isAbsolute(values.repo)) {
    throw new Error("--repo must be an absolute path");
  }
  if (!path.isAbsolute(values["git-executable"])) {
    throw new Error("--git-executable must be an absolute path");
  }
  if (path.isAbsolute(values["policy-path"])) {
    throw new Error("--policy-path must be a Git repository-relative path");
  }
  return values;
}

async function main() {
  let options;
  try {
    options = parseArguments(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`${error.message}\n\n${USAGE}`);
    process.exitCode = 64;
    return;
  }
  if (options.help) {
    process.stdout.write(USAGE);
    return;
  }
  try {
    const analysis = await classifyRepository({
      repoRoot: options.repo,
      gitExecutable: options["git-executable"],
      repositoryId: options["repository-id"],
      base: options.base,
      head: options.head,
      policyPath: options["policy-path"]
    });
    process.stdout.write(canonicalJson(analysis));
    process.exitCode = analysis.classification === CLASSIFICATION.POLICY_ERROR
      ? 2
      : analysis.classification === CLASSIFICATION.ACTION_REQUIRED
        ? 3
        : 0;
  } catch (error) {
    process.stderr.write(`Documentation impact classification could not start: ${error.message}\n`);
    process.exitCode = 2;
  }
}

await main();
