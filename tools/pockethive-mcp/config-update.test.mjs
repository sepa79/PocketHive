import test from "node:test";
import assert from "node:assert/strict";
import {
  deepMergeConfig,
  latestComponentConfigFromJournalPage,
  planComponentConfigUpdate,
} from "./config-update.mjs";

test("deepMergeConfig preserves sibling fields while replacing the requested leaf", () => {
  const current = {
    enabled: true,
    inputs: {
      redis: {
        ratePerSec: 2,
        keyPattern: "customer:{id}",
        nested: { keep: "yes" },
      },
    },
    outputs: [{ type: "rabbit" }],
  };

  const merged = deepMergeConfig(current, {
    inputs: {
      redis: {
        ratePerSec: 10,
      },
    },
  });

  assert.deepEqual(merged, {
    enabled: true,
    inputs: {
      redis: {
        ratePerSec: 10,
        keyPattern: "customer:{id}",
        nested: { keep: "yes" },
      },
    },
    outputs: [{ type: "rabbit" }],
  });
  assert.equal(current.inputs.redis.ratePerSec, 2);
});

test("latestComponentConfigFromJournalPage selects the newest exact status-full entry", () => {
  const page = {
    items: [
      {
        type: "status-delta",
        scope: { role: "generator", instance: "gen-1" },
        data: { config: { ratePerSec: 999 } },
      },
      {
        type: "status-full",
        timestamp: "2026-05-15T15:00:00Z",
        runId: "run-1",
        scope: { role: "generator", instance: "gen-1" },
        data: { config: { ratePerSec: 10, keep: true } },
      },
      {
        type: "status-full",
        scope: { role: "generator", instance: "gen-2" },
        data: { config: { ratePerSec: 20 } },
      },
    ],
  };

  assert.deepEqual(latestComponentConfigFromJournalPage(page, { role: "generator", instanceId: "gen-1" }), {
    matchedRole: "generator",
    matchedInstanceId: "gen-1",
    receivedAt: "2026-05-15T15:00:00Z",
    runId: "run-1",
    config: { ratePerSec: 10, keep: true },
  });
});

test("planComponentConfigUpdate rejects empty patches unless explicitly allowed", () => {
  assert.throws(
    () => planComponentConfigUpdate({ currentConfig: { enabled: true }, patch: {} }),
    /EMPTY_PATCH_REJECTED/
  );

  assert.deepEqual(
    planComponentConfigUpdate({ currentConfig: { enabled: true }, patch: {}, allowEmptyPatch: true }).mergedConfig,
    { enabled: true }
  );
});
