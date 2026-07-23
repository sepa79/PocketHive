import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import { discoverScenarioBundleFiles, runScenarioConfigMigration } from "./index.mjs";

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

test("check reports legacy scenario authoring id and topology beeId", async () => {
  const root = await fixtureDir();
  try {
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - id: genA
      role: generator
      config: {}
    - id: modA
      role: moderator
      config: {}
topology:
  version: 1
  edges:
    - id: e1
      from: { beeId: genA, port: out }
      to: { beeId: modA, port: in }
`, "utf8");

    const result = await runScenarioConfigMigration({ command: "check", paths: [scenario] });
    assert.equal(result.ok, false);
    assert.equal(result.summary.legacyFindings, 4);
    assert.deepEqual(
      result.files[0].findings.map((finding) => finding.code),
      [
        "LEGACY_SCENARIO_AUTHORING",
        "LEGACY_SCENARIO_AUTHORING",
        "LEGACY_SCENARIO_AUTHORING",
        "LEGACY_SCENARIO_AUTHORING",
      ]
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate rewrites scenario authoring id and topology beeId to role", async () => {
  const root = await fixtureDir();
  try {
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - id: genA
      role: generator
      config: {}
    - id: modA
      role: moderator
      config: {}
topology:
  version: 1
  edges:
    - id: e1
      from: { beeId: genA, port: out }
      to: { beeId: modA, port: in }
`, "utf8");

    const result = await runScenarioConfigMigration({ command: "migrate", paths: [scenario] });
    assert.equal(result.ok, true);
    assert.equal(result.summary.changed, 1);

    const updated = await readFile(scenario, "utf8");
    assert.match(updated, /^\s+- role: genA$/m);
    assert.match(updated, /^\s+- role: modA$/m);
    assert.doesNotMatch(updated, /^\s+id: genA$/m);
    assert.doesNotMatch(updated, /beeId:/);
    assert.match(updated, /from: \{ port: out, role: genA \}/);
    assert.match(updated, /to: \{ port: in, role: modA \}/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate fails when topology role conflicts with legacy beeId", async () => {
  const root = await fixtureDir();
  try {
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - id: genA
      role: generator
      config: {}
    - id: modA
      role: moderator
      config: {}
topology:
  version: 1
  edges:
    - id: e1
      from: { beeId: genA, role: modA, port: out }
      to: { beeId: modA, port: in }
`, "utf8");

    const result = await runScenarioConfigMigration({ command: "migrate", paths: [scenario] });
    assert.equal(result.ok, false);
    assert.equal(result.files[0].findings[0].code, "SCENARIO_AUTHORING_CONFLICT");

    const unchanged = await readFile(scenario, "utf8");
    assert.match(unchanged, /beeId: genA/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("check reports legacy pockethive worker config and ignores unrelated YAML files", async () => {
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

    const files = await discoverScenarioBundleFiles([root]);
    assert.deepEqual(files, [join(root, "scenario.yml")]);

    const result = await runScenarioConfigMigration({ command: "check", paths: [root] });
    assert.equal(result.ok, false);
    assert.equal(result.summary.legacyFindings, 2);
    assert.equal(result.summary.errors, 2);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate removes nested SUT endpoint ids that match their canonical map keys", async () => {
  const root = await fixtureDir();
  try {
    const sutDir = join(root, "sut", "wiremock-local");
    await mkdir(sutDir, { recursive: true });
    const sut = join(sutDir, "sut.yaml");
    await writeFile(sut, `id: wiremock-local
name: WireMock local
endpoints:
  default:
    id: default
    kind: HTTP
    baseUrl: http://wiremock:8080
  health:
    id:
    kind: HTTP
    baseUrl: http://wiremock:8080/__admin/health
`, "utf8");

    const check = await runScenarioConfigMigration({ command: "check", paths: [root] });
    assert.equal(check.ok, false);
    assert.equal(check.files[0].findings[0].code, "LEGACY_SUT_ENDPOINT_ID");

    const migrated = await runScenarioConfigMigration({ command: "migrate", paths: [root] });
    assert.equal(migrated.ok, true);
    assert.equal(migrated.summary.changed, 1);
    const updated = await readFile(sut, "utf8");
    assert.doesNotMatch(updated, /^\s+id: default$/m);
    assert.doesNotMatch(updated, /^\s+id:\s*$/m);
    assert.match(updated, /^  default:$/m);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate fails when nested SUT endpoint id conflicts with its map key", async () => {
  const root = await fixtureDir();
  try {
    const sut = join(root, "sut.yaml");
    await writeFile(sut, `id: wiremock-local
name: WireMock local
endpoints:
  default:
    id: payments
    kind: HTTP
    baseUrl: http://wiremock:8080
`, "utf8");

    const result = await runScenarioConfigMigration({ command: "migrate", paths: [sut] });
    assert.equal(result.ok, false);
    assert.equal(result.files[0].findings[0].code, "SUT_ENDPOINT_ID_CONFLICT");
    assert.match(await readFile(sut, "utf8"), /id: payments/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate preserves and rejects non-exact or non-scalar nested SUT endpoint ids", async () => {
  const root = await fixtureDir();
  try {
    const sut = join(root, "sut.yaml");
    const original = `id: wiremock-local
name: WireMock local
endpoints:
  default:
    id: " default "
    kind: HTTP
    baseUrl: http://wiremock:8080
  health:
    id: [health]
    kind: HTTP
    baseUrl: http://wiremock:8080/__admin/health
`;
    await writeFile(sut, original, "utf8");

    const result = await runScenarioConfigMigration({ command: "migrate", paths: [sut] });

    assert.equal(result.ok, false);
    assert.equal(result.summary.errors, 2);
    assert.deepEqual(
      result.files[0].findings.map((finding) => finding.code),
      ["SUT_ENDPOINT_ID_CONFLICT", "SUT_ENDPOINT_ID_CONFLICT"]
    );
    assert.equal(await readFile(sut, "utf8"), original);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("SUT-only migration does not load scenario capability manifests", async () => {
  const root = await fixtureDir();
  try {
    const sut = join(root, "sut.yaml");
    await writeFile(sut, `id: wiremock-local
name: WireMock local
endpoints:
  default:
    id: default
    kind: HTTP
    baseUrl: http://wiremock:8080
`, "utf8");

    const result = await runScenarioConfigMigration({
      command: "migrate",
      paths: [sut],
      capabilitiesDir: join(root, "missing-capabilities"),
    });

    assert.equal(result.ok, true);
    assert.doesNotMatch(await readFile(sut, "utf8"), /^\s+id: default$/m);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("check rejects malformed SUT YAML", async () => {
  const root = await fixtureDir();
  try {
    const sut = join(root, "sut.yaml");
    await writeFile(sut, `id: broken
endpoints:
  default: [
`, "utf8");

    const result = await runScenarioConfigMigration({ command: "check", paths: [sut] });

    assert.equal(result.ok, false);
    assert.equal(result.summary.errors, 1);
    assert.equal(result.files[0].findings[0].code, "INVALID_YAML");
    assert.match(result.files[0].findings[0].path, /^\$:\d+:\d+$/);
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

test("migrate adds missing IO selector for one known subblock", async () => {
  const root = await fixtureDir();
  try {
    const capabilitiesDir = await capabilityFixtureDir(root);
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - role: processor
      config:
        inputs:
          redis:
            listName: ph:dataset
`, "utf8");

    const result = await runScenarioConfigMigration({
      command: "migrate",
      paths: [scenario],
      capabilitiesDir,
    });
    assert.equal(result.ok, true);
    assert.equal(result.summary.changed, 1);
    assert.equal(result.files[0].operations[0].action, "set");
    assert.equal(result.files[0].operations[0].value, "REDIS_DATASET");

    const updated = await readFile(scenario, "utf8");
    assert.match(updated, /^\s+type: REDIS_DATASET$/m);
    assert.match(updated, /^\s+redis:\s*$/m);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate adds safe explicit values for selected IO required fields", async () => {
  const root = await fixtureDir();
  try {
    const capabilitiesDir = await capabilityFixtureDir(root);
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - role: generator
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 5
`, "utf8");

    const check = await runScenarioConfigMigration({
      command: "check",
      paths: [scenario],
      capabilitiesDir,
    });
    assert.equal(check.ok, false);
    assert.equal(check.files[0].findings[0].code, "IO_REQUIRED_CONFIG_MISSING");

    const result = await runScenarioConfigMigration({
      command: "migrate",
      paths: [scenario],
      capabilitiesDir,
    });
    assert.equal(result.ok, true);
    assert.equal(result.summary.changed, 1);

    const updated = await readFile(scenario, "utf8");
    assert.match(updated, /^\s+maxMessages: 0$/m);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("migrate refuses to infer selector when multiple IO subblocks exist", async () => {
  const root = await fixtureDir();
  try {
    const capabilitiesDir = await capabilityFixtureDir(root);
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - role: generator
      config:
        inputs:
          redis:
            listName: ph:dataset
          csv:
            filePath: /app/scenario/datasets/users.csv
`, "utf8");

    const result = await runScenarioConfigMigration({
      command: "migrate",
      paths: [scenario],
      capabilitiesDir,
    });
    assert.equal(result.ok, false);
    assert.equal(result.summary.ioSelectorFindings, 1);
    assert.equal(result.files[0].findings[0].code, "IO_SELECTOR_AMBIGUOUS");

    const unchanged = await readFile(scenario, "utf8");
    assert.doesNotMatch(unchanged, /^\s+type:/m);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("check reports mismatched IO selector without rewriting", async () => {
  const root = await fixtureDir();
  try {
    const capabilitiesDir = await capabilityFixtureDir(root);
    const scenario = join(root, "scenario.yaml");
    await writeFile(scenario, `id: demo
template:
  bees:
    - role: processor
      config:
        inputs:
          type: RABBITMQ
          redis:
            listName: ph:dataset
`, "utf8");

    const result = await runScenarioConfigMigration({
      command: "check",
      paths: [scenario],
      capabilitiesDir,
    });
    assert.equal(result.ok, false);
    assert.equal(result.summary.ioSelectorFindings, 1);
    assert.equal(result.files[0].findings[0].code, "IO_SELECTOR_MISMATCH");
    assert.match(result.files[0].findings[0].message, /REDIS_DATASET/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

async function fixtureDir() {
  return mkdtemp(join(tmpdir(), "scenario-config-migrate-"));
}

async function capabilityFixtureDir(root) {
  const capabilitiesDir = join(root, "capabilities");
  await mkdir(capabilitiesDir, { recursive: true });
  await writeFile(join(capabilitiesDir, "io.scheduler.latest.yaml"), `schemaVersion: "1.0"
capabilitiesVersion: "1.0"
role: io-scheduler
image:
  name: io-scheduler
ui:
  ioScope: INPUT
  ioType: SCHEDULER
config:
  - name: inputs.scheduler.ratePerSec
    type: number
    required: true
  - name: inputs.scheduler.maxMessages
    type: number
    required: true
    default: 0
`, "utf8");
  await writeFile(join(capabilitiesDir, "io.redis-dataset.latest.yaml"), `schemaVersion: "1.0"
capabilitiesVersion: "1.0"
role: io-redis-dataset
image:
  name: io-redis-dataset
ui:
  ioScope: INPUT
  ioType: REDIS_DATASET
config:
  - name: inputs.redis.listName
    type: string
`, "utf8");
  await writeFile(join(capabilitiesDir, "io.csv-dataset.latest.yaml"), `schemaVersion: "1.0"
capabilitiesVersion: "1.0"
role: io-csv-dataset
image:
  name: io-csv-dataset
ui:
  ioScope: INPUT
  ioType: CSV_DATASET
config:
  - name: inputs.csv.filePath
    type: string
`, "utf8");
  return capabilitiesDir;
}
