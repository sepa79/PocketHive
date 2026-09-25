/**
 * Responsibility: preserve the native Basic-login session and validate it against the native provider.
 * Must not: call auth-service, infer an admin identity or try alternate credentials.
 * Contract: docs/tcp-mock/legacy-workspaces.md#authentication-provider-and-ownership.
 */
class NativeAuthSession {
    constructor() {
        this.credentials = sessionStorage.getItem('auth-credentials');
        this.user = null;
    }
    async init() {
        if (!this.credentials) return false;
        return this.validate(this.credentials);
    }
    async validate(credentials) {
        const response = await fetch(HttpClient.endpoint('/api/auth/me'), {
            headers: { Authorization: `Basic ${credentials}` }
        });
        if (response.status === 401) { this.logout(); return false; }
        if (!response.ok) throw new Error('Native authentication unavailable');
        this.user = await response.json();
        this.credentials = credentials;
        sessionStorage.setItem('auth-credentials', credentials);
        return true;
    }
    async login(username, password) { return this.validate(btoa(`${username}:${password}`)); }
    getCurrentUser() { return this.user; }
    getAuthHeader() { return this.credentials ? { Authorization: `Basic ${this.credentials}` } : {}; }
    logout() {
        this.credentials = null;
        this.user = null;
        sessionStorage.removeItem('auth-credentials');
        sessionStorage.removeItem('auth-user');
    }
}
