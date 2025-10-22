# Topology Core (legacy)

This module houses the legacy `io.pockethive.Topology` constants that used to seed queue and exchange
names across the platform. The control-plane refactor has replaced these fallbacks with explicit
configuration (`ControlPlaneProperties`, `WorkerControlPlaneProperties`, Swarm Controller environment
variables, etc.). New code **must not** depend on `Topology`; fetch the required values from bound
properties or descriptor settings instead.

The table below is kept only as a reference while existing tests and documentation migrate off the
class. Once every service consumes the new configuration-first contract the `Topology` API and this
module will be removed.

| Legacy constant | Environment variable | Historical default |
| --- | --- | --- |
| `Topology.SWARM_ID` | `POCKETHIVE_CONTROL_PLANE_SWARM_ID` | `default` |
| `Topology.EXCHANGE` | `POCKETHIVE_TRAFFIC_EXCHANGE` | `ph.<swarm>.hive` |
| `Topology.GEN_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR` | `ph.<swarm>.gen` |
| `Topology.MOD_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR` | `ph.<swarm>.mod` |
| `Topology.FINAL_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL` | `ph.<swarm>.final` |
| `Topology.CONTROL_QUEUE` | `POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE` | `ph.control` |
| `Topology.CONTROL_EXCHANGE` | `POCKETHIVE_CONTROL_PLANE_EXCHANGE` | `ph.control` |

> ⚠️ These defaults exist only for backward compatibility. Services should validate that each property
> is populated explicitly and fail fast when any value is missing.
