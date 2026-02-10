# WorkItem v2 follow-ups (request/result envelopes)

**Area:** WorkItem/log/journal sensitive-data handling  
**Status:** Open (deferred for global solution)  
**Impact:** Can affect debug quality and data sensitivity in WorkItem step history.

---

## 1) Sensitive URL fragments may leak into step history

### Problem

`http.result` stores both `request.baseUrl` and `request.url` for observability/debugging.
Today these values are copied as-is.

If operators configure endpoints with credentials or query tokens, those values can be written to:

- WorkItem steps (`steps[*].payload`)
- logs and debug exports that include step payloads

### Current scope

- Redaction is intentionally deferred here; we do not want one-off masking in a single worker.
- Plan is to introduce a global sensitive-data filter for logs/journal (and related debug surfaces).

### Follow-up direction

- Add URL redaction before serializing `baseUrl`/`url` into `http.result`.
- Suggested minimum: mask userinfo + selected query params (`token`, `key`, `secret`, etc.).
- Keep host/scheme/path for troubleshooting.

---
