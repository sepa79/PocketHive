# B01 independent review

Date: 2026-09-07. Result: **changes required; B01 is not accepted**.
This is the separately requested review after execution handoff. No production fixes,
commit, push, deployment or B02 work were performed.

Reviewed base: `e0d378711b6447f8bb3e0f8bbf787f9b06c2b5bb` plus the uncommitted
B01 working tree. All 312 paths in `files-sha256.json` matched their recorded bytes
before review; this report is a new review artifact outside that execution manifest.

## Findings

### B01-R1 — HIGH: selected sequence port is ignored by SDK composition

`PocketHiveWorkerSdkAutoConfiguration.templatingRenderer` (lines 384–387) creates
`ConfiguredRedisSequenceAccess` directly. The `SequenceAccess` bean declared at
lines 99–103, including a user-supplied replacement or disabled implementation,
is never injected into this renderer. Template sequence effects still select the
static Redis generator regardless of the chosen port.

Reproduction: reuse `WorkControlCompositionTest.workerContext`, supply a mocked
`SequenceAccess` returning `CHOSEN-PORT`, and render a sequence expression through
the context's actual `TemplateRenderer`. Mock the static Redis generator to prevent
all network effects and return a different marker. Observed:

```text
SEQUENCE selectedBean=true rendered=BYPASSED-PORT chosenCalls=0
```

Inject the selected port into the renderer bean and add an actual SDK composition
test covering an alternate/disabled port. `SequencePortRenderingTest` only constructs
renderers directly and cannot catch this wiring defect. B06's deferred removal of the
existing Redis globals does not defer B01's required port injection.

### B01-R2 — HIGH: Trigger loses a pending single request between scheduler ticks

`SchedulerWorkInput.registerStateListeners` (lines 197–207) replaces the stored
snapshot on every enabled-state update but delivers it to the policy only on the
next tick. An enabled `singleRequest=true` update followed by enabled
`singleRequest=false` before that tick loses the first request. Previously
`TriggerSchedulerState.update` observed each update and retained the pending request.

Reproduction: start the actual new `SchedulerWorkInput` with a long initial delay,
capture its registered CP state listener, deliver those two enabled snapshots, then
call `tick(10000)`. Use the actual `TriggerSchedulePolicy` and a mocked dispatch port.
Compile the original `TriggerSchedulerState` and `SchedulerState` directly from HEAD
into the temporary probe directory and supply the identical updates before planning.

```text
TRIGGER current dispatches=1
TRIGGER baseline quota=2
```

The ordinary interval invocation remains; the additional single request disappears.
Preserve delivery/processing of every relevant revision without consuming quota in
a config callback, and add a scheduler-to-policy integration regression case. This
is introduced by B01, not the deferred B03 drain/state-ownership debt.

### B01-R3 — HIGH: core infrastructure gates allow JDK network/filesystem effects

`tools/work-boundaries.json`'s `forbidden_type_prefixes` bans selected JDK APIs
but omits other ordinary effectful clients. A production class in `work-api` can
call `java.net.URL.openStream()` and construct/write `java.io.FileWriter` while both
the normal source gate and the compiled ArchUnit rule accept it. Maven dependency
bans cannot detect these JDK-only effects.

Reproduction: add this class only to the temporary source fixture used by
`WorkBoundaryNegativeTest`, then compile it outside the repository and run the actual
`CompiledWorkBoundaryTest.boundaryRule()` against it:

```java
package io.pockethive.work.api;
public class ClientLeak {
    public java.io.InputStream network(java.net.URL url) throws java.io.IOException {
        return url.openStream();
    }
    public void file(String path) throws java.io.IOException {
        try (var out = new java.io.FileWriter(path)) { out.write("effect"); }
    }
}
```

Both gates accept it. Neither method was executed; no connection or file effect was
performed. Extend the canonical policy to cover effectful JDK types/calls while
allowing explicitly permitted value types. Add permanent negative cases exercising
both source and compiled enforcement. This is a new B01 guard-coverage defect;
no existing production use of these probe methods is alleged.

## Required review passes

| Pass | Result |
|---|---|
| Plan outcome | B01 acceptance blocked by R1–R3: injection, Trigger parity and effective infrastructure exclusion are requirements of this slice. B02/B03/B06 deferred config/state/global-removal work was not counted as newly introduced defects. |
| Style guide | Checked extracted API/DTO/builders, ownership headers, SDK composition and the affected runtime changes. No additional blocking style finding beyond the functional boundary defects above. |
| Conciseness | Direct injection of the already selected port removes the redundant adapter construction in R1; fixes should not introduce another sequence owner or competing scheduling contract. |
| Security | R1 defeats explicit selection/disablement of sequence effects; R3 leaves effectful client escape paths. Inspected the moved SpEL restrictions and exception boundary; no new sandbox relaxation identified. No deployed security/tenant acceptance was performed. |
| Library necessity | Moves reuse existing Jackson, Micrometer, schema validation, Spring, Maven Enforcer and ArchUnit facilities. No new runtime library is needed for the reported fixes. |
| Readability/maintainability | Module owners are clearer, but green wiring/guard tests overstate their coverage. Tests must cover selected-port wiring and multiple state revisions, not just isolated implementations. |

## Ownership searches and verification

Repository-wide `rg` searches covered moved Work/auth/template contract definitions,
sequence adapters/global access and renderer construction, scheduler policy/state
implementations and update delivery, CP publisher/topology/customizer owners and all
Rabbit listeners. Results are retained in `/tmp/b01-review-probes/ownership-searches.txt`.
These searches do not close the previously recorded repository-wide SSOT backlog.

Fresh checks:

- `python3 tools/check-work-boundaries.py`: passed for the real working tree.
- `python3 -m unittest discover -s tools/tests -p test_work_boundaries.py`: 11 passed.
- `./mvnw -B -ntp -pl trigger-service -am -Dtest=WorkControlCompositionTest,WorkControlFactoryPolicyTest,TriggerSchedulePolicyTest,SequencePortRenderingTest,CompiledWorkBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`: 18 tests, zero failures/errors/skips; BUILD SUCCESS. Log: `/tmp/b01-review-maven.log`.
- Independent probes above reproduced R1–R3 despite those passing tests. Sources and
  output are under `/tmp/b01-review-probes/`; runtime probes use mocked IO only and
  the same explicit test metadata environment as root Surefire configuration.
- `git diff --check`: passed.

The earlier full 1197-test execution report was not rerun in this review. No deployed
stack, broker traffic, official-ingress acceptance or shutdown/drain convergence was
verified. Existing factory/executor tests support their specific in-process assertions;
they are not evidence of full deployed Work/Control independence.
