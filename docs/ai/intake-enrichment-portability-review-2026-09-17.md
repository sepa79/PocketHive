# Intake enrichment and portability review

The 1.8.0 candidate adds explicit human-form enrichment, content-based start/resume
guidance and portable local evidence. All 16 source/template/schema artifacts are
unchanged. The runtime candidate passed **249 public-CLI tests** in an offline,
read-only-package sandbox in **140.818 seconds**. This is local development proof,
not native Amazon Q/Copilot or runtime PocketHive qualification.

## Delivered behaviour

| Concern | Result |
| --- | --- |
| Human input | Original filenames, versions and missing skill metadata are accepted at the input boundary. |
| Enrichment | Exact source-pointer transfers reuse canonical authoring validation; originals are archived before raw working filenames are replaced. Unknown content stays visible. |
| Start/resume | Parsed content and the existing schemas distinguish raw, working, partial and mixed sets. Retained originals do not authorise re-importing or replacing an existing draft. |
| Portability | Bundled source references resolve relatively; external evidence is retained locally. Runtime-file evidence retains its relative source path for change-impact checks. |
| Corrections | Existing identities and answered questions survive scoped updates. Deferred decisions remain open without repeated questioning. |
| Handoff | Readiness, sample-adoption, evidence and approval checks remain in their existing owners. Relocation never manufactures a fresh confirmation. |

The canonical interface is the skill's
[intake contract](../../.agents/skills/pockethive-intake/contract/intake-contract.md).
Templates and schemas do not acquire another source format or state store.
Enrichment receipts are read-only operation records; the existing question ledger
owns unresolved decisions. There is no fallback migration or generic merge.

## Exploratory conversations

Fresh practitioner agents received the skill, synthetic sources and an initial
client request. Later replies were withheld until the preceding draft/question
was delivered. Agents saved drafts, command outcomes and concise transcripts;
the coordinator did not repair their documents before assessment.

| Trial | Observed result | Mechanical repairs |
| --- | --- | --- |
| Raw form, three turns | Saved WireMock/15 journeys per second; recorded 30 minutes, then corrected to 10. Preserved identities, unexecuted results and deferred success/latency decisions. | One incorrect asset-directory lookup. |
| Existing bundle, two turns | Added explicit read→submit journey, both HTTP 200, and 20 minutes. Copied the prepared bundle, made the original path unavailable, and resumed without reference edits. | None. A source-reading disclosure failure is recorded below. |
| Mixed input, three turns | Preserved the original baseline until the client explicitly selected replacement by peak traffic at 80 journeys per second for 45 minutes. Steps/environment stayed open. | One invalid question pointer rejected before persistence, then corrected. |
| Previous 1.7.0 package, one turn | Same raw-form request and source bytes produced a usable partial draft with the same next duration question. | Two: incorrect schema path and unsupported answer-reference fragment. |
| Fresh bundle recheck | Saved a usable portable partial draft and asked for journey steps/success definition. | None; the source-reading disclosure failure recurred. |
| Fresh check after entry-instruction change | Suppressed headers/bodies before source output; saved a portable draft and asked for journey definition. | None; first useful draft 148 seconds. |

Independent coordinator checks of the first five final document sets returned
zero errors at draft and handoff stages. Handoff correctly remained incomplete;
the gap counts were 30, 81, 30, 32 and 40 respectively. These counts represent
field/ledger findings, not independent questions to put to a client.
The sixth set independently returned zero errors with 39 gaps; draft exit 0 and
handoff exit 3. All observed results remained unexecuted.

In the single paired raw-form trial, time to useful populated review was about
129 seconds for the new candidate and 136 seconds for the previous package;
shell-call counts at the first question were 19 and 22. These are practitioner
measurements with different clock sampling and concurrent activity, not evidence
of a statistically established latency improvement. The new candidate required
one observed mechanical repair versus two. Final multi-turn counts must not be
compared with the single-turn baseline.

## Findings retained

- Two bundle practitioners printed synthetic sensitive fixture values during
  full-file inspection. Those values were excluded from authored intake and
  retained narrative logs. The targeted-read instruction was first tightened
  in the shared workflow, then moved into the entry instructions after the
  failure recurred. Instruction-only controls cannot guarantee model behaviour.
  A third fresh practitioner suppressed those fields before output, but still
  printed the entire parsed scenario mapping after redaction. Sensitive-value
  suppression passed for this fixture; source minimisation remains imperfect.
  This is not proof the failure is impossible in another client/model.
- One practitioner guessed a template path. Human-input guidance now names the
  canonical scaffold location and routes later field help through the CLI.
- One practitioner mixed a recap of supplied facts into a proposal-labelled
  limitations subtree. The primary facts retained correct provenance, but the
  recap's presentation was confusing. Writing guidance now keeps these kinds in
  separately sourced rows. That original observation is retained, not silently
  replaced with a repaired trial result.
- Some practitioner final replies exposed validation totals despite the intended
  engineering appendix. This remains a conversation-quality finding; the counts
  were not described as numbers of client decisions.

## Technical review and limits

Repository-wide ownership searches found one YAML codec, schema validator, path
resolver, authoring batch implementation, projection writer and workspace
assessment owner. New modules have separate responsibilities: transfer planning,
staged publication, evidence snapshots, declared-reference enumeration and
portability. Contract changes preceded implementation. No competing runtime
configuration, client-data schema or question store was introduced.

Tests cover source changes, protected fields, malformed transfers, conflicting
dispositions, preservation before replacement, repeated operations, snapshot
collisions, missing evidence links, stale reviews, moved directories and retained
source-comparison links. File writes remain atomic individually; publication is
not a whole-directory transaction. Failed or interrupted writes must be inspected
explicitly. Arbitrary runtime links and URLs in client prose are not portability
guarantees. Human review still owns source faithfulness and acceptance.

This finite exploration supports the implemented workflows. It does not establish
universal conversational reliability, independent stakeholder comprehension or
authenticated support for all native client surfaces. No load test, service call,
deployment, commit or push was performed.

Local evidence roots (not runtime dependencies):

- `/home/tim/.tmp/pip/intake-polish-conversations-jei6tiv_/`
- `/home/tim/.tmp/pip/intake-polish-complete-suite-mjpjo9g0/`
