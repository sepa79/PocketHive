import { describe, expect, it } from "vitest";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  childProcessExited,
  childProcessIsRunning,
  parseRecordedEntries,
  topicMatchesPattern
} from "./server-utils.mjs";

describe("topicMatchesPattern", () => {
  it("matches star and hash wildcards", () => {
    expect(topicMatchesPattern("event.#", "event.ready.swarm.foo")).toBe(true);
    expect(topicMatchesPattern("event.*.swarm.foo", "event.ready.swarm.foo")).toBe(true);
    expect(topicMatchesPattern("event.*.swarm.foo", "event.ready.swarm.bar")).toBe(false);
  });
});

describe("parseRecordedEntries", () => {
  it("filters malformed lines and routing keys", () => {
    const dir = mkdtempSync(join(tmpdir(), "pockethive-mcp-"));
    const file = join(dir, "recording.jsonl");
    writeFileSync(
      file,
      [
        JSON.stringify({ routingKey: "event.ready.swarm.foo", body: "ok" }),
        "not-json",
        JSON.stringify({ routingKey: "signal.status-request.foo.ALL.ALL", body: "ping" })
      ].join("\n"),
      "utf8"
    );

    const entries = parseRecordedEntries(file, "event.#");
    expect(entries).toHaveLength(1);
    expect(entries[0].routingKey).toBe("event.ready.swarm.foo");
  });
});

describe("childProcess state helpers", () => {
  it("treats exit code 0 as exited, not running", () => {
    const child = {
      exitCode: 0,
      killed: false
    };

    expect(childProcessExited(child)).toBe(true);
    expect(childProcessIsRunning(child)).toBe(false);
  });

  it("treats a live child with null exitCode as running", () => {
    const child = {
      exitCode: null,
      killed: false
    };

    expect(childProcessExited(child)).toBe(false);
    expect(childProcessIsRunning(child)).toBe(true);
  });
});
