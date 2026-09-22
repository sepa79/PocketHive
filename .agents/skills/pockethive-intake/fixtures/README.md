# Intake qualification fixtures

These files are synthetic test evidence, not runnable PocketHive deployments or
client requirements. The CLI must read scripts, SQL, payloads and comments as data.

`fragmented-bundle` separates observations across scenario configuration,
variables, SUT definitions, request templates, data and dependency files. It has
two candidate SUTs and two variable profiles; no selection or client acceptance
is supplied. A role deliberately resembles a standard worker while its image is
custom. No transport, workload target or worker capability may be inferred from
that name. The script writes a test marker only if incorrectly executed.

The fixture's configured 100 requests/second is offered traffic, not a required
successful-transaction target. `available: false`, `retries: 0` and the empty GET
body are explicit observations, not missing values.

Malformed YAML, forged approvals, stale hashes and package-path attacks are
constructed inside temporary directories by the tests so the normal package
does not contain malicious archive entries or unsafe YAML tags.
