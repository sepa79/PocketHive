// Keyboard Shortcuts Module
class KeyboardShortcuts {
    constructor(ui) {
        this.ui = ui;
        this.commandHistory = [];
        this.init();
    }
    
    init() {
        document.addEventListener('keydown', (e) => this.handleKeydown(e));
    }
    
    handleKeydown(e) {
        // Ctrl/Cmd + N: New mapping
        if ((e.ctrlKey || e.metaKey) && e.key === 'n') {
            e.preventDefault();
            this.ui.openMappingModal();
            this.recordCommand('New Mapping');
        }
        
        // Ctrl/Cmd + S: Save (if modal open)
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
            e.preventDefault();
            if (document.getElementById('mappingModal').classList.contains('active')) {
                this.ui.saveMapping();
                this.recordCommand('Save Mapping');
            }
        }
        
        // Ctrl/Cmd + F: Focus search
        if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
            e.preventDefault();
            document.getElementById('searchInput')?.focus();
            this.recordCommand('Search');
        }
        
        // Ctrl/Cmd + K: Command palette
        if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
            e.preventDefault();
            this.openCommandPalette();
            this.recordCommand('Command Palette');
        }
        
        // Escape: Close modals
        if (e.key === 'Escape') {
            this.closeAllModals();
        }
        
        // Ctrl/Cmd + D: Duplicate selected
        if ((e.ctrlKey || e.metaKey) && e.key === 'd') {
            e.preventDefault();
            if (this.ui.selectedMapping) {
                this.ui.duplicateMapping(this.ui.selectedMapping);
                this.recordCommand('Duplicate Mapping');
            }
        }
        
        // Ctrl/Cmd + Z: Undo
        if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
            e.preventDefault();
            this.ui.undo();
            this.recordCommand('Undo');
        }
        
        // Ctrl/Cmd + Shift + Z: Redo
        if ((e.ctrlKey || e.metaKey) && e.key === 'z' && e.shiftKey) {
            e.preventDefault();
            this.ui.redo();
            this.recordCommand('Redo');
        }
    }
    
    closeAllModals() {
        document.querySelectorAll('.modal').forEach(m => m.classList.remove('active'));
    }
    
    openCommandPalette() {
        const palette = document.getElementById('commandPalette');
        if (palette) {
            palette.classList.add('active');
            document.getElementById('commandSearch')?.focus();
        }
    }
    
    recordCommand(command) {
        this.commandHistory.push({ command, timestamp: Date.now() });
        if (this.commandHistory.length > 50) this.commandHistory.shift();
    }
    
    getRecentCommands() {
        return this.commandHistory.slice(-10).reverse();
    }
}
