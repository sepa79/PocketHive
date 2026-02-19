# Clearing Export Service

Template-driven terminal worker that batches incoming `WorkItem` messages and writes business clearing files.

V1 scope:

- custom templates only (`headerTemplate`, `recordTemplate`, `footerTemplate`, `fileNameTemplate`),
- local directory sink with `*.tmp` + atomic rename,
- optional local manifest (`jsonl`) with one entry per finalized file.

## Example templates

Ready-to-use example config and payload fixtures:

- `clearing-export-service/examples/clearing-template-basic.yaml`
- `clearing-export-service/examples/payloads/clearing-message-1.json`
- `clearing-export-service/examples/payloads/clearing-message-2.json`

## AI usage playbook

Concrete, step-by-step usage instructions for AI contributors:

- `docs/ai/CLEARING_EXPORT_WORKER_PLAYBOOK.md`

See implementation plan: `docs/inProgress/clearing-export-worker-v1.md`.
