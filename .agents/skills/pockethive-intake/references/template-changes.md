# Reviewed template changes

The five supplied originals remain byte-identical in `assets/source/`, verified
by `SHA256SUMS`. The requirements sample's filename drops ` (1)` both in the
package and in the supplied Downloads location; it remains a fictional example.
The four working templates in `assets/templates/` retain the supplied document
family. They are the templates to populate. Schemas define exact field shapes;
the intake contract defines the checks. This page explains the changes.

| Working document | Source → working version | Main corrections |
| --- | --- | --- |
| Requirements | 1 → 2 | Replace illustrative client values with nulls, preserve source ownership, make unknown production usage explicit, generate open questions from traceability. |
| Test plan | 3 → 5 | Explicit open/closed workload models, journey occurrences and correlation, data lifetime, async completion, measurement definitions, workload delivery, environment/dependency limits, stop/drain obligations and conditional comparison method. |
| Traceability | 1 → 2 | Keep the supplied guide and add actual instance links: source evidence, proposals, questions, scoped KPI/rule/results coverage and review of exact content. |
| Execution results | 1 → 2 | Start unexecuted, bind actual document/run snapshots, record delivered workload, dependency observations, stop/drain outcomes and comparison evidence. |

References now use the manifest's real filenames and independent versions.
The unsupplied test-plan sample stays unavailable; there is no substitute sample.
All working templates use LF line endings; the source snapshots retain CRLF.

## QA improvements in use

Define what counts as a business transaction and which observations enter each
measurement. Record sources, windows, minimum samples, percentiles, timeout/retry
treatment and aggregation when applicable. Requested traffic is distinct from
successful transactions and actual delivered traffic. No default tolerance is
invented.

Use occurrence IDs for repeated journey steps; retain data and correlation state
for the declared lifetime. A closed workload needs users, pacing and a stop
condition. An asynchronous response needs a bounded definition of business
completion. Preserve these requirements even if current runtime authoring cannot
represent them.

Record environment and mock limitations where they affect the conclusion.
Stopping means observing producer shutdown and outstanding work under the agreed
drain rule. Baseline or repeatability claims need an explicit method, comparable
evidence and separate identified runs; `comparison.decisionRule` can reference
that method. Do not invent a baseline or require repeated runs for every intake.

These additions improve the intake contract. They do not add a new PocketHive
scenario schema, endpoint resolver, runtime scheduler or results calculator.

## Plan 4 to plan 5

Update an existing plan explicitly before using the current package. For each
sequence correlation, assign its step-local `correlationId` and retain the exact
source step, response JSON Pointer, destination step and requiredness. Put the
destination location/path in its requirements API payload binding, with
`source: {type: correlation, correlationRef: <correlationId>}`. Remove the duplicate
location/path from the plan correlation. Review every repeated API occurrence;
no nearest-step selection or automatic migration occurs. Set the plan's version
to 5, finalise its derived references and validate the updated document set.

The working requirements template now omits `openQuestions`. Keep questions in
traceability and let finalisation create that projection. Directly authored
projection content is rejected with a named document and pointer.
