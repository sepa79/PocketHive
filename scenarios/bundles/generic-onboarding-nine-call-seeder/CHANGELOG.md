# Changelog

## 2026-06-08 - Wizard generated

- Created bundle generic-onboarding-nine-call-seeder from wizard intent.
- Pattern: sequence.
- Auth: none.
- Data source: SCHEDULER.
- Updated validation polling proof to use HTTP-sequence JSON body retry predicates:
  `retry.whileJson` for `validationStatus=PENDING` and `retry.failJson` for
  rejected or timeout terminal states.
- Added ten tenant profiles with `userCount: 10000` each.
- Changed the generator finite-run cap to `maxMessages: "{{ vars.userCount }}"`.
- Added tenant-specific Redis result lists for approved and active account/card
  outputs.
- Raised HTTP sequence concurrency to 64 in-flight journeys for the scale proof.

### Evidence

- Pending 10 tenant x 10000 user scale run.
