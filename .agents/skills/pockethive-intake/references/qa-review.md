# QA review with the existing templates

Use this review while populating or checking a client intake. Keep the four YAML
templates and their schemas unchanged. Record facts in their existing owner,
link evidence and ask about material gaps. Do not add fields or invent a new
document format to accommodate an unsupported representation.

## Check the actual gate

`finalise` saves derived metadata and reports authoring review notices. Its
success does not mean the requirements are complete. Follow it with `validate
--stage draft`; use `validate --stage handoff` before describing an intake as
ready for authoring handoff. Report the actual errors, gaps and warnings. A
draft may save successfully while handoff remains incomplete.

Use the [contract's authoring notices](../contract/intake-contract.md#authoring-review-notices)
as review prompts, not automatic facts or reasons to invent answers. Group
related unresolved decisions into one question with several target pointers.
Read existing answers first. Never create a question for every diagnostic or
ask again merely because a nonblocking review notice remains visible.

Apply the [shared source-review steps](intake-workflow.md#ask-only-useful-questions)
before choosing questions. A valid source pointer and matching hash establish
where evidence came from, not whether it supports the authored claim. Read the
claim and source together, including scope and measurement boundaries. Report a
contradiction even when the CLI returns no errors; correct only what the evidence
supports and preserve any remaining decision. Inspection coverage cannot replace
this judgement.

## Request values and time-sensitive data

`payloadBindings` is the authority for authored request-field bindings.
`requestSample`, `headersSample` and `responseSample` are illustrations. Review
the intended request against the identified API contract and bundle evidence;
account for each required variable and fixed payload field. Never copy a sample
into runtime settings to fill missing bindings. A nonempty binding list alone
does not establish completeness, and the validator cannot prove business
completeness without the API contract.

Use only the existing `dataset`, `constant`, `generated` and `correlation` source
types. `variable` is not a supported type in this package. Preserve an unsupported
requirement as a declared representation gap; do not substitute another source
type. The sequence owns correlation extraction; the binding owns its destination.

Review dates, expiry windows and other perishable values using their stated
business meaning and the planned run window. An old date may be intentional
historical data or a negative test. The date notice identifies a calendar-shaped
constant; it cannot establish expiry. Other date formats and opaque expressions
need the same engineering review. Do not infer meaning from a name such as
`expiry_date`, use the current date as a client requirement, or silently turn a
constant into a generator.

Record an unresolved validity decision against the exact binding in
`traceability.instance.questions`. Retain the supplied rule and source evidence
through that question's answer and the existing provenance ledger. Where a
run-time freshness check is required, describe the check and expected outcome
in `plan.readiness.prechecks`. There is no `validUntil` binding field.

## Unknown, omitted and not applicable

Before presenting a draft, review the CLI's `brief.blankFields` inventory with
the source and recorded questions. `population-available` means the canonical
mapper can supply the field; use the population operation, not a second mapper.
`source-review-needed` does not mean the source lacks the answer. An explanation
or not-applicable record still needs source-faithfulness review. Optional fields
and unused template scaffolds do not become blockers because they appear here.
Ask only about applicable material decisions; preserve unknowns visibly.

`scope:`, `scope: null` and `scope: ~` all represent YAML null. Their spelling
does not encode different decisions. An unknown OAuth scope stays null with a
scoped question. A confirmed omitted scope retains the API owner's reason in
identified answer evidence; use the existing `not-applicable` provenance kind
only when that is the actual decision. A comment can explain it to the reader,
but a comment alone is not answer evidence.

Token reuse is recorded through `authorization.type`, `tokenSource` and the
declared reuse settings. Reuse alone does not establish which acquisition scope
the token needs. Do not invent `unknown` enums, empty-string conventions or
sentinel values to bypass a missing decision.

## Data use, replay and workload evidence

For each participating dataset binding, confirm its owner, required count,
reuse, per-record concurrency, exhaustion action and side effects. When reset
is required, confirm its instructions and responsible owner. Existing handoff
validation already checks these decisions for explicitly used datasets. It
does not default concurrency to one or infer a dataset from a sample payload.
Unbound data that the source says is needed remains an explicit binding gap.

For APIs identified by evidence as state-changing, get the API owner's
`idempotency.required` decision and replay rule. Leave an unknown decision null
and record a handoff-blocking question. The existing validator requires replay
decisions when retries are positive; it cannot identify every state-changing
API from an HTTP method or prose. Zero retries does not prove the API read-only.
One owner response may cover several operations when its scope is explicit.

`productionUsage` is optional context. Unknown availability is null; false means
confirmed unavailable. Ask about the basis for any missing applicable target
alongside available production evidence, without making production metrics a
universal requirement. Client goals or an explicitly adopted engineering
proposal may supply that basis. Never convert observed traffic to an accepted
TPS target automatically. Existing KPI checks remain the handoff gate.

## Dependencies and pre-run evidence

For a declared seeder or other bundle prerequisite, reference its existing
definition/revision through `plan.environmentQualification.dependencyRefs`.
Describe the required artifact, responsible owner and sequencing in the relevant
`dataPreparation[].preparation` and `readinessChecks`; put the bounded before-run
check and expected result in `plan.readiness.prechecks`. If the owner or required
output is unknown, ask before declaring the dependent work ready.

These fields record a prerequisite; they are not an executable dependency graph.
The intake CLI cannot verify another bundle ran or enforce its execution order.
Do not invent a nested dependency schema inside a flexible field or edit the
template's immutable `readiness.prerequisites` policy text.

A required WireMock smoke check uses `plan.readiness.prechecks` and
`results.preRunChecks[]` with the existing `check`, `status`, `observed` and
`evidenceRef` fields. Leave it `pending` until actual evidence is supplied.
Supporting evidence identifies when, by whom and against which revision the
check ran. Use `not-applicable` only with a supplied reason; there is no `skipped`
status. A passing pre-run check does not turn the load-test result into a pass.
Do not claim mock correctness or execute a smoke test as part of intake.

## Keep stakeholder review concise

Use `prepare-review --write-review` to produce the generated report. Put the
intended conclusion in the existing objective, and evidence limitations in
`plan.environmentQualification.limitations`. State what the test could establish,
what it cannot establish and the next decision needed. For a mock target, qualify
the mock journey/load setup; do not describe it as real-service capacity proof.
Engineering explanations must remain proposals or sourced observations as
appropriate. Regenerate the report after edits; its revision must match the forms.

Use the [stakeholder writing guide](stakeholder-writing.md) to present the client
objective, planned test, acceptance conditions and outstanding decisions.
Retain `ownership` and `mcpAutomation` in the YAML as packaged policy. The NFT
team owns that tooling detail; stakeholders are not asked to validate it.
The stakeholder summary is a read-only view of the same identified revision,
not a separate editable `generation-config.yaml` or requirements authority.

## Worked reviews: can the test answer the question?

Use the relevant example when an explicit objective or source raises the issue.
These are reasoning examples, not extra mandatory gates or preset test values.
Keep proposed values in their existing owning fields and their rationale,
premises and adoption status in `traceability.instance.proposals`. Record a
missing decision once in `traceability.instance.questions` with all affected
targets. The contract and schemas remain the authority for field shapes.

### Offered requests and completed transactions

**Evidence:** A bundle schedules 100 requests/second. The client asks whether
payment processing meets its business throughput target; that target is absent.

**Review:** Preserve the schedule as an observation. Ask for the definition of a
successful payment, target and window in one related batch. Keep offered-load
measurement in `plan.workloadDelivery`, business acceptance in the existing
criterion rules, and their measurement boundaries explicit. A retry is another
request attempt; whether it is another business transaction requires the supplied
definition. Do not copy the configured rate into `requirements.kpis[].targetTps`.

**Stakeholder wording:** “The bundle offers 100 requests each second. We still
need the required number of successfully completed payments and the period over
which it must be sustained.”

### Arrival model and generator capacity

**Evidence:** The client wants to understand a sustained arrival rate even when
the service slows down. The source does not establish scheduling or available
generator capacity.

**Review:** Propose confirming an arrival model that can represent that objective
through `plan.executionModel`; do not infer it from a worker name. Explain that
traffic waiting for previous responses may reduce offered load as responses
slow. Ask how achieved offered load and generator limits will be observed before
using a shortfall to infer SUT capacity. Place evidence limitations in
`plan.environmentQualification.limitations` and the proposed pre-run observations
in `plan.readiness.prechecks`. Do not silently substitute an unsupported model.

**Stakeholder wording:** “We need to distinguish the service's limit from a load
generator that cannot deliver the planned traffic.”

### Acknowledgement and asynchronous completion

**Evidence:** The API returns HTTP 202 before processing completes. The objective
is completed-work capacity, but only acknowledgement logs are available.

**Review:** Ask for the completion event or query, correlation rule, time boundary
and accountable owner as one completion-evidence decision. Keep the unresolved
decision against `plan.executionModel.completion` and the affected measurable
rules. A proposed completion check needs source support; do not invent a polling
endpoint. If the client explicitly wants acknowledgement latency only, respect
that scope and explain what it leaves unmeasured.

**Stakeholder wording:** “An acknowledgement shows that work was accepted. The
current logs cannot establish how much work completed or how long it took.”

### Data exhaustion and side effects

**Evidence:** A source declares single-use accounts and a state-changing journey.
The available record count or planned transaction count is missing.

**Review:** Use the existing data usage/reset owners described above. Ask about
the missing count and exhaustion action; keep any capacity calculation tied to
explicit inputs and units. Do not assume account reuse, reset authority or one
record per request. A retry or multi-step journey may have different consumption.

**Stakeholder wording:** “The test needs enough eligible accounts for the planned
journeys. Reusing an account may change the result, so the data rule remains open.”

### Recovery and mock-based conclusions

**Evidence:** The client asks about recovery after a burst, while a dependency is
mocked and the safe load ceiling has not been supplied.

**Review:** Propose observing recovery after load reduces, with an explicit
completion/queue criterion where the source supports one. Keep the missing safe
ceiling and recovery definition as decisions; do not select stress values or
start traffic. Record real/mocked scope in `plan.mockSetup.dependencies` and its
limits in `plan.environmentQualification.limitations`. A mock's response profile
does not establish the capacity of the real dependency.

**Stakeholder wording:** “This setup can examine the service's behaviour with the
declared mock responses. It cannot establish the real dependency's capacity.”

Use the [conversation evaluations](evaluation.md) when qualifying these
judgements across clients; do not run that evaluation interview on a live client
as an additional intake step.
