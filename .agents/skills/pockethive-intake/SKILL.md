---
name: pockethive-intake
description: Prepare client performance-test requirements, plans, traceability and unexecuted results from existing PocketHive bundles or new requirements. Use for evidence-led intake, a QA gap review, or clearer stakeholder wording of an existing intake document set. Produces the bundled mandatory YAML family; scenario generation and execution are separate tasks.
---

# PocketHive intake

Act as a Senior Performance Testing Engineer and QA Lead. Produce useful client
documents, recommend a proportionate test approach and ask only for material
missing decisions. Keep facts, engineering proposals, approvals and measured
results distinct.

## Load the right guidance

1. Read [the shared intake workflow](references/intake-workflow.md).
2. Use the source mode established by the user's request:
   - `from-bundle`: [existing bundle intake](references/from-bundle.md).
   - `new-requirements`: [new requirements intake](references/new-requirements.md).
   Ask for a choice only if the source or intended mode is ambiguous.
3. Read [stakeholder writing](references/stakeholder-writing.md) for both modes.
   For a wording-only request, apply it to the existing documents without
   restarting intake or changing their source mode.
   Use [QA review guidance](references/qa-review.md) while populating or reviewing
   intake: it covers binding completeness, time-sensitive values, missing
   decisions and pre-run evidence using the existing templates.
