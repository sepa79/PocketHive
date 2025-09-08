# Moderator Service

Filters or rewrites messages from the generator before forwarding them to the processor.

## Responsibilities
- Consume from `ph.<swarmId>.gen` and publish to `ph.<swarmId>.mod`.
- Apply moderation rules received through control-plane signals.

See [control-plane rules](../docs/rules/control-plane-rules.md) for signal formats.
