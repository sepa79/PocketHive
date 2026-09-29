# SUT Environments Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/sut-environments-plan.md`

The SUT registry, selection flow, swarm propagation, bundle-local SUT support, and baseline UI are implemented.

The proposed target and its acceptance criteria now live in
[Global SUTs, Shared Mocks and Continuous Plans](global-sut-mocks-spec.md).
That specification covers global resolution, explicit deprecated bundle import,
shared mock lifecycle and the dedicated SUTs page. It does not claim those changes
are implemented. Review the items below against that proposal before delivery.

Remaining work:

- [ ] Decide and document whether fine-grained per-environment CRUD is required beyond whole-file editing.
- [ ] Normalize active-SUT selection and visibility UX against the current Scenarios workspace.
- [ ] Update remaining worker/template examples to use the canonical SUT endpoint form consistently.
- [ ] Add focused contract and UI tests for any newly approved editing surface.
