# Stakeholder writing and safe compression

This reference owns wording and presentation for both intake modes and for
rewriting an existing document set. The intake contract decides which narrative
fields may be edited; it retains ownership of facts, validation and approvals.
No other skill invocation, interview or approval round is needed.

## Make the plan understandable

Use **Caveman-lite**: professional prose, normal grammar, short sentences and no
filler. Explain technical terms when first needed. Use concrete subjects and
verbs. Remove repeated summaries and decorative tables. Keep an explanation
when the stakeholder needs it to make a decision; there is no word-count target.

Write the permitted narrative fields and comments in the mandatory YAML. Keep
exact technical fields beside their explanations. Use the existing consolidated
review to answer:

| Reader's question | Explain from the identified document set |
| --- | --- |
| Why are we testing? | Client objective, selected environment and business journeys; included, excluded and mocked scope. |
| What will the test do? | Proposed workload, phase order, durations and reasons. Distinguish request rate from concurrent users. |
| How will we judge it? | Exact acceptance conditions, units, measurement windows and supporting observations. Explain terms such as percentile. |
| What remains uncertain? | Material dependencies, risks, missing evidence and conclusions this environment cannot support. |
| What decision is needed? | Outstanding choices, recommendations and the stage that needs each answer. |

The review is a read-only view of the identified YAML revision. It may be shown
in chat or exported for sharing; it is not another editable requirements or plan
store. Edit source documents and regenerate the view. A short summary may link
to detail but must not imply that omitted conditions were waived.

Use `prepare-review` for the current decisions and source references, then request
only the field views needed to explain the plan. Identify the returned document
revision. Its diagnostic groups refer to the single result issue list; avoid
copying all validation messages into the client summary. For an explicit previous
document snapshot, `--previous DIR` identifies changed pointers without transferring
its approval or repeating replaced client values.

Keep packaged `ownership` and `mcpAutomation` policy in the mandatory YAML.
Explain business choices in the stakeholder review; tooling mechanics belong to
the NFT team's review. Do not ask the client to approve internal MCP rules or
split them into a second editable configuration document.

## Protect test meaning

Preserve all of the following through the writing pass:

- Client facts, engineering proposals, accepted choices, approvals, measured
  outcomes and unexecuted status. Keep unknowns and evidence limits visible.
- API/journey/SUT scope, real versus mocked dependencies, transaction definition,
  offered versus achieved rate, concurrency, units, percentiles, sample population
  and measurement windows.
- Exact thresholds and operators, phase order, durations, retries, timeouts,
  conditions, exclusions, stopping rules and safety responsibilities.
- Keys, enums, identifiers, expressions, source references, hashes and quoted
  client text. Explain a quotation alongside it instead of rewriting it.

Do not turn a recommendation into a requirement by removing its qualifier. Do not
replace precise conditions with vague phrases such as “fast enough” or “stable”.
Expand an unfamiliar term without renaming a canonical API, field or contract.

Compare protected structured values before and after editing, then review
narrative meaning. Use the shared finalisation command to recalculate derived
hashes and validate the edited set. Existing revision/confirmation rules still
apply. Use the contract's review-content digest to check whether the reviewed
material changed; never update an old confirmation to a new digest automatically.

Check that a stakeholder can identify objective, scope, load, acceptance, limits
and outstanding decisions without knowing PocketHive internals. This needs
human judgement; a parser cannot establish clarity or source faithfulness.
