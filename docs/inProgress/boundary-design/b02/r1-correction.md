# B02 R1 correction — 2026-09-08

Status: implemented and tested, awaiting separate review. No acceptance of the rest
of B02 is implied. Original finding: [R1 review](review-2026-09-08.md).

The file adapter now translates RequestTemplateParser's INLINE_AUTH problem category
to the existing AuthFailureException configuration contract. It retains the parser
exception as the cause and includes the file path in the auth message, preserving
file-specific deduplication identity. This applies both to standalone inline auth and
inline auth combined with authRef. Other parser and IO errors retain their previous
classification.

Ownership stays with RESP-REQUEST-TEMPLATE-PARSE: the parser validates the document;
TemplateLoader translates its typed result for runtime callers. Request Builder and
HTTP Sequence consume their existing auth failure path. No document-field checks or
new error handling were added to those workers. The file adapter declares its direct
dependency on the existing auth-contracts module, and its header and architecture
record describe this translation.

## Verification

The existing loader rejection test now checks the canonical auth failure category for
both invalid forms. Two worker behavior cases verify the actual first-invocation error
and second-invocation null result; the HTTP-sequence case also verifies no HTTP call.
They use the real loader and existing in-process fixtures, without external services.

Before the production correction, the loader suite had **2 expected failures out of
10 cases**: both new auth-classification assertions failed. Maven skipped the dependent
worker modules after that failure; their tests were not claimed as executed in that run.
Command used request-builder-service,http-sequence-service with `-am`, selected
TemplateLoaderTest,RequestBuilderWorkerImplTest,HttpSequenceRunnerTest and `-fae`.
Log: `/tmp/b02-r1-before.log`.

After correction, **42 cases passed, zero failures/errors/skips**:

```bash
./mvnw -B -ntp -pl request-builder-service,http-sequence-service,scenario-manager-service -am \
  -Dtest=TemplateLoaderTest,RequestTemplateParserTest,RequestTemplateFindingsTest,RequestBuilderWorkerImplTest,HttpSequenceRunnerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

This comprises loader 10, parser 4, authoring projection 2, request-builder 14,
HTTP-sequence 9, repository scenario validation 1 and import test 2. The prior review
probe now reports `AuthFailureException.find: true` for both old and corrected loaders.
The worker tests verify the repeated-failure outcome that the probe itself did not.
Log: `/tmp/b02-r1-after.log`; durable summaries: [R1 verification](r1-verification.txt).

The documentation build (`npm --prefix docs-site run build`) and `git diff --check`
also passed. Documentation build log: `/tmp/b02-r1-docs.log`.

The [current source fingerprint](r1-source-changes.sha256) covers the B02 Java/POM scope
after correction. The original source-changes.sha256 and review report remain evidence
of the pre-correction state. No production changes occurred outside TemplateLoader and
its dependency declaration; the parser and worker runtime code were not changed.

No automatic review/fix loop, commit, push or deployment was performed.
