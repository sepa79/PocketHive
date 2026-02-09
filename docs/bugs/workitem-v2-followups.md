# WorkItem v2 follow-ups (request/result envelopes)

**Area:** Worker SDK + Request Builder + Processor  
**Status:** Partially addressed, partially open  
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

- This is currently accepted for local/experimental usage.
- There is no dedicated redaction policy yet.

### Follow-up direction

- Add URL redaction before serializing `baseUrl`/`url` into `http.result`.
- Suggested minimum: mask userinfo + selected query params (`token`, `key`, `secret`, etc.).
- Keep host/scheme/path for troubleshooting.

---