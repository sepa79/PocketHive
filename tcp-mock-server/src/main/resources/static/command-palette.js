// Command Palette Module
class CommandPaletteModule {
    constructor() {
        this.commands = [];
        this.shortcuts = {
            'Ctrl+K': 'Open command palette',
            'Ctrl+N': 'New mapping',
            'Ctrl+S': 'Save current',
            'Ctrl+F': 'Search',
            'Ctrl+Z': 'Undo',
            'Ctrl+Shift+Z': 'Redo',
            'Ctrl+D': 'Duplicate',
            'Escape': 'Close modal',
            '?': 'Show shortcuts'
        };
    }

    init(ui) {
        this.ui = ui;
        this.buildCommands();
        this.bindKeys();
    }

    buildCommands() {
        this.commands = [
            { id: 'new-mapping', name: 'New Mapping', icon: 'plus', action: () => document.getElementById('addMappingBtn')?.click(), category: 'Mappings' },
            { id: 'new-scenario', name: 'New Scenario', icon: 'project-diagram', action: () => document.getElementById('addScenarioBtn')?.click(), category: 'Scenarios' },
            { id: 'start-recording', name: 'Start Recording', icon: 'record-vinyl', action: () => document.getElementById('recordingToggle')?.click(), category: 'Recording' },
            { id: 'recording-history', name: 'Recording History', icon: 'history', action: () => document.getElementById('recordingHistoryBtn')?.click(), category: 'Recording' },
            { id: 'clear-requests', name: 'Clear All Requests', icon: 'trash', action: () => document.getElementById('clearRequestsBtn')?.click(), category: 'Requests' },
            { id: 'export-mappings', name: 'Export All Mappings', icon: 'download', action: () => document.getElementById('exportAllBtn')?.click(), category: 'Mappings' },
            { id: 'import-mappings', name: 'Import Mappings', icon: 'upload', action: () => document.getElementById('importFile')?.click(), category: 'Mappings' },
            { id: 'toggle-theme', name: 'Toggle Dark Mode', icon: 'moon', action: () => document.getElementById('themeToggle')?.click(), category: 'Settings' },
            { id: 'settings', name: 'Open Settings', icon: 'cog', action: () => document.querySelector('[data-tab="settings"]')?.click(), category: 'Settings' },
            { id: 'docs', name: 'View Documentation', icon: 'book', action: () => document.querySelector('[data-tab="docs"]')?.click(), category: 'Help' },
            { id: 'shortcuts', name: 'Keyboard Shortcuts', icon: 'keyboard', action: () => this.showShortcuts(), category: 'Help' }
        ];
    }

    bindKeys() {
        document.addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                this.open();
            } else if (e.key === '?' && !e.ctrlKey && !e.metaKey) {
                const target = e.target;
                if (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA') {
                    e.preventDefault();
                    this.showShortcuts();
                }
            }
        });
    }

    open() {
        const modal = document.getElementById('commandPaletteModal');
        if (modal) {
            modal.classList.add('active');
            const input = document.getElementById('commandPaletteInput');
            if (input) {
                input.value = '';
                input.focus();
                input.oninput = (e) => this.renderCommands(e.target.value);
            }
            this.renderCommands('');
        }
    }

    close() {
        const modal = document.getElementById('commandPaletteModal');
        if (modal) modal.classList.remove('active');
    }

    search(query) {
        if (!query) return this.commands;
        const lower = query.toLowerCase();
        return this.commands.filter(cmd => 
            cmd.name.toLowerCase().includes(lower) || 
            cmd.category.toLowerCase().includes(lower)
        );
    }

    renderCommands(query) {
        const results = this.search(query);
        const list = document.getElementById('commandPaletteList');
        if (!list) return;

        const grouped = {};
        results.forEach(cmd => {
            if (!grouped[cmd.category]) grouped[cmd.category] = [];
            grouped[cmd.category].push(cmd);
        });

        list.innerHTML = Object.entries(grouped).map(([category, cmds]) => `
            <div class="mb-3">
                <div class="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase px-3 mb-1">${category}</div>
                ${cmds.map(cmd => `
                    <button onclick="if(tcpMockUI.core && tcpMockUI.core.commandPalette) tcpMockUI.core.commandPalette.execute('${cmd.id}')" 
                            class="w-full text-left px-3 py-2 hover:bg-gray-100 dark:hover:bg-gray-700 rounded flex items-center space-x-3">
                        <i class="fas fa-${cmd.icon} text-primary-500 w-5"></i>
                        <span class="text-gray-900 dark:text-white">${cmd.name}</span>
                    </button>
                `).join('')}
            </div>
        `).join('');
    }

    execute(id) {
        const cmd = this.commands.find(c => c.id === id);
        if (cmd) {
            this.close();
            cmd.action();
        }
    }

    showShortcuts() {
        const modal = document.getElementById('shortcutsModal');
        if (modal) modal.classList.add('active');
    }
}
