// Bulk Operations Module
class BulkOperationsModule {
    constructor() {
        this.selected = new Set();
    }

    toggleSelection(id) {
        if (this.selected.has(id)) {
            this.selected.delete(id);
        } else {
            this.selected.add(id);
        }
    }

    selectAll(ids) {
        ids.forEach(id => this.selected.add(id));
    }

    clearSelection() {
        this.selected.clear();
    }

    getSelected() {
        return Array.from(this.selected);
    }

    hasSelection() {
        return this.selected.size > 0;
    }

    async bulkDelete(mappings, onDelete) {
        const selected = this.getSelected();
        if (selected.length === 0) return;
        if (!confirm(`Delete ${selected.length} mappings?`)) return;
        
        for (const id of selected) {
            await onDelete(id);
        }
        this.clearSelection();
    }

    async bulkUpdatePriority(mappings, newPriority, onUpdate) {
        const selected = this.getSelected();
        if (selected.length === 0) return;
        
        for (const id of selected) {
            const mapping = mappings.find(m => m.id === id);
            if (mapping) {
                mapping.priority = newPriority;
                await onUpdate(mapping);
            }
        }
        this.clearSelection();
    }

    async bulkToggleEnabled(mappings, enabled, onUpdate) {
        const selected = this.getSelected();
        if (selected.length === 0) return;
        
        for (const id of selected) {
            const mapping = mappings.find(m => m.id === id);
            if (mapping) {
                mapping.enabled = enabled;
                await onUpdate(mapping);
            }
        }
        this.clearSelection();
    }
}
