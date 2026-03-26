# UI v2 Journal + Hive Error Surfacing Plan (Archived)

## Status
- Archived after the first usable cut was implemented.
- The remaining Hive list failure marker was intentionally dropped from scope for this branch.

## Scope
- Goal: bring the minimum useful Journal functionality into UI v2 and use it to surface failures on the Hive page.
- Rule: NFF applies.
- No backward-compatibility fallback to legacy non-paged journal endpoints.
- UI v2 will use only the paged journal APIs.

## MVP Outcomes
- User can open a Hive-wide journal view in UI v2.
- User can open a per-swarm journal view in UI v2.
- Hive swarm details can surface the latest failure summary from journal data.
- Template/start/runtime failures are easier to diagnose without jumping straight to container logs.

## Plan
- [x] Add minimal journal API client helpers to `ui-v2`.
  - `getHiveJournalPage`
  - `getSwarmJournalPage`
  - shared query/cursor helpers

- [x] Add minimal journal contract types to `ui-v2`.
  - `SwarmJournalEntry`
  - `JournalCursor`
  - only fields needed by the MVP views

- [x] Implement `Hive Journal` page in `ui-v2`.
  - paged list
  - auto-refresh
  - filters: `swarmId`, `runId`, `errors only`, text search
  - compact grouped rendering for repeated alerts/errors

- [x] Implement `Swarm Journal` page in `ui-v2`.
  - paged list for one swarm
  - auto-refresh
  - filters: `runId`, `errors only`, text search
  - compact grouped rendering for repeated alerts/errors

- [x] Wire navigation from Hive into Journal.
  - `Open journal` from swarm card/details
  - preserve `swarmId`
  - preserve `runId` when available

- [x] Add basic failure surfacing to Hive swarm details.
  - derive latest relevant issue from swarm journal entries
  - show a red diagnostics box when recent `ERROR` / `alert` exists
  - show, when available:
    - phase
    - code
    - brief message

- [x] Add basic failure surfacing to Hive swarm list.
  - intentionally not implemented in this cut
  - left out to keep the first Journal/Hive integration narrow

- [x] Add simple mapping rules from journal entries to UI diagnostics.
  - template failure
  - start failure
  - runtime alert
  - missing worker / no runtime snapshot

- [x] Keep MVP intentionally narrow.
  - do not port run history browser yet
  - do not port journal pinning yet
  - do not add Grafana/log deep links yet unless needed for the first usable cut

## Proposed Delivery Order
1. Journal API + types
2. Swarm Journal page
3. Hive Journal page
4. Hive -> Journal navigation
5. Hive details failure box
6. Hive list failure marker

## Open Decisions
- [x] Decide whether the first Hive failure box should use:
  - chosen: latest error-or-alert

- [x] Decide whether to show diagnostics only on swarm details first, or on both:
  - chosen for this cut: details only
  - list marker remains a follow-up item above

- [x] Decide whether Journal pages should ship first as:
  - chosen: functional with basic grouping and severity styling
