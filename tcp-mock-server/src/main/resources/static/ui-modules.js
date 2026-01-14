// UI Modules Integration
class UIModules {
    constructor() {
        this.undoRedo = new UndoRedo();
        this.shortcuts = null;
        this.selectedMappings = new Set();
        this.favorites = new Set(JSON.parse(localStorage.getItem('favorites') || '[]'));
    }
    
    init(ui) {
        this.shortcuts = new KeyboardShortcuts(ui);
        this.setupDragDrop();
        this.loadFavorites();
    }
    
    applyTemplate(templateKey) {
        const template = MappingTemplates[templateKey];
        if (!template) return null;
        return { ...template, id: template.id + Date.now() };
    }
    
    validateField(field, value) {
        switch(field) {
            case 'pattern': return Validator.validateRegex(value);
            case 'priority': return Validator.validatePriority(value);
            case 'delay': return Validator.validateDelay(value);
            default: return { valid: true, message: '' };
        }
    }
    
    exportAll(mappings) {
        ImportExport.exportMappings(mappings);
    }
    
    exportSelected(mappings) {
        const selected = Array.from(this.selectedMappings);
        ImportExport.exportSelected(mappings, selected);
    }
    
    importFile(file, callback) {
        ImportExport.importMappings(file, callback);
    }
    
    setupDragDrop() {
        const dropZone = document.getElementById('mappingsTab');
        if (dropZone) {
            ImportExport.setupDragDrop(dropZone, (err, mappings) => {
                if (err) {
                    this.showNotification('Import failed: ' + err.message, 'error');
                } else {
                    this.showNotification(`Imported ${mappings.length} mappings`, 'success');
                    window.tcpMockUI?.loadMappings();
                }
            });
        }
    }
    
    toggleFavorite(id) {
        if (this.favorites.has(id)) {
            this.favorites.delete(id);
        } else {
            this.favorites.add(id);
        }
        this.saveFavorites();
    }
    
    isFavorite(id) {
        return this.favorites.has(id);
    }
    
    saveFavorites() {
        localStorage.setItem('favorites', JSON.stringify(Array.from(this.favorites)));
    }
    
    loadFavorites() {
        const saved = localStorage.getItem('favorites');
        if (saved) {
            this.favorites = new Set(JSON.parse(saved));
        }
    }
    
    showNotification(message, type = 'info') {
        const notification = document.createElement('div');
        notification.className = `notification notification-${type}`;
        notification.textContent = message;
        notification.style.cssText = 'position:fixed;top:20px;right:20px;padding:1rem;border-radius:0.5rem;z-index:1000;';
        
        if (type === 'success') notification.style.background = '#10b981';
        else if (type === 'error') notification.style.background = '#ef4444';
        else notification.style.background = '#3b82f6';
        
        notification.style.color = 'white';
        document.body.appendChild(notification);
        
        setTimeout(() => {
            notification.style.opacity = '0';
            notification.style.transition = 'opacity 0.3s';
            setTimeout(() => notification.remove(), 300);
        }, 3000);
    }
}
