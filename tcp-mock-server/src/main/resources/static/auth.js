/**
 * Responsibility: select the configured browser authentication provider.
 * Must not: try another provider, store credentials or infer authenticated identity.
 * Contract: docs/tcp-mock/legacy-workspaces.md#authentication-provider-and-ownership.
 */
class AuthModule {
    async init() {
        const response = await fetch(HttpClient.endpoint('/api/auth/config'));
        if (!response.ok) throw new Error('Authentication configuration unavailable');
        const config = await response.json();
        if (config.provider === 'NATIVE') {
            this.provider = new NativeAuthSession();
            document.getElementById('nativeLogin').classList.remove('hidden');
        } else if (config.provider === 'POCKETHIVE') {
            const session = await import('/auth-session.js');
            this.provider = new PocketHiveAuthSession(session);
            document.getElementById('pocketHiveLogin').classList.remove('hidden');
        } else {
            throw new Error('Unsupported authentication provider');
        }
        return this.provider.init();
    }
    login(username, password) { return this.provider.login(username, password); }
    getCurrentUser() { return this.provider.getCurrentUser(); }
    getAuthHeader() { return this.provider.getAuthHeader(); }
    logout() { this.provider.logout(); }
}
