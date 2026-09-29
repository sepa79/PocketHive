/**
 * Responsibility: resolve same-application API paths and send authenticated HTTP requests.
 * Must not: decide workspace policy, identities or login success.
 * Contract: docs/tcp-mock/legacy-workspaces.md.
 */
class HttpClient {
    constructor(auth) {
        this.auth = auth;
    }

    static endpoint(path) {
        if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Expected an application API path');
        return new URL(path.slice(1), new URL('.', document.baseURI)).href;
    }

    async fetch(url, options = {}) {
        const headers = {
            ...options.headers,
            ...this.auth.getAuthHeader()
        };

        try {
            const response = await fetch(HttpClient.endpoint(url), { ...options, headers });
            
            if (response.status === 401) {
                this.auth.logout();
                window.location.reload();
                throw new Error('Unauthorized');
            }
            
            return response;
        } catch (error) {
            if (error.message !== 'Unauthorized') {
                console.error('HTTP request failed:', error);
            }
            throw error;
        }
    }

    async get(url) {
        return this.fetch(url);
    }

    async post(url, data) {
        return this.fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
    }

    async put(url, data) {
        return this.fetch(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
    }

    async delete(url) {
        return this.fetch(url, { method: 'DELETE' });
    }
}
