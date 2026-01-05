class TcpMockUIComplete {
    constructor() {
        this.requests = [];
        this.mappings = [];
        this.scenarios = {};
        this.verifications = [];
        this.currentTab = 'requests';
        this.editingMapping = null;
        this.theme = localStorage.getItem('theme') || 'light';
        this.init();
    }

    init() {
        this.initTheme();
        this.bindEvents();
        this.loadData();
        setInterval(() => this.loadData(), 5000);
    }

    initTheme() {
        document.documentElement.classList.toggle('dark', this.theme === 'dark');
    }

    bindEvents() {
        // Tabs
        document.querySelectorAll('.nav-tab').forEach(tab => {
            tab.addEventListener('click', (e) => this.switchTab(e.target.closest('.nav-tab').dataset.tab));
        });

        // Theme
        document.getElementById('themeToggle').addEventListener('click', () => this.toggleTheme());

        // Requests
        document.getElementById('searchInput').addEventListener('input', (e) => this.filterRequests(e.target.value));
        document.getElementById('refreshBtn').addEventListener('click', () => this.loadRequests());
        document.getElementById('clearRequestsBtn').addEventListener('click', () => this.clearRequests());

        // Mappings
        document.getElementById('addMappingBtn').addEventListener('click', () => this.openMappingModal());
        document.getElementById('closeMappingModalBtn').addEventListener('click', () => this.closeMappingModal());
        document.getElementById('cancelMappingBtn').addEventListener('click', () => this.closeMappingModal());
        document.getElementById('saveMappingBtn').addEventListener('click', () => this.saveMapping());
        
        // Response type toggle
        document.getElementById('responseType').addEventListener('change', (e) => this.toggleResponseFields(e.target.value));
        document.getElementById('advancedMatchType').addEventListener('change', (e) => {
            document.getElementById('advancedMatchFields').classList.toggle('hidden', !e.target.value);
        });

        // Scenarios
        document.getElementById('resetAllScenariosBtn').addEventListener('click', () => this.resetAllScenarios());

        // Verification
        document.getElementById('addVerificationBtn').addEventListener('click', () => this.addVerification());
        document.getElementById('runVerificationBtn').addEventListener('click', () => this.runVerification());

        // Test
        document.getElementById('sendTestBtn').addEventListener('click', () => this.sendTest());

        // Modals
        document.getElementById('closeRequestModalBtn').addEventListener('click', () => this.closeRequestModal());
    }

    switchTab(tab) {
        document.querySelectorAll('.nav-tab').forEach(t => {
            t.classList.remove('active', 'border-primary-500', 'text-primary-600', 'dark:text-primary-400');
            t.classList.add('border-transparent', 'text-gray-500');
        });
        document.querySelector(`[data-tab="${tab}"]`).classList.add('active', 'border-primary-500', 'text-primary-600', 'dark:text-primary-400');
        
        document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
        document.getElementById(`${tab}Tab`).classList.add('active');
        
        this.currentTab = tab;
        if (tab === 'scenarios') this.loadScenarios();
    }

    toggleTheme() {
        this.theme = this.theme === 'light' ? 'dark' : 'light';
        localStorage.setItem('theme', this.theme);
        document.documentElement.classList.toggle('dark', this.theme === 'dark');
    }

    async loadData() {
        if (this.currentTab === 'requests') await this.loadRequests();
        if (this.currentTab === 'mappings') await this.loadMappings();
        if (this.currentTab === 'scenarios') await this.loadScenarios();
    }

    async loadRequests() {
        try {
            const response = await fetch('/api/requests');
            this.requests = await response.json();
            this.renderRequests();
            document.getElementById('requestCount').textContent = `${this.requests.length} requests`;
        } catch (error) {
            console.error('Failed to load requests:', error);
        }
    }

    renderRequests() {
        const tbody = document.getElementById('requestsTable');
        tbody.innerHTML = this.requests.map(r => `
            <tr class="hover:bg-gray-50 dark:hover:bg-gray-700 cursor-pointer" onclick="tcpMockUI.showRequestDetail('${r.id}')">
                <td class="px-6 py-4">
                    <span class="badge ${r.matched ? 'badge-success' : 'badge-error'}">
                        ${r.matched ? 'Matched' : 'Unmatched'}
                    </span>
                </td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100 font-mono">${new Date(r.timestamp).toLocaleString()}</td>
                <td class="px-6 py-4 text-sm">
                    <code class="text-gray-800 dark:text-gray-200">${this.truncate(r.message, 50)}</code>
                </td>
                <td class="px-6 py-4 text-sm">
                    <code class="text-gray-800 dark:text-gray-200">${this.truncate(r.response, 50)}</code>
                </td>
                <td class="px-6 py-4">
                    <button class="text-primary-600 dark:text-primary-400 hover:text-primary-800" onclick="event.stopPropagation(); tcpMockUI.showRequestDetail('${r.id}')">
                        <i class="fas fa-eye"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    filterRequests(term) {
        const filtered = this.requests.filter(r => 
            r.message.toLowerCase().includes(term.toLowerCase()) ||
            r.response.toLowerCase().includes(term.toLowerCase())
        );
        const tbody = document.getElementById('requestsTable');
        tbody.innerHTML = filtered.map(r => `
            <tr class="hover:bg-gray-50 dark:hover:bg-gray-700 cursor-pointer" onclick="tcpMockUI.showRequestDetail('${r.id}')">
                <td class="px-6 py-4">
                    <span class="badge ${r.matched ? 'badge-success' : 'badge-error'}">
                        ${r.matched ? 'Matched' : 'Unmatched'}
                    </span>
                </td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100 font-mono">${new Date(r.timestamp).toLocaleString()}</td>
                <td class="px-6 py-4 text-sm"><code class="text-gray-800 dark:text-gray-200">${this.truncate(r.message, 50)}</code></td>
                <td class="px-6 py-4 text-sm"><code class="text-gray-800 dark:text-gray-200">${this.truncate(r.response, 50)}</code></td>
                <td class="px-6 py-4">
                    <button class="text-primary-600 dark:text-primary-400" onclick="event.stopPropagation(); tcpMockUI.showRequestDetail('${r.id}')">
                        <i class="fas fa-eye"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    showRequestDetail(id) {
        const request = this.requests.find(r => r.id === id);
        if (!request) return;
        document.getElementById('modalRequest').textContent = request.message;
        document.getElementById('modalResponse').textContent = request.response;
        document.getElementById('requestModal').classList.add('active');
    }

    closeRequestModal() {
        document.getElementById('requestModal').classList.remove('active');
    }

    async clearRequests() {
        if (!confirm('Clear all requests?')) return;
        try {
            await fetch('/api/requests', { method: 'DELETE' });
            this.requests = [];
            this.renderRequests();
        } catch (error) {
            console.error('Failed to clear requests:', error);
        }
    }

    async loadMappings() {
        try {
            const response = await fetch('/api/ui/mappings');
            this.mappings = await response.json();
            this.renderMappings();
        } catch (error) {
            console.error('Failed to load mappings:', error);
        }
    }

    renderMappings() {
        const tbody = document.getElementById('mappingsTable');
        tbody.innerHTML = this.mappings.map(m => `
            <tr class="hover:bg-gray-50 dark:hover:bg-gray-700">
                <td class="px-6 py-4 text-sm font-mono text-gray-900 dark:text-gray-100">${m.id}</td>
                <td class="px-6 py-4 text-sm font-mono text-gray-900 dark:text-gray-100">${this.truncate(m.pattern, 40)}</td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100">${m.priority}</td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100">${m.matchCount}</td>
                <td class="px-6 py-4">
                    ${m.hasAdvancedMatching ? '<span class="badge badge-info">Advanced</span>' : ''}
                    ${m.fixedDelayMs > 0 ? '<span class="badge badge-warning">Delay</span>' : ''}
                    ${m.scenarioName ? '<span class="badge badge-success">Scenario</span>' : ''}
                </td>
                <td class="px-6 py-4 space-x-2">
                    <button class="text-blue-600 dark:text-blue-400 hover:text-blue-800" onclick="tcpMockUI.editMapping('${m.id}')">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="text-red-600 dark:text-red-400 hover:text-red-800" onclick="tcpMockUI.deleteMapping('${m.id}')">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    openMappingModal(mapping = null) {
        this.editingMapping = mapping;
        document.getElementById('mappingModalTitle').textContent = mapping ? 'Edit Mapping' : 'Create Mapping';
        
        if (mapping) {
            document.getElementById('mappingId').value = mapping.id;
            document.getElementById('mappingPattern').value = mapping.pattern;
            document.getElementById('mappingPriority').value = mapping.priority;
            document.getElementById('mappingResponse').value = mapping.response;
            document.getElementById('responseDelimiter').value = mapping.delimiter || '\\n';
            document.getElementById('fixedDelayMs').value = mapping.fixedDelayMs || 0;
            document.getElementById('scenarioName').value = mapping.scenarioName || '';
            document.getElementById('requiredState').value = mapping.requiredState || '';
            document.getElementById('newState').value = mapping.newState || '';
            document.getElementById('mappingDescription').value = mapping.description || '';
        } else {
            document.getElementById('mappingId').value = '';
            document.getElementById('mappingPattern').value = '';
            document.getElementById('mappingPriority').value = '10';
            document.getElementById('mappingResponse').value = '';
            document.getElementById('responseDelimiter').value = '\\n';
            document.getElementById('fixedDelayMs').value = '0';
            document.getElementById('scenarioName').value = '';
            document.getElementById('requiredState').value = '';
            document.getElementById('newState').value = '';
            document.getElementById('mappingDescription').value = '';
            document.getElementById('responseType').value = 'normal';
            document.getElementById('advancedMatchType').value = '';
            this.toggleResponseFields('normal');
            document.getElementById('advancedMatchFields').classList.add('hidden');
        }
        
        document.getElementById('mappingModal').classList.add('active');
    }

    closeMappingModal() {
        document.getElementById('mappingModal').classList.remove('active');
        this.editingMapping = null;
    }

    toggleResponseFields(type) {
        document.getElementById('normalResponseFields').classList.toggle('hidden', type !== 'normal');
        document.getElementById('faultResponseFields').classList.toggle('hidden', type !== 'fault');
        document.getElementById('proxyResponseFields').classList.toggle('hidden', type !== 'proxy');
    }

    async saveMapping() {
        const responseType = document.getElementById('responseType').value;
        let responseTemplate = '';
        
        if (responseType === 'normal') {
            responseTemplate = document.getElementById('mappingResponse').value;
        } else if (responseType === 'fault') {
            const faultType = document.getElementById('faultType').value;
            responseTemplate = `{{fault:${faultType}}}`;
        } else if (responseType === 'proxy') {
            const proxyTarget = document.getElementById('proxyTarget').value;
            responseTemplate = `{{proxy:${proxyTarget}}}`;
        }

        const mapping = {
            id: document.getElementById('mappingId').value || `mapping-${Date.now()}`,
            pattern: document.getElementById('mappingPattern').value,
            response: responseTemplate,
            priority: parseInt(document.getElementById('mappingPriority').value),
            delimiter: document.getElementById('responseDelimiter').value,
            fixedDelayMs: parseInt(document.getElementById('fixedDelayMs').value) || null,
            scenarioName: document.getElementById('scenarioName').value || null,
            requiredState: document.getElementById('requiredState').value || null,
            newState: document.getElementById('newState').value || null,
            description: document.getElementById('mappingDescription').value || null
        };

        // Advanced matching
        const advMatchType = document.getElementById('advancedMatchType').value;
        if (advMatchType) {
            const expression = document.getElementById('advancedMatchExpression').value;
            const value = document.getElementById('advancedMatchValue').value;
            mapping.advancedMatching = {};
            
            if (advMatchType === 'jsonPath') {
                mapping.advancedMatching.jsonPath = { expression, equalTo: value };
            } else if (advMatchType === 'xmlPath') {
                mapping.advancedMatching.xmlPath = { expression, equalTo: value };
            } else if (advMatchType === 'length') {
                mapping.advancedMatching.length = { greaterThan: parseInt(value) };
            } else if (advMatchType === 'contains') {
                mapping.advancedMatching.contains = value;
            }
        }

        try {
            const response = await fetch('/api/ui/mappings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(mapping)
            });
            
            if (response.ok) {
                this.closeMappingModal();
                this.loadMappings();
            } else {
                alert('Failed to save mapping');
            }
        } catch (error) {
            console.error('Failed to save mapping:', error);
            alert('Failed to save mapping');
        }
    }

    async editMapping(id) {
        const mapping = this.mappings.find(m => m.id === id);
        if (mapping) this.openMappingModal(mapping);
    }

    async deleteMapping(id) {
        if (!confirm('Delete this mapping?')) return;
        try {
            await fetch(`/api/ui/mappings/${id}`, { method: 'DELETE' });
            this.loadMappings();
        } catch (error) {
            console.error('Failed to delete mapping:', error);
        }
    }

    async loadScenarios() {
        try {
            const response = await fetch('/__admin/scenarios');
            const data = await response.json();
            this.scenarios = data.scenarios || [];
            this.renderScenarios();
        } catch (error) {
            console.error('Failed to load scenarios:', error);
        }
    }

    renderScenarios() {
        const container = document.getElementById('scenariosContent');
        if (this.scenarios.length === 0) {
            container.innerHTML = '<p class="text-gray-500 dark:text-gray-400">No active scenarios</p>';
            return;
        }
        
        container.innerHTML = this.scenarios.map(s => `
            <div class="border border-gray-200 dark:border-gray-700 rounded-lg p-4">
                <div class="flex justify-between items-center">
                    <div>
                        <h4 class="font-medium text-gray-900 dark:text-white">${s.name}</h4>
                        <p class="text-sm text-gray-500 dark:text-gray-400">State: <span class="font-mono">${s.state}</span></p>
                    </div>
                    <button class="px-3 py-1 bg-orange-500 hover:bg-orange-600 text-white text-sm rounded" onclick="tcpMockUI.resetScenario('${s.name}')">
                        <i class="fas fa-redo mr-1"></i>Reset
                    </button>
                </div>
            </div>
        `).join('');
    }

    async resetScenario(name) {
        try {
            await fetch(`/__admin/scenarios/${name}/reset`, { method: 'POST' });
            this.loadScenarios();
        } catch (error) {
            console.error('Failed to reset scenario:', error);
        }
    }

    async resetAllScenarios() {
        if (!confirm('Reset all scenarios?')) return;
        try {
            await fetch('/__admin/reset', { method: 'POST' });
            this.loadScenarios();
        } catch (error) {
            console.error('Failed to reset scenarios:', error);
        }
    }

    addVerification() {
        const pattern = document.getElementById('verifyPattern').value;
        const countType = document.getElementById('verifyCountType').value;
        const count = parseInt(document.getElementById('verifyCount').value);
        
        if (!pattern) {
            alert('Pattern is required');
            return;
        }
        
        this.verifications.push({ pattern, countType, count });
        this.renderVerifications();
    }

    renderVerifications() {
        const container = document.getElementById('verificationResults');
        container.innerHTML = this.verifications.map((v, i) => `
            <div class="border border-gray-200 dark:border-gray-700 rounded-lg p-3 flex justify-between items-center">
                <div class="text-sm">
                    <code class="text-gray-900 dark:text-gray-100">${v.pattern}</code>
                    <span class="text-gray-500 dark:text-gray-400 ml-2">${v.countType} ${v.count}</span>
                    ${v.result !== undefined ? `<span class="ml-2 badge ${v.result.passed ? 'badge-success' : 'badge-error'}">${v.result.actual} actual</span>` : ''}
                </div>
                <button class="text-red-600 dark:text-red-400" onclick="tcpMockUI.removeVerification(${i})">
                    <i class="fas fa-times"></i>
                </button>
            </div>
        `).join('');
    }

    removeVerification(index) {
        this.verifications.splice(index, 1);
        this.renderVerifications();
    }

    async runVerification() {
        for (const v of this.verifications) {
            const matches = this.requests.filter(r => new RegExp(v.pattern).test(r.message));
            const actual = matches.length;
            let passed = false;
            
            if (v.countType === 'exactly') passed = actual === v.count;
            else if (v.countType === 'atLeast') passed = actual >= v.count;
            else if (v.countType === 'atMost') passed = actual <= v.count;
            
            v.result = { actual, passed };
        }
        this.renderVerifications();
    }

    async sendTest() {
        const message = document.getElementById('testMessage').value;
        const responseEl = document.getElementById('testResponse');
        
        if (!message.trim()) {
            responseEl.textContent = 'Please enter a message';
            return;
        }
        
        responseEl.textContent = 'Sending...';
        
        try {
            const response = await fetch('/api/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message })
            });
            
            const result = await response.json();
            responseEl.textContent = result.success === 'true' ? result.response : `Error: ${result.error}`;
            setTimeout(() => this.loadRequests(), 500);
        } catch (error) {
            responseEl.textContent = `Error: ${error.message}`;
        }
    }

    truncate(text, len) {
        return text && text.length > len ? text.substring(0, len) + '...' : text;
    }
}

let tcpMockUI;
document.addEventListener('DOMContentLoaded', () => {
    tcpMockUI = new TcpMockUIComplete();
});
