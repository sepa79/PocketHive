# B02 narrowed YAML binding correction — 2026-09-08

Human decision: withdraw the global YAML-loader replacement and retain the small
binder correction. Earlier 172-test/full-loader evidence describes the withdrawn
implementation, not current acceptance.

WorkConfigBindHandler (31 lines; originally named WorkOutputBindHandler) extends Spring NoUnboundElementsBindHandler and
rejects nested properties that an Object-valued field cannot represent. Route type,
regex and co-constraint validation remains in WorkConfigurationParser.
Contract: [RESP-WORK-REDIS-ROUTES](../../../architecture/runtime-responsibilities.md#resp-work-redis-routes).

Removed the three YAML-loader classes, SPI registration, added direct SnakeYAML
dependency, loader-specific test class and its cumulative fingerprint file.
Spring's standard YAML loader remains the bootstrap decoder.

**Open B02 work:** `header: {}` still disappears and `header: []` still becomes blank
text before binding. Preserve these types in the target Work-config decoder before
canonical validation. Do not reintroduce a global SDK loader as a local route fix.
The [previous review](redis-routes-corrections-review-2026-09-08.md) retains the original
reproduction. Full startup/raw parity and B02 are not accepted.

Retained regression coverage belongs to WorkIOConfigBinderTest: two cases exercise
nonempty nested map/list headers through the standard YAML loader and actual binder;
one verifies that a selected route list replaces malformed lower-priority fields.
No tests were retained to certify the removed loader or internal module selection.

Verification: **165 focused behavior/import tests passed** using the previous RR suite
with `clean test`; log `/tmp/b02-yaml-withdraw-tests.log`. Clean removes the deleted
loader classes and SPI resource from build output. Root `-DskipTests package`, docs
build and `git diff --check` also passed; logs `/tmp/b02-yaml-withdraw-package.log`
and `/tmp/b02-yaml-withdraw-docs.log`. The original standard-loader probe confirms
nested-object rejection and the still-open empty-container cases; result
`/tmp/pockethive-rr-correction-review/withdrawn-loader-result.txt`. No commit/deployment.
