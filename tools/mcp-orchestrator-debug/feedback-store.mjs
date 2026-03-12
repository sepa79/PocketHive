import { appendFileSync, existsSync, readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";

export function createSessionId() {
  return randomUUID();
}

export function createEventId() {
  return `evt-${randomUUID()}`;
}

export function appendSessionLog(logPath, entry) {
  appendFileSync(logPath, `${JSON.stringify(entry)}\n`, "utf8");
}

export function parseSessionLog(logPath) {
  if (!existsSync(logPath)) {
    return [];
  }

  return readFileSync(logPath, "utf8")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .flatMap((line) => {
      try {
        return [JSON.parse(line)];
      } catch {
        return [];
      }
    });
}

export function summarizeSessionLog(logPath, sessionId) {
  const entries = parseSessionLog(logPath).filter((entry) => entry.sessionId === sessionId);
  const toolEvents = entries.filter((entry) => entry.kind === "tool_event");
  const feedbackEvents = entries.filter((entry) => entry.kind === "feedback_event");

  return {
    sessionId,
    toolCalls: {
      total: toolEvents.length,
      ok: toolEvents.filter((entry) => entry.resultStatus === "ok").length,
      rejected: toolEvents.filter((entry) => entry.resultStatus === "rejected").length,
      failed: toolEvents.filter((entry) => entry.resultStatus === "failed").length
    },
    feedbackEvents: feedbackEvents.length,
    mostUsedTools: topCounts(toolEvents.map((entry) => entry.toolName), "toolName"),
    mostRejectedTools: topCounts(
      toolEvents
        .filter((entry) => entry.resultStatus === "rejected")
        .map((entry) => entry.toolName),
      "toolName"
    ),
    validationCodes: topCounts(
      toolEvents.flatMap((entry) => (entry.validation ?? []).map((item) => item.code)).filter(Boolean),
      "code"
    ),
    blockerTypes: topCounts(
      feedbackEvents.map((entry) => entry.blockerType).filter(Boolean),
      "blockerType"
    ),
    suggestionTypes: topCounts(
      feedbackEvents
        .flatMap((entry) => entry.suggestedImprovements ?? [])
        .map((item) => item.type)
        .filter(Boolean),
      "type"
    )
  };
}

function topCounts(values, fieldName) {
  const counts = new Map();
  for (const value of values) {
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }

  return [...counts.entries()]
    .sort((left, right) => {
      if (right[1] !== left[1]) {
        return right[1] - left[1];
      }
      return left[0].localeCompare(right[0]);
    })
    .map(([value, count]) => ({
      [fieldName]: value,
      count
    }));
}
