/**
 * Responsibility: read retained browser audit observations.
 * Must not: claim these observations are authoritative audit evidence or persist authentication.
 * Contract: docs/tcp-mock/legacy-workspaces.md#authentication-provider-and-ownership.
 */
class BrowserAuditLog {
    static read() {
        const log = localStorage.getItem('audit-log');
        return log ? JSON.parse(log) : [];
    }
}
