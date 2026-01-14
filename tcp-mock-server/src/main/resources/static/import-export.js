// Import/Export Module
const ImportExport = {
    exportMappings(mappings, format = 'json') {
        const data = format === 'yaml' ? this.toYAML(mappings) : JSON.stringify(mappings, null, 2);
        const blob = new Blob([data], { type: format === 'yaml' ? 'text/yaml' : 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `tcp-mock-mappings-${new Date().toISOString().split('T')[0]}.${format}`;
        link.click();
        URL.revokeObjectURL(url);
    },
    
    exportSelected(mappings, selectedIds, format = 'json') {
        const selected = mappings.filter(m => selectedIds.includes(m.id));
        this.exportMappings(selected, format);
    },
    
    importMappings(file, callback) {
        const reader = new FileReader();
        reader.onload = (e) => {
            try {
                const content = e.target.result;
                const mappings = file.name.endsWith('.yaml') || file.name.endsWith('.yml') 
                    ? this.fromYAML(content) 
                    : JSON.parse(content);
                callback(null, Array.isArray(mappings) ? mappings : [mappings]);
            } catch (error) {
                callback(error, null);
            }
        };
        reader.readAsText(file);
    },
    
    toYAML(obj) {
        // Simple YAML conversion
        const yaml = (o, indent = 0) => {
            const pad = '  '.repeat(indent);
            if (Array.isArray(o)) {
                return o.map(item => `${pad}- ${typeof item === 'object' ? '\n' + yaml(item, indent + 1) : item}`).join('\n');
            }
            return Object.entries(o).map(([k, v]) => {
                if (typeof v === 'object' && v !== null) {
                    return `${pad}${k}:\n${yaml(v, indent + 1)}`;
                }
                return `${pad}${k}: ${v}`;
            }).join('\n');
        };
        return yaml(obj);
    },
    
    fromYAML(text) {
        // Basic YAML parsing - for production use js-yaml library
        try {
            return JSON.parse(text);
        } catch {
            throw new Error('YAML parsing requires js-yaml library');
        }
    },
    
    setupDragDrop(dropZone, callback) {
        dropZone.addEventListener('dragover', (e) => {
            e.preventDefault();
            dropZone.classList.add('drag-over');
        });
        
        dropZone.addEventListener('dragleave', () => {
            dropZone.classList.remove('drag-over');
        });
        
        dropZone.addEventListener('drop', (e) => {
            e.preventDefault();
            dropZone.classList.remove('drag-over');
            const file = e.dataTransfer.files[0];
            if (file) {
                this.importMappings(file, callback);
            }
        });
    }
};
