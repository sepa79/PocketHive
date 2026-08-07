# Functional Swarm DSL POC

This scenario proves the remote Functional Swarm path with a real swarm:

`Redis request list -> Functional Swarm ingress -> Processor -> Functional Swarm reply sink -> unique Redis reply list`

The public Java client writes the versioned request defined in
`docs/spec/functional-swarm-rpc.schema.json` to
`pockethive.functional-swarm.poc.requests`. The ingress accepts only a reply list
under `pockethive.functional-swarm.poc.reply.<requestId>`, renders the canonical
HTTP template, and preserves the generated transport headers through Processor.
The reply sink validates the canonical `http.result` and performs an atomic,
idempotent publish with a 30-second TTL.

The sample deliberately has no HTTP or Redis authentication. Functional Swarm DSL v1 rejects
HTTP templates with `authRef` rather than silently executing them differently, and its Redis
endpoint contract has no credential fields.
