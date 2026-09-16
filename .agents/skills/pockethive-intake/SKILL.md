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
4. Read the [package manifest](contract/manifest.json),
   [intake contract](contract/intake-contract.md) and relevant schemas, then the
   four working templates named by the manifest.
   [Template changes](references/template-changes.md) explain the reviewed corrections.
   The contract owns paths, field shapes, requiredness and validation. Do not
   reconstruct those rules in prompts or another script.
5. Read [PocketHive context](references/pockethive-context.md) when interpreting
   bundles, checking capability limits or preparing a later MCP handoff. Read
   [client guidance](references/clients.md) when loading or qualifying the skill.

## Work inside the portable package

This folder contains the instructions, original and working templates, contract,
validator and its pinned libraries. No personal skill installation or PocketHive
source checkout is required. Python **3.10 or later** is an explicit validator
prerequisite; dependencies are bundled. Do not download replacements, install a
different parser or claim validation from a manual review.

Resolve package assets through the CLI's package resolver. Inputs and generated
documents have separate roots; never write client answers into packaged assets.
Keep the resulting YAML in the client's authoring workspace for Git review.
Do not commit, push or modify client/global configuration implicitly.

The examples below use an explicit installed skill path. Replace it and the
input/output paths with the selected locations; do not assume the current
directory is the skill root.

```sh
python3 "/path/to/pockethive-intake/scripts/intake.py" verify-package
python3 "/path/to/pockethive-intake/scripts/intake.py" initialise --output "/client/intake" --mode new-requirements
python3 "/path/to/pockethive-intake/scripts/intake.py" initialise --output "/client/intake" --mode from-bundle --source "/client/bundle"
python3 "/path/to/pockethive-intake/scripts/intake.py" inspect-bundle --source "/client/bundle"
python3 "/path/to/pockethive-intake/scripts/intake.py" populate-from-inspection --documents "/client/intake"
python3 "/path/to/pockethive-intake/scripts/intake.py" finalise --documents "/client/intake"
python3 "/path/to/pockethive-intake/scripts/intake.py" validate --documents "/client/intake" --stage draft
python3 "/path/to/pockethive-intake/scripts/intake.py" validate --documents "/client/intake" --stage handoff
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
projection content. `finalise` updates derived document metadata;
it reports `reviewContentSha256` for reviewing the current material content and never
records human acceptance on the user's behalf. Follow the shared workflow to
bind an actual review to that digest. Validate after editing.

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

The review is a read-only view of the identified YAML revision. Local validation
does not establish client approval, MCP workflow confirmation, valid runtime
configuration, readiness to run or a passing test. Follow the existing MCP
contract only during a separately requested authoring handoff.