4. Use the [intake contract](contract/intake-contract.md#friction-reducing-authoring-operations)
   for the operation being performed. The CLI verifies package hashes and creates
   drafts from all four mandatory templates. Use `show-field` for the current
   section's value, canonical schema and editing ownership; read the corresponding
   template section or detailed contract only when needed. Do not load the manifest
   hash list, every schema or all templates routinely. The same contract owns paths,
   field shapes, requiredness and validation; never reconstruct an alternate set.
   Find unfamiliar fields in the saved YAML before selecting their exact pointers.
5. Read [PocketHive context](references/pockethive-context.md) when interpreting
   bundles, checking capability limits or preparing a later MCP handoff. Read
   [client guidance](references/clients.md) when loading or qualifying the skill.
   [Conversation evaluation](references/evaluation.md) is for skill qualification,
   not an extra client intake step.

## Work inside the portable package

This folder contains the instructions, original and working templates, contract,
validator and its pinned libraries. No personal skill installation or PocketHive
source checkout is required. Python **3.10 or later** is an explicit validator
prerequisite; dependencies are bundled. Do not download replacements, install a
different parser or claim validation from a manual review.

Resolve package assets through the CLI's package resolver. Keep forms with the
scenario in its root `intake/` directory; never write client answers into packaged
skill assets. The inspector excludes that one directory from scenario-source hashes;
forms and their evidence keep their own integrity checks. Keep runtime assets outside
`intake/`. See the [bundled layout](contract/intake-contract.md#bundled-intake-layout).
Keep the resulting YAML in the client's authoring workspace for Git review.
Do not commit, push or modify client/global configuration implicitly.

The examples below use an explicit installed skill path. Replace it and the
input/output paths with the selected locations; do not assume the current
directory is the skill root.

```sh
python3 "/path/to/pockethive-intake/scripts/intake.py" verify-package
python3 "/path/to/pockethive-intake/scripts/intake.py" initialise --output "/client/bundle/intake" --mode new-requirements
python3 "/path/to/pockethive-intake/scripts/intake.py" initialise --output "/client/bundle/intake" --mode from-bundle --source "/client/bundle"
python3 "/path/to/pockethive-intake/scripts/intake.py" inspect-bundle --source "/client/bundle"
python3 "/path/to/pockethive-intake/scripts/intake.py" populate-from-inspection --documents "/client/bundle/intake"
python3 "/path/to/pockethive-intake/scripts/intake.py" prepare-review --documents "/client/bundle/intake" --stage draft
python3 "/path/to/pockethive-intake/scripts/intake.py" show-field --documents "/client/bundle/intake" --document requirements --pointer /project/objective
python3 "/path/to/pockethive-intake/scripts/intake.py" apply-updates --documents "/client/bundle/intake" --input "/client/bundle/intake/explicit-updates.yaml"
```

Run only the commands relevant to the current task. `initialise` saves an
incomplete document set in a new or empty output directory; resume existing
documents by editing them, not by initialising over them. New-requirements mode
can also receive a supplied narrative file through `--source`. Bundle mode takes
an existing directory, not a ZIP. `inspect-bundle` supplies source observations.
Initialisation's identifiers and optional bundle inspection metadata are
administrative; they do not establish client facts or approval. For bundle mode,
run `populate-from-inspection` to fill supported observed HTTP fields and their
provenance, then review the reported unsupported or missing facts. The agent
fills remaining supported fields and evidence using the contract's structures.
Author questions only in `traceability.instance.questions`; `finalise` creates
the requirements projection when absent and rejects independently authored
projection content. Use `prepare-review` to resume, group gaps and finalise/validate
once; select a small question batch from the brief rather than asking one question
per diagnostic; group counts show where findings are concentrated. `show-field`
avoids loading unrelated document sections; use `show-fields` for several explicit
targets in one validation pass. Pointer failures provide schema hints without
silently choosing a different field. Read [field help](contract/field-help.md) for
the bulk input shape. Use the
contract's `apply-updates` batch for explicitly supplied facts and their evidence,
retaining the returned `documentsSha256`. The helper saves the existing YAML family;
the batch is an editing instruction, not a second source of requirements. It cannot
infer evidence support, answer questions or record review acceptance for you.
Optional `apply-updates --dry-run` checks the same candidate without saving it.
Read `applied` and `persistence` separately from validation: a saved edit can
make a previous review stale. Do not retry a saved batch with its old revision.
See [authoring outcomes](contract/authoring-outcomes.md) when interpreting results.

For an evolved bundle, `compare-source --documents DIR --previous-source DIR
--source DIR` compares explicit snapshots and identifies affected evidence. Review
the proposed changes before explicitly updating facts and the source identity.
Missing previous bytes are a limitation; never silently replace the source hash.
The [source comparison contract](contract/intake-contract.md#source-comparison)
explains explicit current-source review when a historical snapshot is unavailable.
Population cannot repair changed source bytes.

The lower-level `finalise` operation updates derived document metadata;
it reports `reviewContentSha256` for reviewing the current material content and never
records human acceptance on the user's behalf. Follow the shared workflow to
bind an actual review to that digest. `finalise` success confirms metadata was
saved; it does not establish complete intake. `prepare-review --stage draft` runs
both operations; use `--stage handoff` before claiming handoff readiness. The
lower-level `validate` remains available for read-only checks.
Report its actual gaps and errors, and review nonblocking notices without
turning samples, dates or missing values into inferred client facts.

If the runtime or a client tool is unavailable, report that precise limitation.
Where the client can read the working templates, it may still produce clearly
labelled unvalidated drafts. Missing mandatory assets block dependent drafting;
do not invent a substitute template. Preserve completed independent work.

On a command failure, use `--debug` before or after the subcommand for safe
exception types and package code locations on stderr. Stdout stays one JSON
result. Inspect the named document/pointer; do not edit generated fields or
switch parsers to work around validation. Debug output excludes raw exception
messages, client payloads and local variables.

## Return a reviewable result

Return the four filled or partial documents, a concise stakeholder review,
material gaps and the validation outcome actually observed. Save drafts before
waiting for answers. Results remain unexecuted unless identified run evidence
was supplied. A validator cannot prove that a source supports an assertion.

Use `prepare-review --write-review` for the reproducible stakeholder report beside
the forms. Review `brief.blankFields` before presenting the draft: populate
available source facts, inspect extraction limits and explain material unknowns
through the existing ledger. Its null inventory adds no required fields. Use
`brief.decisions` to separate current decisions, later execution questions and
engineering triage. Do not present diagnostic totals as questions for the client.
The [decision review contract](contract/intake-contract.md#decision-review-and-reproducible-stakeholder-output)
owns these views. Author intended bundle/scenario IDs in requirements only;
their plan copies are generated.

The review is a read-only view of the identified YAML revision. Local validation
does not establish client approval, MCP workflow confirmation, valid runtime
configuration, readiness to run or a passing test. Follow the existing MCP
contract only during a separately requested authoring handoff.
