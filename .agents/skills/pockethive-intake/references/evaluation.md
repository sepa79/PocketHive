# Evaluate intake conversations

Use this guide when qualifying or improving the skill, not during every client
intake. The [case cards](../tests/evaluations/cases.md) contain synthetic interview
prompts and assessor expectations. They exercise the same mandatory YAML family;
they do not define another client-data format or authorise a real test run.

## Run a comparable trial

1. Select the client surface, the exact package revision and one case. Use an
   empty, disposable authoring workspace with synthetic inputs only. Verify the
   package through its public CLI. Keep all packaged assets read-only.
2. Give the agent the selected case's **initial request** and referenced sources,
   replacing the explicit path placeholders. Keep assessor notes and later
   replies out of the agent's initial context. An evaluator supplies the replies
   at the specified points; do not use an agent to invent the client's answers.
3. Retain observable messages, tool results and saved document revisions. Do not
   request hidden reasoning. Time the path to the first useful saved draft and
   count questions, repeated questions and manual repairs.
4. At each checkpoint, snapshot the documents as delivered and validate them
   through the public CLI at the appropriate stage. Retain the agent's actual
   finalisation/validation output too. Do not silently repair its files before
   assessment; any evaluator-assisted repair uses a copy and is counted. A
   finalise success is not a handoff pass. Read the source and output together
   to assess whether each authored fact is supported.
5. Have a performance engineer assess the case-specific expectations and the
   rubric below. A second model may flag potential defects; it cannot establish
   source faithfulness, client acceptance or qualification on its own.

Run each case three times in fresh sessions for an initial comparison; report
all attempts, including failures. This is a practical starting sample, not a
statistical reliability claim. Compare the candidate with the previous released
skill under the same client, settings, prompts and source bytes. Apply each case's
specified one-fact variation as a separate trial. Do not optimise prompts against
the held-out variation before measuring it.

## Assess quality and friction separately

| Dimension | Evidence to retain | Acceptance for this case set |
| --- | --- | --- |
| Source faithfulness | Source-to-field comparison and exact unsupported claims, if any | No invented requirements, defaults, approvals or measured outcomes. |
| Decision completeness | Expected material gaps and their saved question targets | Relevant unanswered decisions remain visible at the correct stage. |
| Question efficiency | Actual question batches and questions repeated after an unambiguous answer | No needless repetition; partial answers leave only unresolved parts open. |
| Source-review efficiency | Relevant source reads before questions, extraction limits and unresolved conflicts | Do not ask for facts already supplied or present extraction omissions as missing client decisions. |
| Engineering judgement | Proposed test, rationale, unresolved premises and stated limits | Test choices address the explicit objective; proposals stay proposals. |
| Stakeholder clarity | Reviewer can explain objective, scope, load, acceptance, limits and next decision | Exact units, conditions and uncertainty survive simplification. |
| Editing reliability | Source/document snapshots, command output and repairs | No silent overwrites, source execution or loss of client work. |
| Practical effort | Elapsed time, tool calls, manual repairs and tokens where exposed | Compare observed results with the baseline; do not guess missing telemetry. |

Record each dimension as pass, fail or not assessed with a short evidence link
in the trial report. Do not hide a faithfulness failure inside an average score.
An unavailable prerequisite is recorded as blocked with the actual reason;
it is not a passing or failing model response. Keep evidence about package
mechanics separate from conversation and engineering judgement.

Cases E08 and E09 distinguish source inspection from source faithfulness. Assess
whether the agent reads omitted evidence and challenges an unsupported claim even
when document mechanics pass. These judgements require source-to-output review;
inspection counts and a passing CLI command cannot establish them. For resumed
reviews, assess change summaries against existing ledger IDs, including reordered
questions, without treating removal as resolution.

## Qualify the claimed client surface

Use the [client loading guidance](clients.md), preserving one canonical skill
and its thin integration pointer. For each claimed surface, record the observed
client/version, model when exposed, OS, package checksum, loading mechanism and
tool limitations. Do not infer hidden version or model details.

Check separately that the client can discover the skill, read referenced assets,
save all four documents, run the CLI, ask questions and resume retained work.
Include an explicit skill invocation and a natural intake request. Also try:

- “Shorten this existing intake's wording without changing its meaning.” This
  should retain its mode and answers rather than initialise a second intake.
- “Explain the purpose of a load test; do not create documents.” This should
  answer the question without starting an intake workflow.
- “Run this scenario.” This should not be presented as completed by intake or
  create a substitute set of requirements documents.

Exercise Codex CLI/app/IDE, Amazon Q IDE/CLI and the requested Copilot surfaces
individually. A review-only or file-context-only surface may have narrower
observed capabilities; state them. Authentication-blocked trials and missing
CLI execution remain explicit limitations. Do not install tools, switch products
or substitute another model as an implicit qualification shortcut.

## Offline mechanical checks

The case fixtures also have public-CLI regression checks. From the unpacked
skill root, after a maintainer has sealed its manifest:

```sh
python3 -B -I -S -m unittest discover -s tests -p 'test_behavioural_safeguards.py' -v
```

These checks exercise facts versus configuration, partial-answer preservation,
source-instruction isolation and review preservation during wording changes.
They do not simulate a client model or qualify its interview behaviour. The
complete existing suite remains the CLI release gate described in
[local qualification](../tests/QUALIFICATION.md).

## Trial report

Keep a concise Markdown report beside the trial's output, outside the package:
identify case/variation, package and source revisions, client settings, observed
limitations, transcript/output links, validation result, rubric outcomes and
friction measurements. This is qualification evidence, not another editable
requirements document. Do not copy real client payloads or credentials into
shared evaluation evidence.

For future changes, add a focused case from the actual failure before expanding
the workflow. Prefer better examples and deterministic authoring support over
longer instructions or a mandatory second interview.

Use E10 for the observed name/scope/rate/resume failure. Record each native client trial separately; unavailable authentication remains unqualified, not a simulated pass.
