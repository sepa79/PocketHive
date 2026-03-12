# MCP Improvement Feedback Loop

> Status: concept
> Scope: concept only, not a product contract
> Audience: PocketHive team, MCP/tooling design discussions

## 1. Problem

When an AI client fails to use an MCP server effectively, normal logs tell us:

- which tool was called,
- whether it failed,
- and sometimes the raw error.

That is not enough to improve the server.

It does not tell us:

- what the AI was trying to achieve,
- how it interpreted the failure,
- what next action it thought was correct,
- whether the real problem was docs, examples, validation, naming, or tool shape.

As a result, MCP ergonomics are hard to improve systematically.

## 2. Core idea

Treat MCP interaction as a two-part signal:

1. the server reports the outcome of the tool call,
2. the AI reports how it understood that outcome.

This creates a structured feedback loop for continuous improvement of the MCP server itself.

The target is not governance, scoring, or forcing architecture decisions.
The target is simple:

- understand why the AI could not use MCP the way we expected,
- identify repeated friction patterns,
- improve the server, validation, examples, and docs over time.

## 3. Concept

For meaningful failures, capture both:

- the tool event
- the AI feedback event

### 3.1 Tool event

The MCP side records:

- `toolName`
- `resultStatus`
  - `ok | rejected | failed`
- `summary`
- `validation[]`
- `nextHint`
- `timestamp`
- `eventId`
- `sessionId`

This is the server-side view of what happened.

### 3.2 AI feedback event

The AI side records:

- `relatedEventId`
- `intent`
- `outcomeUnderstanding`
- `blockerType`
- `proposedNextAction`
- `suggestedImprovements[]`

This is the client-side view of why progress stalled and what improvement might help.

## 4. Why this matters

This loop gives a better signal than plain telemetry.

Without it, we learn:

- `tool X failed 27 times`

With it, we can learn:

- `tool X failed because the contract was repeatedly misunderstood`
- `validation message Y caused the AI to choose the wrong next step`
- `the tool exists, but examples are missing`
- `the tool is too low-level for the domain task the AI is trying to complete`

That is the difference between runtime logging and actionable MCP product feedback.

## 5. Where it helps

This concept can improve:

- tool naming
- response shape
- validation clarity
- next-step hints
- docs
- examples
- tool boundaries

It is especially useful when the same failure repeats across sessions but for different prompts or agents.

## 6. What PocketHive should aggregate

Useful aggregate signals include:

- most-called tools
- most-rejected tools
- most frequent `validation.code`
- most frequent `blockerType`
- most frequent suggestion type
- sessions that stalled without reaching the intended milestone
- sessions that completed cleanly

These metrics do not decide product changes by themselves.
They help the PocketHive team decide where to inspect first.

## 7. Practical heuristics

Usually improve docs or examples when:

- the tool already exists,
- failures cluster around `docs_gap`, `example_gap`, or `misunderstood_contract`.

Usually improve validation when:

- failures cluster around `validation_unclear`,
- the AI repeatedly chooses the wrong next step after an error.

Usually reshape the MCP surface when:

- the same `missing_tool` or `tool_too_low_level` pattern repeats,
- one obvious domain task requires a long, repetitive sequence of calls.

## 8. Important boundaries

This concept is intentionally narrow.

It is not:

- automatic architecture governance
- automatic product decision-making
- a requirement that the AI must always be right
- a finalized MCP contract

It is a structured learning loop for improving MCP usability.

The PocketHive team remains the decision-maker for:

- contract changes
- new tools
- validation behavior
- docs and examples

## 9. Minimal experiment shape

The smallest useful experiment is:

1. capture tool events locally,
2. capture AI feedback for meaningful failures,
3. persist both to local JSONL,
4. review a small number of real sessions,
5. make 2-3 concrete MCP improvements from observed patterns.

That is enough to validate whether the loop produces better signals than plain logs.

## 10. Success criteria

This concept is successful if it helps answer questions like:

- Why did the AI fail here?
- Was the failure caused by contract shape, poor validation, missing examples, or missing tools?
- Did the MCP response help the AI choose the right next step?
- Which improvements would likely reduce repeated failure in future sessions?

If the loop produces those answers consistently, it is worth formalizing further.

## 11. PocketHive angle

PocketHive is a good place to test this concept because its MCP use cases are concrete:

- runtime inspection
- scenario authoring
- topology and validation workflows
- orchestration/debug flows

That gives us real domain tasks, real failures, and a realistic path from feedback signal to MCP improvement.

## 12. One-line summary

The concept is simple:

MCP should log not only that a tool failed, but also why the AI could not recover from that failure in the way the server designers expected.
