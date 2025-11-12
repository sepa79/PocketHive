# Worker Plugin Host — Output Binding Plan

## Motivation

The worker-plugin-host currently provides default values for `pockethive.outputs.rabbit.exchange` and `pockethive.outputs.rabbit.routingKey` just to satisfy the Worker SDK auto-configuration. Plugins are supposed to own their output configuration (and may even be noop), so forcing a host-level default is misleading and breaks scenarios where the plugin deliberately omits Rabbit outputs.

## Goal

Allow the host to boot without hardcoding any Rabbit output defaults. The Worker SDK should resolve the plugin's configured outputs (if any) after plugin configuration is applied, and gracefully handle noop outputs.

## Plan

1. **Lazy Output Resolution**
   - Update `WorkerControlPlaneAutoConfiguration` to defer reading `pockethive.outputs.rabbit.exchange` and routing key until declarables are created.
   - Replace the current `resolveTrafficExchange()`/`resolveRabbitInputQueue()` methods with suppliers that fetch values from the environment after plugin defaults + overrides have been processed.
   - If no output is configured and the worker's output type is `NONE`, skip queue declaration instead of throwing.

2. **Host Configuration Cleanup**
   - Once the Worker SDK change lands, remove `pockethive.outputs.rabbit.*` from `worker-plugin-host/src/main/resources/application.yml`.
   - Verify the host fails fast only when a plugin declares a Rabbit output but no exchange/routing key is provided.

3. **Plugin Manifest & Validation Enhancements**
   - Extend plugin manifest schema to flag whether a Rabbit output is required.
   - During plugin loading, validate that required outputs exist; emit actionable errors before the Worker SDK initialises.

4. **Tests & Documentation**
   - Add Worker SDK tests covering Rabbit outputs, noop outputs, and misconfigured outputs.
   - Update `docs/sdk/worker-plugin-packaging.md` and this plan once the SDK work is merged, noting that the host no longer needs to set rabbit defaults.

## Next Steps

1. Prototype lazy output resolution in Worker SDK auto-config.
2. Remove the host-level defaults once the SDK change is available.
3. Update plugin manifest validation + docs accordingly.
