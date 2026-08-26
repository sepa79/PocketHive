# Review Checklist

- [ ] Reviewed `docs/ENGINEERING_RULES.md` and `docs/REVIEW_RULES.md`
- [ ] One Java production type per file, except the narrow private nested-type exception; one TypeScript/React module concern per file; no public nested contract bags
- [ ] One clear responsibility per file; no kitchen-sink class was introduced or expanded
- [ ] New/materially changed runtime and boundary types have accurate responsibility headers
- [ ] Listeners/controllers remain thin boundaries and delegate domain behavior
- [ ] Repository-wide SSOT search found no competing parser, resolver, mapper, state writer, topology owner, or outcome calculator
- [ ] Conventional Commit message
- [ ] Tests cover new behaviour
- [ ] Static analysis and other automated checks pass
- [ ] Documentation updated
- [ ] No secrets or sensitive data
- [ ] Follows project policy and coding standards
- [ ] No cascading defaults introduced; backward compatibility only when explicitly required
- [ ] Correlation, idempotency, concrete target, and `runId` are checked at relevant control-plane boundaries
- [ ] `git diff --check` passes
