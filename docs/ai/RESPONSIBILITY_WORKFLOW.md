# Responsibility and SSOT workflow

Use for changes to service/library responsibilities, state writers, configuration
resolution, parsing/validation, infrastructure effects or operation-success rules,
including changes to the meaning of responsibility headers.

## Authoritative sources

- [Architecture records](../ARCHITECTURE.md#responsibility-records) own responsibility
  IDs, current owners, allowed collaboration and forbidden actions.
- [Engineering rules](../ENGINEERING_RULES.md#responsibility-header) own header format
  and synchronization requirements.
- [Review rules](../REVIEW_RULES.md#mandatory-boundary-review--blocking-rules) own
  acceptance requirements and the single import-test exception.

This workflow owns the work sequence and evidence format. Plans, headers, HiveMind
and HiveMap link to the owning records; do not copy responsibility lists or the
import test's rule table into this document, a skill or another policy file.

## Implementation task

1. **Identify the affected responsibilities.** Find each architecture ID/section,
   current implementation and consumers. If its record is missing, establish it in
   the owning architecture document before implementation. Record the inspected
   revision and relevant uncommitted scope; do not invent a parallel catalogue.
2. **Define the change and observable acceptance.** Specify the owner, action or port
   change and how its effect will be verified. Search the whole repository for other
   implementations, including differently named helpers. Record active duplication
   as a defect, not as a permitted migration owner.
3. **Update the contract first.** Follow existing plan-review and protected-area rules.
   Separate current and target ownership. Identify the slice that transfers ownership
   and removes the old implementation. Do not present a future boundary as implemented.
4. **Implement with matching headers.** Link affected headers to the architecture IDs
   and sections. Keep architecture, headers and actual code aligned in the same change
   set, following the engineering rules for wording-only edits and header corrections.
   Move/adapt unit tests with each extracted responsibility; cover its behavior and
   port contract, with component tests where real collaboration warrants them, under
   the [test-value policy](../REVIEW_RULES.md#boundary-verification-and-test-value).
5. **Verify and hand off.** Run the single import test when dependency/import boundaries
   change; update its existing table where import permissions change. Run relevant
   behavior tests and required acceptance checks under the
   [boundary-verification policy](../REVIEW_RULES.md#boundary-verification-and-test-value).
   Ownership/selection evidence comes from imports/dependencies and source review;
   do not create tests merely asserting that a module or bean is used. Record results and gaps.
   Execution goals hand off evidence; they do not run self-review or review/fix loops.

## Separate review task

Check architecture, headers and code against each other. Include the following for
each affected responsibility in the review report. Scale detail to the change; a
checklist tick is not evidence.

| Evidence | Required content |
|---|---|
| Scope and contract | Reviewed revision/uncommitted scope; responsibility ID and architecture section; current versus target state |
| Owner and header | Implementation file/symbol; header reference; whether actual behavior stays within the contract |
| Alternative owners | Repository search commands/scope and inspected candidates; explain distinct roles or report duplication |
| Call path and actions | Consumer → port → selected implementation; state writer and actual effects; forbidden actions even without forbidden imports |
| Verification | Test name and execution result/artifact; relevant before/after evidence; distinguish behavior, composition and deployed acceptance |
| Verdict and gaps | Supported, violated or unverified for this responsibility; specific defects or missing evidence |

Reviewers inspect relevant paths themselves; implementation notes and tool results
are inputs. Empty searches do not exclude differently named implementations. A green
import test proves only its declared import restrictions. Two active authorities are
a CRITICAL SSOT blocker. Architecture/header/code disagreement or missing required
evidence blocks acceptance. Do not rewrite documentation merely to excuse code that
violates its intended contract.
