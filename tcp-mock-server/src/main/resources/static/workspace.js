// Presentation-only cache. WorkspaceService owns identities and default/deletion policy.
class WorkspaceModule {
    constructor(http) {
        this.http = http;
        this.workspaces = [];
        this.current = null;
    }

    async init() {
        return this.loadWorkspaces();
    }

    async loadWorkspaces() {
        try {
            const response = await this.http.get('/api/workspaces');
            this.requireSuccess(response);
            const catalogue = await response.json();
            const defaults = catalogue.filter(workspace => workspace.defaultWorkspace);
            if (defaults.length !== 1) throw new Error('Workspace catalogue requires one default');
            const savedId = sessionStorage.getItem('current-workspace');
            const selected = catalogue.find(workspace => workspace.id === savedId);
            const current = selected === undefined ? defaults[0] : selected;
            sessionStorage.setItem('current-workspace', current.id);
            this.workspaces = catalogue;
            this.current = current;
            return true;
        } catch (error) {
            ErrorHandler.handle(error, 'Failed to load workspaces');
            return false;
        }
    }

    getAll() { return this.workspaces; }

    getCurrentWorkspace() { return this.current; }

    async switch(id) {
        const workspace = this.workspaces.find(entry => entry.id === id);
        if (!workspace) return false;
        sessionStorage.setItem('current-workspace', id);
        this.current = workspace;
        return true;
    }

    async create(name, shared = false) {
        try {
            const response = await this.http.post('/api/workspaces', { name, shared });
            this.requireSuccess(response);
            const workspace = await response.json();
            this.workspaces = [...this.workspaces, workspace];
            return workspace;
        } catch (error) {
            ErrorHandler.handle(error, 'Failed to create workspace');
            return null;
        }
    }

    async delete(id) {
        try {
            const response = await this.http.delete(`/api/workspaces/${encodeURIComponent(id)}`);
            this.requireSuccess(response);
            this.workspaces = this.workspaces.filter(workspace => workspace.id !== id);
            if (this.current?.id === id) {
                const selected = this.workspaces.find(workspace => workspace.defaultWorkspace);
                this.current = selected === undefined ? null : selected;
                if (this.current) sessionStorage.setItem('current-workspace', this.current.id);
                else sessionStorage.removeItem('current-workspace');
            }
            return true;
        } catch (error) {
            ErrorHandler.handle(error, 'Failed to delete workspace');
            return false;
        }
    }

    async rename(id, name) {
        const workspace = this.workspaces.find(entry => entry.id === id);
        if (!workspace) return false;
        try {
            const response = await this.http.put(`/api/workspaces/${encodeURIComponent(id)}`,
                { name, shared: workspace.shared });
            this.requireSuccess(response);
            const updated = await response.json();
            this.workspaces = this.workspaces.map(entry => entry.id === id ? updated : entry);
            if (this.current?.id === id) this.current = updated;
            return true;
        } catch (error) {
            ErrorHandler.handle(error, 'Failed to rename workspace');
            return false;
        }
    }

    requireSuccess(response) {
        if (!response.ok) throw new Error(`Workspace request failed (HTTP ${response.status})`);
    }
}
