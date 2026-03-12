import { describe, expect, it } from "vitest";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseSessionLog, summarizeSessionLog } from "./feedback-store.mjs";

describe("parseSessionLog", () => {
  it("skips malformed jsonl lines", () => {
    const dir = mkdtempSync(join(tmpdir(), "pockethive-feedback-"));
    const file = join(dir, "session-log.jsonl");
    writeFileSync(
      file,
      [
        JSON.stringify({ kind: "tool_event", sessionId: "s-1", toolName: "foo" }),
        "not-json",
        JSON.stringify({ kind: "feedback_event", sessionId: "s-1", relatedEventId: "evt-1" })
      ].join("\n"),
      "utf8"
    );

    const entries = parseSessionLog(file);
    expect(entries).toHaveLength(2);
    expect(entries[0].toolName).toBe("foo");
    expect(entries[1].relatedEventId).toBe("evt-1");
  });
});

describe("summarizeSessionLog", () => {
  it("aggregates tool and feedback events for one session", () => {
    const dir = mkdtempSync(join(tmpdir(), "pockethive-feedback-"));
    const file = join(dir, "session-log.jsonl");
    writeFileSync(
      file,
      [
        JSON.stringify({
          kind: "tool_event",
          sessionId: "s-1",
          toolName: "orchestrator.get-swarm",
          resultStatus: "rejected",
          validation: [{ code: "SWARM_NOT_FOUND" }]
        }),
        JSON.stringify({
          kind: "tool_event",
          sessionId: "s-1",
          toolName: "orchestrator.list-swarms",
          resultStatus: "ok",
          validation: []
        }),
        JSON.stringify({
          kind: "feedback_event",
          sessionId: "s-1",
          blockerType: "validation_unclear",
          suggestedImprovements: [{ type: "improve_error_message" }]
        }),
        JSON.stringify({
          kind: "tool_event",
          sessionId: "s-2",
          toolName: "control.start-recording",
          resultStatus: "failed",
          validation: [{ code: "RABBIT_UNREACHABLE" }]
        })
      ].join("\n"),
      "utf8"
    );

    const summary = summarizeSessionLog(file, "s-1");
    expect(summary.toolCalls.total).toBe(2);
    expect(summary.toolCalls.ok).toBe(1);
    expect(summary.toolCalls.rejected).toBe(1);
    expect(summary.toolCalls.failed).toBe(0);
    expect(summary.feedbackEvents).toBe(1);
    expect(summary.mostUsedTools[0]).toEqual({ toolName: "orchestrator.get-swarm", count: 1 });
    expect(summary.validationCodes[0]).toEqual({ code: "SWARM_NOT_FOUND", count: 1 });
    expect(summary.blockerTypes[0]).toEqual({ blockerType: "validation_unclear", count: 1 });
    expect(summary.suggestionTypes[0]).toEqual({ type: "improve_error_message", count: 1 });
  });
});
