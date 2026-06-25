import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import { discoverScenarioFiles, runScenarioConfigMigration } from "./index.mjs";

test("migrate moves config.worker keys to direct bee config", async () => {
  const root = await fixtureDir();
  try {
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  image: swarm-controller:latest
  bees:
    - id: gen
      role: generator
      config:
        # keep this comment
        inputs:
          scheduler:
            ratePerSec: 5
        worker:
          message:
            body: hi
          config:
            mode:
              type: finite
`, "utf8");

    const result = await runScenarioConfigMigration({ command: "migrate", paths: [scenario] });
    assert.equal(result.ok, true);
    assert.equal(result.summary.changed, 1);

    const updated = await readFile(scenario, "utf8");
    assert.match(updated, /# keep this comment/);
    assert.doesNotMatch(updated, /^\s+worker:\s*$/m);
    assert.match(updated, /^\s+message:\s*$/m);
    assert.match(updated, /^\s+mode:\s*$/m);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("dry-run reports output without writing", async () => {
  const root = await fixtureDir();
  try {
    const scenario = join(root, "scenario.yaml");
    const original = `id: demo
template:
  bees:
    - role: generator
      config:
        worker:
          message:
            body: hi
`;
    await writeFile(scenario, original, "utf8");

    const result = await runScenarioConfigMigration({ command: "migrate", dryRun: true, paths: [scenario] });
    assert.equal(result.ok, true);
    assert.equal(result.files[0].changed, true);
    assert.match(result.files[0].output, /^\s+message:\s*$/m);
    assert.equal(await readFile(scenario, "utf8"), original);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("check reports legacy pockethive worker config and discovers only scenario files", async () => {
  const root = await fixtureDir();
  try {
    await writeFile(join(root, "other.yaml"), "worker: {}\n", "utf8");
    await writeFile(join(root, "scenario.yml"), `id: demo
template:
  bees:
    - role: processor
      config:
        pockethive:
          worker:
            config:
              baseUrl: http://example
`, "utf8");

    const files = await discoverScenarioFiles([root]);
    assert.deepEqual(files, [join(root, "scenario.yml")]);

    const result = await runScenarioConfigMigration({ command: "check", paths: [root] });
    assert.equal(result.ok, false);
    assert.equal(result.summary.legacyFindings, 2);
    assert.equal(result.summary.errors, 2);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate fails on conflicting target value", async () => {
  const root = await fixtureDir();
  try {
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - role: generator
      config:
        message:
          body: existing
        worker:
          message:
            body: legacy
`, "utf8");

    const result = await runScenarioConfigMigration({ command: "migrate", paths: [scenario] });
    assert.equal(result.ok, false);
    assert.equal(result.summary.errors, 1);
    assert.equal(result.files[0].findings[0].code, "CONFIG_MIGRATION_CONFLICT");

    const unchanged = await readFile(scenario, "utf8");
    assert.match(unchanged, /^\s+worker:\s*$/m);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

async function fixtureDir() {
  return mkdtemp(join(tmpdir(), "scenario-config-migrate-"));
}
