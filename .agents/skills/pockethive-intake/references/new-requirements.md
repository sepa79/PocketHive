# Mode: new requirements intake

Use `new-requirements` for a client narrative, supplied requirements documents or
direct answers. Apply the shared workflow and writing guidance loaded by
`SKILL.md`; this mode adds extraction instructions only.

1. Read the supplied sources and initialise the four document drafts in a
   new/empty output directory, or resume the existing document set. An existing
   narrative file can be supplied through `--source`. Identify
   each source without inventing an author, approval or business context. Keep
   results unexecuted unless the user supplies identified execution evidence.
2. Extract explicit statements and record their source locations. Separate facts,
   contradictions, examples and missing information. Save the partial documents
   before asking questions.
3. Draft a reasoned engineering approach to the stated objective. Mark choices
   as proposals until adopted; do not treat absent targets as permission to copy
   the sample. Explain material capability or measurement limitations early.
4. Use the existing QA topic areas to check coverage: purpose/scope; SUT and
   journeys; data/authentication; workload; acceptance and measurement; safety,
   ownership and cleanup. Ask only about relevant unresolved decisions using the
   shared interview rules. Do not start a parallel MCP workflow interview.
5. Apply stakeholder writing and run `prepare-review` at the appropriate stage. Return
   the document set and a concise review distinguishing accepted facts, proposals
   and remaining decisions.

Keep optional context and later execution details at their proper stage. An
absent production graph does not prevent a client from specifying a workload.
A closed-loop workload, non-HTTP protocol or comparison claim must be represented
faithfully by the contract and supported owner capabilities before handoff;
otherwise preserve it as an explicit gap. Do not weaken the requested test.

Collection and a readable plan do not establish plan approval, MCP confirmation
or permission to generate, deploy or run a scenario. Preserve any valid approval
already supplied within its exact scope and revision.
