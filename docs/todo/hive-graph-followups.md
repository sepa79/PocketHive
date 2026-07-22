# Hive Graph Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/hive-graph-view.md`

The single-swarm React Flow view, topology edges, live runtime data, and worker details are implemented in UI V2. Remaining optional enhancements:

- [ ] Add an optional message-sampling overlay using the existing debug tap APIs.
- [ ] Derive and display edge health from upstream/downstream worker health when the product contract defines that classification.
- [ ] Add connected-node highlighting without changing the stable layout on status refresh.
- [ ] Add focused UI tests for stale-state presentation and layout stability.
