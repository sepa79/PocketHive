// Undo/Redo Module
class UndoRedo {
    constructor() {
        this.history = [];
        this.currentIndex = -1;
        this.maxHistory = 50;
    }
    
    record(action, data) {
        // Remove any redo history
        this.history = this.history.slice(0, this.currentIndex + 1);
        
        this.history.push({ action, data, timestamp: Date.now() });
        
        if (this.history.length > this.maxHistory) {
            this.history.shift();
        } else {
            this.currentIndex++;
        }
    }
    
    undo() {
        if (this.currentIndex < 0) return null;
        const item = this.history[this.currentIndex];
        this.currentIndex--;
        return item;
    }
    
    redo() {
        if (this.currentIndex >= this.history.length - 1) return null;
        this.currentIndex++;
        return this.history[this.currentIndex];
    }
    
    canUndo() {
        return this.currentIndex >= 0;
    }
    
    canRedo() {
        return this.currentIndex < this.history.length - 1;
    }
    
    clear() {
        this.history = [];
        this.currentIndex = -1;
    }
    
    getHistory() {
        return this.history.slice(0, this.currentIndex + 1);
    }
}
