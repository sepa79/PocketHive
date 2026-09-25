/**
 * Responsibility: validate the shared PocketHive session through the selected administration API.
 * Must not: create a second login, persist credentials or fall back to native authentication.
 * Contract: docs/tcp-mock/legacy-workspaces.md#authentication-provider-and-ownership.
 */
class PocketHiveAuthSession {
    constructor(session) { this.session = session; this.user = null; }
    async init() {
        if (!this.session.readStoredAuthSession()) return false;
        const response = await fetch(HttpClient.endpoint('/api/auth/me'), { headers: this.getAuthHeader() });
        if (response.status === 401) { this.logout(); return false; }
        if (!response.ok) throw new Error('PocketHive authentication unavailable or access denied');
        this.user = await response.json();
        return true;
    }
    getCurrentUser() { return this.user; }
    getAuthHeader() {
        const token = this.session.readStoredAccessToken();
        return token ? { Authorization: `Bearer ${token}` } : {};
    }
    logout() { this.session.clearAuthSession(); this.user = null; }
}
