# Topology Core

The control-plane migration now sources all routing, queue, and exchange names from explicit
configuration (`ControlPlaneProperties`, `WorkerControlPlaneProperties`, swarm-controller
environment variables, etc.). The legacy `io.pockethive.Topology` and `TopologyDefaults` helpers have
been removed from this module—services and tests must obtain values from bound properties or
`ControlPlaneTopologySettings` instead of static fallbacks.

This module currently retains shared AsyncAPI specifications and historical topology notes while they
are relocated to service-specific documentation. Once those references move, `common/topology-core`
can be retired entirely.
