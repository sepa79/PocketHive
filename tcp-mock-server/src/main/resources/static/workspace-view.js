/**
 * Responsibility: render workspace catalogue state and dispatch explicit user actions.
 * Must not: decide default/deletion policy, isolate traffic, or fabricate server results.
 * Contract: docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
class WorkspaceView {
    constructor(workspace) {
        this.workspace = workspace;
        this.busy = false;
    }

    render() {
        const current = this.workspace.getCurrentWorkspace();
        document.getElementById('currentWorkspaceName').textContent = current?.name ?? 'Catalogue unavailable';
        const list = document.getElementById('workspaceList');
        list.replaceChildren();
        for (const workspace of this.workspace.getAll()) {
            const row = document.createElement('div');
            row.className = 'flex items-center gap-1';
            const select = this.button(workspace.name, () => this.select(workspace.id));
            select.className += ' flex-1 text-left';
            select.setAttribute('aria-pressed', String(workspace.id === current?.id));
            if (workspace.id === current?.id) select.className += ' text-primary-500';
            row.append(select, this.button('Rename', () => this.rename(workspace)));
            if (workspace.deletable) row.append(this.button('Delete', () => this.remove(workspace)));
            list.append(row);
        }
        list.append(this.button('Reload catalogue', () => this.reload()));
    }

    button(label, action) {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.className = 'px-2 py-2 text-sm rounded-lg hover:bg-gray-50 dark:hover:bg-gray-700';
        button.disabled = this.busy;
        button.addEventListener('click', action);
        return button;
    }

    async run(operation, success) {
        if (this.busy) return false;
        this.busy = true;
        this.render();
        let result;
        try {
            result = await operation();
            document.getElementById('workspaceStatus').textContent = result
                ? success : 'Request failed or its result is unknown. Reload the catalogue before retrying.';
            return result;
        } catch (error) {
            document.getElementById('workspaceStatus').textContent = 'Request failed. Reload the catalogue before retrying.';
            ErrorHandler.handle(error, 'Workspace action failed');
            return false;
        } finally {
            this.busy = false;
            this.render();
        }
    }

    async select(id) {
        return this.run(() => this.workspace.switch(id), 'Catalogue selection changed. Mappings and traffic are unchanged.');
    }

    async reload() {
        return this.run(() => this.workspace.loadWorkspaces(), 'Catalogue reloaded.');
    }

    async rename(workspace) {
        const name = window.prompt('Workspace name', workspace.name);
        if (name === null) return false;
        return this.run(() => this.workspace.rename(workspace.id, name), 'Workspace renamed.');
    }

    async remove(workspace) {
        if (!window.confirm(`Delete catalogue entry "${workspace.name}"? Mappings and traffic are unchanged.`)) return false;
        return this.run(() => this.workspace.delete(workspace.id), 'Workspace deleted.');
    }

    async create() {
        const name = document.getElementById('workspaceName').value;
        const shared = document.getElementById('workspaceShared').checked;
        const result = await this.run(() => this.workspace.create(name, shared), 'Workspace created.');
        const status = document.getElementById('workspaceCreateStatus');
        status.textContent = result ? '' : 'Could not confirm creation. Your input is preserved. Close this form and reload the catalogue before retrying.';
        if (result) document.getElementById('workspaceCreateModal').classList.remove('active');
        return result;
    }
}
