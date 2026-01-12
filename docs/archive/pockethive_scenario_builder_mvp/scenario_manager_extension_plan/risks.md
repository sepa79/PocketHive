
# Risks & Mitigations

- Divergence between UI and Manager schema: extract common JSON Schema package.
- Over-trusting `startAt`: let Orchestrator define T0; Manager doesn't do timing.
- Event storms: throttle SSE fanout; paginate `/runs`.
