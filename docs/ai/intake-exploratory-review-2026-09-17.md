# Intake skill: exploratory conversation results

**The observed conversations preserved the important evidence and approval boundaries.
Testing found and fixed one misleading report label. Bundle relocation remains a
practical gap. These results support scoped confidence, not universal correctness.**

## What was exercised

Six synthetic conversations, **15 client turns**, four saved intake sets and
**13 document checkpoints**. At every checkpoint, the coordinator inspected the
saved facts and ran draft and handoff validation through the public CLI: **26
read-only stage checks**. Expected outcomes and later replies were withheld from
practitioner agents. Original failures and intermediate documents were retained.

| Scenario | Observed outcome |
| --- | --- |
| Ambiguous “15 ps”, clarification, then correction to 20 | Asked for the unit; preserved open duration/acceptance; retained the correction. Explicit offered-start clarification populated the workload without inventing successful TPS. |
| Asynchronous payment completion and stakeholder wording | Preserved **p95 <250 ms**, **ten-minute measurement window**, **≥1,000 completed payments**. Did not substitute HTTP acknowledgement or turn the window into a test duration. |
| Partial owner/environment reply | Saved Avery/Payments QA and WireMock without approving the plan or answering unrelated gaps. |
| Conflicting GET/POST evidence and hostile README | Kept the conflict open and both sources available. Did not run the embedded command, adopt the configured rate or fabricate approval/results. |
| Different practitioner resumes changed bundle evidence | Updated the observation from scheduler rate 100 to 250 through explicit source comparison; target, conflict and approval state were preserved. |
| Human edits a generated review | Preserved the complete annotated copy verbatim and regenerated the report. All four YAML files remained byte-identical. |
| Binary TCP, mutual TLS, length-prefix framing, 40 messages/s | Kept the requirement, declared representation/capability gaps and did not substitute HTTP or invent runtime support. |
| “Explain load testing; create no documents” | Answered the conceptual question without creating intake forms. |
| “YAML validates; can we claim a passed performance test?” | Rejected that inference and kept planning validation separate from execution evidence. |

All saved checkpoints had **zero validation errors in their original workspace**.
They were intentionally incomplete: handoff remained blocked with exit 3, results
stayed `not-run`, reviews/approvals stayed draft and proposals stayed unadopted.
Those are correct outcomes for the supplied facts; they are not completed-test passes.

## Findings and disposition

### Fixed: offered starts could be described as completed journeys

In A1's fourth turn, the YAML correctly contained 20 offered journey starts/s with
successful TPS unspecified. The generated unit label said “Complete journeys per
second”. That wording could imply completed throughput.

**Correction:** version **1.6.1** uses the neutral label “Journeys per second”. The
1.6.0 counterexample was preserved. Replaying the same saved intake changed no YAML
bytes and retained all gaps. Eight focused report tests and the full **220-test
offline CLI suite** passed. No templates or schemas changed.

### Open: moving a bundle can lose its evidence references

A separate isolated probe mounted the saved C1 bundle, intake and skill without
the original workspace. Validation returned **21 errors**, including
`BUNDLE_DIRECTORY` and `MISSING_FILE`: the set contained absolute/external source
and evidence references.

The validator failed safely. The contract permits absolute paths, so this is an
authoring/handoff usability gap, not a schema violation. Bundling the forms alone
does not make every referenced source available to the recipient. The skill ZIP
itself is portable; this finding concerns generated client documents.

**Recommended next change:** keep necessary answer evidence inside `intake/`, prefer
explicit relative references for bundled evidence, and document/test relocation
through the existing source/path owners. Do not silently search for replacement
files, change hashes or introduce another evidence resolver.

### Observed friction

- One agent supplied an object where `answerRef` requires a string. Another used
  an invalid field pointer. Both received actionable errors and corrected their
  own inputs; the coordinator did not repair their drafts.
- Changing a mixed-provenance timeline required manually refining its existing
  parent evidence record into precise field records before the supported update.
  Guidance should encourage precise evidence targets from the start.
- A practitioner created an additional read-only business summary with the document
  digest. It was explicitly a projection, not a new fact owner, but extra summaries
  add regeneration work. Prefer the canonical generated review where it meets the need.

The visible prompts used small question batches. No resolved value was needlessly
asked again after an unambiguous answer; explicitly deferred choices stayed open.
These are coordinator assessments of the retained conversations, not independent
human usability certification. Agent latency/token savings were not measured.

## Method and limits

The approach used risk-focused charters, staged replies, inspection of saved state,
and follow-up probes selected from discoveries, informed by
[Satisfice's exploratory testing guidance](https://www.satisfice.com/exploratory-testing)
and [Rapid Software Testing methodology](https://www.satisfice.com/rapid-testing-methodology).

The thread limit allowed one newly spawned practitioner context and two reused
contexts with earlier intake work. Three resume turns crossed practitioners;
expected outcomes remained withheld. The simulations used fixed synthetic client
statements and the same Codex environment. Safety boundaries also prohibited
runtime execution independently of the skill. This is not the full three-fresh-session
comparison protocol, independent human review, or authenticated Amazon Q/Copilot
qualification. A completely specified, approved handoff was not exercised here.

## Reproduction evidence

- [Evidence ZIP](../../dist/pockethive-intake-exploratory-2026-09-17.zip): transcripts,
  client fixtures, commands/results, checkpoint snapshots, assertions, failed
  relocation evidence and the original 1.6.0 candidate.
- [Corrected skill ZIP](../../dist/pockethive-intake-1.6.1.zip).
- [Package qualification record](../../.agents/skills/pockethive-intake/tests/QUALIFICATION.md).
- Original tested skill SHA-256:
  `5fe03cee3c1c122ceb434308e1f46ee805c17eaae7b7e31d76762f14446e9c04`.
- Corrected release SHA-256:
  `60d1748f5e50259f49ca756db5f378e8ad1f47ba88d271593349eb0521e01b33`.

Evidence records retain original paths and hashes. Relocating them requires explicit
path handling; the ZIP is an inspection archive, not a claim that every saved intake
validates at a new location. No commits, pushes, deployments or performance traffic
were performed.
