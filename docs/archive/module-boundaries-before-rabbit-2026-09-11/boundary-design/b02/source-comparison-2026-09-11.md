# Branch/source comparison — 2026-09-11

## Verdict and scope

Do not discard the entire branch on the evidence collected here. Stop treating the
current B02 sequence as an almost-finished SSOT transfer. Rework the Rabbit configuration
path as one complete responsibility, with the old declarations, parsing, environment
projection and actual adapter consumption considered together. Existing useful transfers
should be retained or reused by responsibility, not accepted because their commits passed
earlier narrow reviews. This is a source comparison and bounded architecture assessment,
not a complete review or test certification of the branch.

No reset, revert, checkout, commit, deployment or production edit was performed. Existing
uncommitted plan edits were preserved. Comparisons use local Git objects; no remote fetch
was performed, so remote-tracking refs are not presented as freshly verified server state.

## Actual comparison points

The branch reflog records creation from `merge/rewrite-lifecycle-mcp` at `18d8cfbc`.
Later merge `be3c6a15` incorporated further MCP work. Comparing only with main would
attribute inherited/integrated work to this refactor: current main `fe9f9dba` is an ancestor,
but it is not the branch's creation point.

| Baseline | Meaning | Diff to committed HEAD `0dbddaee` |
| --- | --- | --- |
| `18d8cfbc` | Actual branch creation point | 1,029 files; +61,233 / -18,856 lines, including subsequent integration |
| `19d56091` | Before the Work boundary plan/B01 | 331 production Java files changed; +9,410 / -5,504 production Java lines |
| `eb681ee7` | B01 checkpoint, before B02 | 381 files; +18,450 / -4,040 total lines; 181 production Java files, +6,418 / -3,201 |

Line counts describe scope, not quality. B02 also changes 90 test files and 45 documentation
files. A whole-branch rollback would remove earlier Controller/Scenario decomposition and
MCP integration as well as the disputed configuration work.

## Blocking finding: a parallel Rabbit configuration authority was introduced

At the source revision, SDK `RabbitInputProperties` and `RabbitOutputProperties` already
held defaults and text normalization. Against B01, current input properties only lose
input enablement/autoStartup and gain a header; output properties are unchanged.

Commit `2e5b691e` adds `rabbit-config/RabbitInputSettingsParser` and its output counterpart
with separate defaults, normalization and constraints. Current startup still binds SDK
properties through `WorkInputConfigBinder` / `WorkOutputConfigBinder`. Their validation
hook defaults to no-op, and neither Rabbit properties class overrides it. Controller and
Scenario use the new parser path instead. This is not a completed move of authority.

Fresh source-compiled offline probe:

```text
SDK validation accepted: queue=jobs, prefetch=0
Shared parser problems: [WorkConfigurationProblem[path=inputs.rabbit.prefetch, message=Must be a positive 32-bit integer.]]
```

Probe: `/tmp/ph-branch-comparison/RabbitOwnershipProbe.java`, compiled directly against
current source. It calls the properties validation hook used by the binder and the parser;
it is not a full Spring startup or broker test. Current `WorkIOConfigBinderTest` also
explicitly expects Rabbit output binding with `persistent=true` and a null exchange.

The owner must be decided once for the complete Rabbit settings responsibility. Keeping
the existing properties as the public settings API is possible; relocating their rules
behind another public type is also possible. Neither class name establishes SSOT. In both
cases there must be only one implementation and every consumer must use its result.

## Inherited problem: properties did not control all actual Rabbit behavior

At `18d8cfbc`, `RabbitWorkInputFactory.create(definition, config)` does not consume `config`.
That remains true now. Repository searches at both revisions find prefetch/concurrency
getter calls in tests, not production consumers. The current Rabbit environment exporter
now emits these settings, but this does not establish that the listener uses them.

This corrects the preceding conversation's overly broad assertion that the old properties
were already the effective SSOT. They held intended configuration, but the end-to-end
path was not complete. Resetting restores this problem; adding parser ports does not fix it.
By contrast, current `RabbitWorkOutput` demonstrably reads persistent/exchange/routingKey
from output properties. Do not generalize the unused-input finding to every Rabbit setting.

Planning consequence: applying configured tuning cannot be deferred behind a phase boundary
while claiming end-to-end acceptance for that tuning. The required change is bounded to
settings consumption; it does not automatically authorize the entire B05 transport extraction.

## Useful changes that are not equivalent to the Rabbit duplication

- Redis dataset properties remove local constraints/source-mode logic and delegate to
  `RedisConfigurationParser`; scheduler properties delegate defaults and parsing to shared
  schedule/settings owners. These are actual delegation changes, though repeated raw/typed
  conversions deserve simplification. They are not proof of complete B02 acceptance.
- Scenario's validator loses local Work/template validation and delegates to shared APIs
  (`33` additions / `441` deletions in that file against B01). Reverting restores those copies.
- The old request-template loader is deleted; file loading lives in `request-template-files`
  and calls the shared semantic parser. Request Builder, HTTP Sequence and Scenario consume
  these owners. Preserve the behavioral tests, especially auth error classification.
- Named topology resolution and the Controller final candidate/environment gate address
  concrete ownership and consistency problems. Retain them as candidates for reuse; this
  comparison does not independently certify all their consumers or previous test results.
- B01 moves Work contracts and separates Control Plane infrastructure. It is a possible
  base for reconstructing B02, not a reason to return to the entire branch's origin.

## Recommended recovery

1. Suspend the newly written closure checklist as an implementation prescription. It
   assumed the new parser was necessarily the right owner before comparing the source.
2. Use `eb681ee7` as the comparison/reconstruction baseline if restarting B02 is chosen.
   Preserve current HEAD and uncommitted work; do not reset the existing branch blindly.
3. Establish one Rabbit settings API, migrate startup/Scenario/Controller and actual
   adapter consumption together, remove the second rule set, and verify non-default
   behavior. Choose the minimum module boundary needed for that complete responsibility.
4. Reuse the actual Redis/local/template transfers and their meaningful tests. Avoid
   replaying the large `2e5b691e` checkpoint wholesale as if it were a coherent acceptance unit.

A fresh isolated B02 reconstruction is a reasonable option if incremental repair remains
hard to understand. This comparison establishes reasons to rebuild the disputed path,
not evidence that all 181 changed production files must be thrown away.

## Review passes and evidence limits

- Plan: blocking; the Rabbit transfer leaves startup and effective consumption outside
  the claimed shared configuration path. The current acceptance sequence needs correction.
- Style/boundaries: blocking SSOT finding above. This was not a file-by-file header audit.
- Conciseness: retaining properties plus parser rules adds a competing concept; genuine
  delegations elsewhere must be distinguished from this duplication.
- Security: no security acceptance claimed; no live services, credentials or deployment
  actions were involved. Existing auth/path tests were inspected as required retained evidence,
  not rerun or certified here.
- Libraries: the observed issue needs no new dependency or custom scanner. Existing module
  and import enforcement can accompany the repaired ownership boundary.
- Maintainability: per-field transfers and narrow review status obscure whether values
  actually reach the worker. Acceptance should follow a whole responsibility's consumers.

Evidence: branch reflog, first-parent history, diffs at all three baselines, source/current
repository searches for Rabbit settings consumers, targeted source reads of binders,
factories, parser/composition, Redis/scheduler properties and Scenario/template delegation,
plus the source-compiled probe above. No full build, regression suite or deployed behavior
verification was run in this comparison. B02 remains unaccepted.
