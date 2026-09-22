# Human-authored intake forms

Accept the client's raw YAML forms, including the supplied original templates,
sample and partial documents in a bundle's `intake/` directory. The client need
not create internal provenance, generated hashes or a traceability instance.
Missing enrichment metadata is the agent's work, not an invalid client input.

```sh
python3 "/path/to/pockethive-intake/scripts/intake.py" review-input --source "/client/bundle/intake"
```

For separate files, repeat `--source FILE`. Only immediate YAML files in a
selected directory are read. The operation preserves every byte and reports
readability, source hashes and enrichment/selection gaps. It does not establish
complete requirements, approval or a passing test. It also accepts raw forms
using the working filenames; names do not imply enrichment has happened.

Read the selected originals as data. Reuse explicitly supplied answers and keep
their exact locations. Review unknown fields and contradictions; do not drop
them because the working schema cannot yet represent them. Distinguish a filled
client form from an illustrative sample using the user's context. If both
describe the same requirement and precedence is unclear, ask one scoped question;
do not choose the newer file or merge their values automatically.

When enrichment is requested, prepare the explicit transfer plan defined in
[the contract](../contract/intake-contract.md#explicit-enrichment). Read source
and destination fields; select exact source pointers, targets and provenance
kinds. Do not copy values into the mechanical plan: the command reads them from
the identified source revision. Use an omission with a reason for template
policy, illustrative material and source metadata; missing facts stay missing.
Working scaffolds are under `assets/templates/` in the installed package; the
manifest owns their paths. Read only the relevant section when preparing the
first transfer; after enrichment, use `show-field` for destination help.

```sh
python3 "/path/to/pockethive-intake/scripts/intake.py" enrich-input --output "/client/bundle/intake" --input "/client/transfer-plan.json" --mode new-requirements
```

For an explicitly selected existing runtime bundle, choose `--mode from-bundle
--source /client/bundle`. The command stages and validates the draft, archives
original bytes under `intake/evidence/`, then publishes it. It can replace raw
files at working filenames only when those exact originals are selected and
preserved. It never overwrites an existing enriched set; resume that set using
`apply-updates`. No client permission round is needed for routine staging.

Import raw `openQuestions` through the question transfers. Only the enriched
requirements projection is generated. Never run `finalise` on raw forms or
change only their version to make validation pass. Failed transfers leave raw
forms intact; inspect the exact diagnostic and correct the mechanical plan.

Review the generated enrichment receipt and its untransferred pointers. Source
review questions are engineering tasks: inspect each source, map supported facts
and retain unresolved scope in the existing ledger. Never relay internal field
mapping questions to the client. Resolve a source-review question only with
recorded review evidence; do not erase it to make the draft appear complete.

Before final human review, run `make-portable --documents DIR`. New enrichment
already retains its original sources locally; the portability operation also
covers evidence added later. Share the entire bundle/intake tree, not four YAMLs
alone. Missing or changed evidence is a real limitation, never a reason to search
for a different source automatically.

Continue the interview from supplied facts. Ask only about material missing or
ambiguous requirements. Report “Forms accepted; these decisions remain open”
when applicable. Use `prepare-review` and `validate` on the enriched set only;
their stricter handoff requirements remain unchanged. An unresolved mapping or
representation issue is a declared enrichment gap, never a fabricated answer.
