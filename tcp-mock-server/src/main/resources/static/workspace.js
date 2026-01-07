// Workspace Module - Real Backend Integration
class WorkspaceModule {
    constructor(http) {
        this.http = http;
        this.workspaces = [];
        this.current = null;
    }

    async init() {
        await this.loadWorkspaces();
        const savedId = sessionStorage.getItem('current-workspace') || 'default';
        this.current = this.workspaces.find(w => w.id === savedId) || this.workspaces[0];
    }

    async loadWorkspaces() {
        try {
            const response = await this.http.get('/api/workspaces');
            this.workspaces = await response.json();
        } catch (error) {
            ErrorHandler.handle(error, 'Failed to load workspaces');
            this.workspaces = [{ id: 'default', name: 'Default Workspace', owner: 'system', shared: false }];
        }
    }

    getAll() {
        return this.workspaces;
    }

    getCurrentWorkspace() {
        return this.current || this.workspaces[0];
    }

    async switch(id) {
        const workspace = this.workspaces.find(w => w.id === id);
        if (workspace) {
            this.current = workspace;
            sessionStorage.setItem('current-workspace', id);
            return true;
        }
        return false;
    }

    async create(name, shared = false) {
        try {
            const response = await this.http.post('/api/workspaces', { name, shared });
            const workspace = await response.json();
            this.workspaces.push(workspace);
            return workspace;
        } catch (error) {
            ErrorHandler.handle(error, 'Failed to create workspace');
            return null;
        }
    }

    async delete(id) {
        if (id === 'default') return false;
        try {
            await this.http.delete(`/api/workspaces/${id}`);
            this.workspaces = this.workspaces.filter(w => w.id !== id);
            if (this.current.id === id) {
                await this.switch('default');
            }
            return true;
        } catch (error) {
            console.error('Failed to delete workspace:', error);
            return false;
        }
    }

    async rename(id, name) {
        const workspace = this.workspaces.find(w => w.id === id);
        if (workspace) {
            try {
                workspace.name = name;
                await this.http.put(`/api/workspaces/${id}`, workspace);
                return true;
            } catch (error) {
                console.error('Failed to rename workspace:', error);
                return false;
            }
        }
        return false;
    }
}
