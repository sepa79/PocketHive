# Separate review of B01-R1–R3 corrections

Date: 2026-09-08. Result: **R1 and R2 accepted; R3 remains HIGH/open**.
B01 acceptance and B02 start remain blocked. No production fixes, commit, push or
runtime deployment were performed in this review.

All 20 correction file hashes and the recorded test/build log hashes matched
`fixes-evidence.json` before review. Changes to plan/status documents below are
review artifacts subsequent to that execution checkpoint.

## Remaining finding — B01-R3, HIGH

The extended `forbidden_type_prefixes` still permits ordinary JDK constructors
that open files outside java.io/java.nio. Both the source checker and actual
compiled ArchUnit rule accept this class in work-api:

```java
package io.pockethive.work.api;
public class ArchiveFileLeak {
    public int count(String path) throws java.io.IOException {
        try (var file = new java.util.zip.ZipFile(path)) { return file.size(); }
    }
    public String read(java.nio.file.Path path) throws java.io.IOException {
        try (var scanner = new java.util.Scanner(path)) { return scanner.nextLine(); }
    }
}
```

Source result: `[]` (no violations).
Compiled result: `COMPILED gate accepted ZipFile(String) and Scanner(Path)`.
Reproduction sources and log: `/tmp/b01-correction-review/`. The source probe used
`WorkBoundaryNegativeTest`'s temporary repository fixture. The compiled probe
invoked `CompiledWorkBoundaryTest.boundaryRule()` against the compiled class.
Neither method was executed; no files were read by this probe.

This is incomplete closure of the original JDK-effect restriction, not a new
production use of those APIs. The exact original URL/FileWriter examples are now
rejected, but B01's core-without-direct-IO requirement is not established. Define
an explicit allowed JDK dependency surface, with effectful overload restrictions
for mixed value/IO APIs, rather than treating the absence of a forbidden package
prefix as permission. Add the constructor cases to both gates' negative fixtures
and retain positive coverage for location values and packaged schema loading.

## Accepted corrections

- R1: the actual SDK renderer consumes the selected SequenceAccess bean. Alternate
  and disabled composition tests verify zero interactions with the static Redis
  generator. Processor's Spring-selected constructor also consumes TemplateRenderer;
  remaining explicit convenience constructors/global removal are previously recorded
  B06/B07 scope, not this finding.
- R2: every SDK projection invokes policy.update without consuming quota. Projection
  delivery and policy update/plan use synchronization; plan no longer receives a
  stale captured snapshot. The actual scheduler/Trigger test preserves the pending
  single request through true/false updates, the following tick and disable/re-enable.
  Rate's disable reset is tested even when it occurs between ticks.

## Six review passes and evidence

| Pass | Result |
|---|---|
| Plan outcome | R1/R2 requirements satisfied; R3 still blocks the direct-IO restriction. |
| Style | No new blocking implementation-unit/header finding in the correction scope. |
| Conciseness | update/plan separates observation from quota consumption without introducing a competing scheduler contract; the denylist still needs a more complete policy model. |
| Security | Direct filesystem effects can bypass the core boundary as reproduced above. No deployed security/tenant validation claimed. |
| Library necessity | Corrections add no runtime dependencies; existing Java/ArchUnit/Maven tools are sufficient. |
| Readability/maintainability | R1/R2 ownership and update order are explicit. R3 tests verify listed examples but cannot substantiate the broader stated restriction. |

Repository searches covered SequenceAccess implementations and renderer creation,
Spring constructor selection, ScheduledInvocationPolicy implementations/callers,
removed scheduler contracts, the canonical JSON policy and both gate consumers.
No competing scheduling or sequence algorithm owner was introduced by these fixes.

Fresh validation:

- The same seven focused suites listed in fixes-evidence.json: 24 Java tests passed,
  BUILD SUCCESS; `/tmp/b01-correction-review/java-tests.log`.
- Normal source gate passed; all 13 source/POM negative tests passed;
  `/tmp/b01-correction-review/source-gate.log` and `python-tests.log`.
- Independent constructor probe above passes both gates and demonstrates the gap.
- `git diff --check` passed.

The full 1202-test run was verified by its recorded digest, not rerun. No deployed
stack, broker traffic, official-ingress acceptance or drain convergence was tested.
