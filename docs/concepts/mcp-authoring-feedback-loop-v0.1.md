> Status: in progress

# MCP Authoring Feedback Loop v0.1

## Goal

Capture structured feedback from an AI client during local MCP-driven scenario authoring so the PocketHive team can improve:

- MCP tools
- validation messages
- examples and docs
- workflow shape

This is product telemetry, not automatic architecture governance.

## Principles

- One session runs against one stable MCP capability version.
- MCP should fail explicitly, not guess.
- Feedback is required after meaningful failure states.
- AI may suggest improvements, but decisions stay with the PocketHive team.
- Session feedback and product improvement suggestions are separate concerns.
- The authoring target remains the canonical PocketHive scenario bundle contract.
- MCP session state is only a working copy of that canonical bundle, not a separate SSOT.

## Two Feedback Loops

### 1. Session loop

Used inside a single authoring session:

1. AI calls an MCP tool.
2. MCP returns `ok`, `rejected`, or `failed`.
3. MCP includes validation details and an optional next-step hint.
4. If feedback is required, AI must submit structured feedback before continuing.
5. AI retries with a better next action.

### 2. Product loop

Used across many sessions:

1. PocketHive team reviews aggregated tool events and AI feedback.
2. Team decides whether to improve docs, validation, examples, or MCP tools.
3. New sessions run against the newer MCP capability version.

## Minimal Runtime Model

### Authoring session

- `sessionId`
- `capabilitiesVersion`
- `workspacePath`
- `workingBundlePath`
- `status`: `active | needs_feedback | valid | exported | discarded | failed`
- `currentGoal`

`workingBundlePath` refers to an in-session working copy of a canonical
PocketHive scenario bundle that must remain compatible with:
- `docs/scenarios/README.md`
- `docs/scenarios/SCENARIO_CONTRACT.md`

### Tool event

- `eventId`
- `sessionId`
- `toolName`
- `resultStatus`: `ok | rejected | failed`
- `summary`
- `validation[]`
- `nextHint`
- `feedbackRequired`
- `timestamp`

### AI feedback event

- `sessionId`
- `relatedEventId`
- `intent`
- `outcomeUnderstanding`
- `blockerType`
- `proposedNextAction`
- `suggestedImprovements[]`

## When Feedback Is Required

Require `submit_feedback` when:

- a tool returns `rejected`
- validation returns at least one `error`
- MCP reports unsupported workflow or capability gap

Optional feedback is still useful after successful milestones, but it should not block progress.

## Minimal MCP Response Shape

```json
{
  "status": "rejected",
  "stateVersion": 7,
  "summary": "Cannot create topology edge because target bee has no declared input port 'in'.",
  "validation": [
    {
      "code": "TOPOLOGY_PORT_MISSING",
      "severity": "error",
      "path": "template.bees[2].ports",
      "message": "Bee 'processor-a' does not declare input port 'in'."
    }
  ],
  "nextHint": {
    "suggestedTool": "set_bee_ports",
    "reason": "Declare the missing logical port before creating the topology edge."
  },
  "feedbackRequired": true
}
```

## Minimal AI Feedback Shape

```json
{
  "relatedEventId": "evt-17",
  "intent": "Create a logical topology edge from generator-a.out to processor-a.in.",
  "outcomeUnderstanding": "Processor is missing a declared input port, so the topology edge cannot be created yet.",
  "blockerType": "missing_domain_step",
  "proposedNextAction": "Add input port 'in' to processor-a with set_bee_ports and retry connect_bees.",
  "suggestedImprovements": [
    {
      "type": "improve_docs",
      "target": "connect_bees",
      "reason": "It was not obvious that topology edges must match the canonical ports/work contract.",
      "confidence": "medium"
    }
  ]
}
```

## Allowed `blockerType` Values

- `misunderstood_contract`
- `missing_domain_step`
- `missing_tool`
- `tool_too_low_level`
- `tool_too_high_level`
- `validation_unclear`
- `docs_gap`
- `example_gap`
- `unexpected_side_effect`

## Allowed Improvement Suggestion Types

- `improve_docs`
- `add_example`
- `improve_error_message`
- `narrow_contract`
- `add_parameter`
- `add_tool`
- `split_tool`
- `merge_tools`
- `rename_tool`

These are suggestions only. They must be reviewed by the PocketHive team.

## What MCP Should Aggregate

- tool calls per session
- rejected calls per session
- most frequent `validation.code`
- most frequent `blockerType`
- most frequent `suggestedImprovements[].type`
- sessions completed without export
- sessions reaching valid bundle state

## Decision Heuristics For The Team

Usually improve docs/examples when:

- the tool already exists
- failures cluster around `docs_gap`, `example_gap`, or `misunderstood_contract`

Usually improve validation when:

- failures cluster around `validation_unclear`
- AI repeatedly chooses the wrong next step after an error

Usually add or reshape MCP tools when:

- the same `missing_tool` or `tool_too_low_level` pattern repeats across sessions
- AI needs long, repetitive tool sequences for one obvious domain operation

## Minimal Implementation Plan

1. Add `submit_feedback` to the existing PocketHive MCP.
2. Persist tool events and AI feedback to a local `session-log.jsonl`.
3. Mark sessions as `needs_feedback` after rejected/error states.
4. Add a simple `session-summary.json` with aggregated counters.
5. Review real sessions before adding new MCP endpoints.

## Contract Guardrail

This feedback loop must evaluate MCP usability against the existing
PocketHive scenario contract.

It must not normalize failures by introducing MCP-only authoring rules or a
parallel scenario representation.

## Scope Note

This document is intentionally small and local-first. It is meant to validate the feedback loop in direct MCP usage before designing any offline or ZIP-based flow.
