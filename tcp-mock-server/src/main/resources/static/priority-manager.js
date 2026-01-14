// Priority Manager Module
class PriorityManagerModule {
    constructor() {
        this.draggedItem = null;
    }

    detectConflicts(mappings) {
        const conflicts = [];
        for (let i = 0; i < mappings.length; i++) {
            for (let j = i + 1; j < mappings.length; j++) {
                const m1 = mappings[i];
                const m2 = mappings[j];
                if (this.patternsOverlap(m1.pattern, m2.pattern)) {
                    conflicts.push({
                        mapping1: m1.id,
                        mapping2: m2.id,
                        priority1: m1.priority,
                        priority2: m2.priority,
                        severity: m1.priority === m2.priority ? 'high' : 'medium'
                    });
                }
            }
        }
        return conflicts;
    }

    patternsOverlap(pattern1, pattern2) {
        if (pattern1 === pattern2) return true;
        try {
            const regex1 = new RegExp(pattern1);
            const regex2 = new RegExp(pattern2);
            const testStrings = ['TEST', 'ECHO', 'PAYMENT', '{"test":true}', '<xml/>'];
            for (const str of testStrings) {
                if (regex1.test(str) && regex2.test(str)) return true;
            }
        } catch (e) {
            return false;
        }
        return false;
    }

    suggestPriority(pattern, existingMappings) {
        const specificity = this.calculateSpecificity(pattern);
        const similar = existingMappings.filter(m => this.patternsOverlap(pattern, m.pattern));
        if (similar.length === 0) return 10;
        const avgPriority = similar.reduce((sum, m) => sum + m.priority, 0) / similar.length;
        return Math.round(avgPriority + (specificity > 0.7 ? 5 : -5));
    }

    calculateSpecificity(pattern) {
        let score = 0;
        if (pattern.includes('^')) score += 0.2;
        if (pattern.includes('$')) score += 0.2;
        if (pattern.includes('[')) score += 0.1;
        const literalChars = pattern.replace(/[.*+?^${}()|[\]\\]/g, '').length;
        score += Math.min(literalChars / 20, 0.5);
        return Math.min(score, 1);
    }

    enableDragDrop(tableId, onReorder) {
        const table = document.getElementById(tableId);
        if (!table) return;

        table.addEventListener('dragstart', (e) => {
            if (e.target.tagName === 'TR') {
                this.draggedItem = e.target;
                e.target.style.opacity = '0.5';
            }
        });

        table.addEventListener('dragend', (e) => {
            if (e.target.tagName === 'TR') {
                e.target.style.opacity = '1';
            }
        });

        table.addEventListener('dragover', (e) => {
            e.preventDefault();
            const target = e.target.closest('tr');
            if (target && target !== this.draggedItem) {
                const rect = target.getBoundingClientRect();
                const midpoint = rect.top + rect.height / 2;
                if (e.clientY < midpoint) {
                    target.parentNode.insertBefore(this.draggedItem, target);
                } else {
                    target.parentNode.insertBefore(this.draggedItem, target.nextSibling);
                }
            }
        });

        table.addEventListener('drop', (e) => {
            e.preventDefault();
            if (onReorder) onReorder();
        });
    }

    reorderByPriority(mappings) {
        return [...mappings].sort((a, b) => b.priority - a.priority);
    }
}
