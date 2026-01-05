class TcpMockUIUltimate {
    constructor() {
        this.requests = [];
        this.mappings = [];
        this.scenarios = [];
        this.verifications = [];
        this.currentTab = 'requests';
        this.editingMapping = null;
        this.editingScenario = null;
        this.selectedMapping = null;
        this.scenarioSearchTerm = '';
        this.theme = localStorage.getItem('theme') || 'light';
        this.modules = new UIModules();
        this.recording = new RecordingModule();
        this.journal = new RequestJournalModule();
        this.responseBuilder = new ResponseBuilderModule();
        this.priorityManager = new PriorityManagerModule();
        this.globalSettings = new GlobalSettingsModule();
        this.bulkOps = new BulkOperationsModule();
        this.mappingFilter = new MappingFilterModule();
        this.diffViewer = new DiffViewerModule();
        this.init();
    }

    init() {
        this.initTheme();
        this.bindEvents();
        this.modules.init(this);
        this.loadData();
        setInterval(() => this.loadData(), 5000);
        this.showOnboarding();
    }

    initTheme() {
        document.documentElement.classList.toggle('dark', this.theme === 'dark');
    }

    bindEvents() {
        document.querySelectorAll('.nav-tab').forEach(tab => {
            tab.addEventListener('click', (e) => this.switchTab(e.target.closest('.nav-tab').dataset.tab));
        });
        document.getElementById('themeToggle').addEventListener('click', () => this.toggleTheme());
        document.getElementById('searchInput').addEventListener('input', (e) => { this.journal.filters.pattern = e.target.value; this.renderRequests(); });
        document.getElementById('dateFrom')?.addEventListener('change', (e) => { this.journal.filters.dateFrom = e.target.value; this.renderRequests(); });
        document.getElementById('dateTo')?.addEventListener('change', (e) => { this.journal.filters.dateTo = e.target.value; this.renderRequests(); });
        document.getElementById('matchedFilter')?.addEventListener('change', (e) => { this.journal.filters.matched = e.target.value === '' ? null : e.target.value === 'true'; this.renderRequests(); });
        document.getElementById('refreshBtn').addEventListener('click', () => this.loadRequests());
        document.getElementById('clearRequestsBtn').addEventListener('click', () => this.clearRequests());
        document.getElementById('exportRequestsJSON')?.addEventListener('click', () => this.journal.exportToJSON(this.requests));
        document.getElementById('exportRequestsCSV')?.addEventListener('click', () => this.journal.exportToCSV(this.requests));
        document.getElementById('prevPage')?.addEventListener('click', () => { this.journal.page--; this.renderRequests(); });
        document.getElementById('nextPage')?.addEventListener('click', () => { this.journal.page++; this.renderRequests(); });
        document.getElementById('recordingToggle')?.addEventListener('click', () => this.toggleRecording());
        document.getElementById('addMappingBtn').addEventListener('click', () => this.openMappingModal());
        document.getElementById('closeMappingModalBtn').addEventListener('click', () => this.closeMappingModal());
        document.getElementById('cancelMappingBtn').addEventListener('click', () => this.closeMappingModal());
        document.getElementById('saveMappingBtn').addEventListener('click', () => this.saveMapping());
        document.getElementById('responseType').addEventListener('change', (e) => this.toggleResponseFields(e.target.value));
        document.getElementById('advancedMatchType').addEventListener('change', (e) => {
            document.getElementById('advancedMatchFields').classList.toggle('hidden', !e.target.value);
        });
        document.getElementById('formatJSON')?.addEventListener('click', () => this.formatResponse('json'));
        document.getElementById('formatXML')?.addEventListener('click', () => this.formatResponse('xml'));
        document.getElementById('toHex')?.addEventListener('click', () => this.formatResponse('hex'));
        document.getElementById('showVariables')?.addEventListener('click', () => this.showTemplateVars());
        document.getElementById('resetAllScenariosBtn').addEventListener('click', () => this.resetAllScenarios());
        document.getElementById('addVerificationBtn').addEventListener('click', () => this.addVerification());
        document.getElementById('runVerificationBtn').addEventListener('click', () => this.runVerification());
        document.getElementById('sendTestBtn').addEventListener('click', () => this.sendTest());
        document.getElementById('closeRequestModalBtn').addEventListener('click', () => this.closeRequestModal());
        document.getElementById('addHeaderBtn')?.addEventListener('click', () => this.addGlobalHeader());
        document.getElementById('saveSettingsBtn')?.addEventListener('click', () => this.saveSettings());
        document.getElementById('resetSettingsBtn')?.addEventListener('click', () => this.resetSettings());
        document.getElementById('templateSelector')?.addEventListener('change', (e) => this.applyTemplate(e.target.value));
        document.getElementById('exportAllBtn')?.addEventListener('click', () => this.modules.exportAll(this.mappings));
        document.getElementById('exportSelectedBtn')?.addEventListener('click', () => this.modules.exportSelected(this.mappings));
        document.getElementById('importBtn')?.addEventListener('click', () => document.getElementById('importFile').click());
        document.getElementById('importFile')?.addEventListener('change', (e) => this.handleImport(e.target.files[0]));
        document.getElementById('mappingPattern')?.addEventListener('input', (e) => this.validatePattern(e.target.value));
        document.getElementById('mappingPriority')?.addEventListener('input', (e) => this.validatePriority(e.target.value));
        document.getElementById('mappingSearch')?.addEventListener('input', (e) => { this.mappingFilter.filters.search = e.target.value; this.renderMappings(); });
        document.getElementById('priorityMin')?.addEventListener('input', (e) => { this.mappingFilter.filters.priorityMin = e.target.value ? parseInt(e.target.value) : null; this.renderMappings(); });
        document.getElementById('priorityMax')?.addEventListener('input', (e) => { this.mappingFilter.filters.priorityMax = e.target.value ? parseInt(e.target.value) : null; this.renderMappings(); });
        document.getElementById('featureFilter')?.addEventListener('change', (e) => { this.applyFeatureFilter(e.target.value); this.renderMappings(); });
        document.getElementById('clearFiltersBtn')?.addEventListener('click', () => { this.mappingFilter.reset(); this.clearFilterInputs(); this.renderMappings(); });
        document.getElementById('selectAllMappings')?.addEventListener('change', (e) => this.toggleSelectAll(e.target.checked));
        document.getElementById('bulkDeleteBtn')?.addEventListener('click', () => this.bulkDelete());
        document.getElementById('bulkPriorityBtn')?.addEventListener('click', () => this.bulkSetPriority());
        document.getElementById('clearSelectionBtn')?.addEventListener('click', () => this.clearSelection());
        document.getElementById('closeDiffModalBtn')?.addEventListener('click', () => this.closeDiffModal());
        document.getElementById('addScenarioBtn')?.addEventListener('click', () => this.openScenarioModal());
        document.getElementById('closeScenarioModalBtn')?.addEventListener('click', () => this.closeScenarioModal());
        document.getElementById('cancelScenarioBtn')?.addEventListener('click', () => this.closeScenarioModal());
        document.getElementById('saveScenarioBtn')?.addEventListener('click', () => this.saveScenario());
        document.getElementById('scenarioSearch')?.addEventListener('input', (e) => { this.scenarioSearchTerm = e.target.value; this.renderScenarios(); });
    }

    switchTab(tab) {
        document.querySelectorAll('.nav-tab').forEach(t => {
            t.classList.remove('active', 'border-primary-500', 'text-primary-600', 'dark:text-primary-400');
            t.classList.add('border-transparent', 'text-gray-500');
        });
        document.querySelector(`[data-tab="${tab}"]`).classList.add('active', 'border-primary-500', 'text-primary-600', 'dark:text-primary-400');
        document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
        const tabElement = document.getElementById(`${tab}Tab`);
        if (tabElement) {
            tabElement.classList.add('active');
        } else {
            console.error(`Tab element not found: ${tab}Tab`);
        }
        this.currentTab = tab;
        if (tab === 'scenarios') this.loadScenarios();
        if (tab === 'verification') this.renderVerifications();
        if (tab === 'test') document.getElementById('testResponse').textContent = 'Ready...';
        if (tab === 'settings') this.loadSettingsUI();
        if (tab === 'docs') this.loadDocumentation();
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
        if (this.currentTab === 'settings') this.loadSettingsUI();
        await this.updateRecordingStatus();
        await this.updateMetrics();
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
        const filtered = this.journal.filter(this.requests);
        const paginated = this.journal.paginate(filtered);
        const tbody = document.getElementById('requestsTable');
        tbody.innerHTML = paginated.items.map(r => `
            <tr class="hover:bg-gray-50 dark:hover:bg-gray-700 cursor-pointer" onclick="tcpMockUI.showRequestDetail('${r.id}')">
                <td class="px-6 py-4"><span class="badge ${r.matched ? 'badge-success' : 'badge-error'}">${r.matched ? 'Matched' : 'Unmatched'}</span></td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100 font-mono">${new Date(r.timestamp).toLocaleString()}</td>
                <td class="px-6 py-4 text-sm"><code class="text-gray-800 dark:text-gray-200">${this.truncate(r.message, 50)}</code></td>
                <td class="px-6 py-4 text-sm"><code class="text-gray-800 dark:text-gray-200">${this.truncate(r.response, 50)}</code></td>
                <td class="px-6 py-4 space-x-2">
                    <button class="text-primary-600 dark:text-primary-400" onclick="event.stopPropagation(); tcpMockUI.showRequestDetail('${r.id}')" title="View"><i class="fas fa-eye"></i></button>
                    <button class="text-green-600 dark:text-green-400" onclick="event.stopPropagation(); tcpMockUI.createMappingFromRequest('${r.id}')" title="Create Mapping"><i class="fas fa-plus-circle"></i></button>
                    <button class="text-blue-600 dark:text-blue-400" onclick="event.stopPropagation(); tcpMockUI.compareWithPrevious('${r.id}')" title="Compare"><i class="fas fa-code-compare"></i></button>
                </td>
            </tr>
        `).join('');
        document.getElementById('pageInfo').textContent = `Page ${paginated.page} of ${paginated.pages}`;
        document.getElementById('paginationInfo').textContent = `Showing ${paginated.items.length} of ${paginated.total} requests`;
        document.getElementById('prevPage').disabled = paginated.page === 1;
        document.getElementById('nextPage').disabled = paginated.page === paginated.pages;
    }

    filterRequests(term) {
        const filtered = this.requests.filter(r => 
            r.message.toLowerCase().includes(term.toLowerCase()) || r.response.toLowerCase().includes(term.toLowerCase())
        );
        const tbody = document.getElementById('requestsTable');
        tbody.innerHTML = filtered.map(r => `
            <tr class="hover:bg-gray-50 dark:hover:bg-gray-700 cursor-pointer" onclick="tcpMockUI.showRequestDetail('${r.id}')">
                <td class="px-6 py-4"><span class="badge ${r.matched ? 'badge-success' : 'badge-error'}">${r.matched ? 'Matched' : 'Unmatched'}</span></td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100 font-mono">${new Date(r.timestamp).toLocaleString()}</td>
                <td class="px-6 py-4 text-sm"><code class="text-gray-800 dark:text-gray-200">${this.truncate(r.message, 50)}</code></td>
                <td class="px-6 py-4 text-sm"><code class="text-gray-800 dark:text-gray-200">${this.truncate(r.response, 50)}</code></td>
                <td class="px-6 py-4"><button class="text-primary-600 dark:text-primary-400" onclick="event.stopPropagation(); tcpMockUI.showRequestDetail('${r.id}')"><i class="fas fa-eye"></i></button></td>
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
            this.modules.undoRedo.record('clearRequests', this.requests);
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
            this.checkPriorityConflicts();
        } catch (error) {
            console.error('Failed to load mappings:', error);
        }
    }

    renderMappings() {
        const filtered = this.mappingFilter.filter(this.mappings);
        const tbody = document.getElementById('mappingsTable');
        tbody.innerHTML = filtered.map(m => `
            <tr class="hover:bg-gray-50 dark:hover:bg-gray-700">
                <td class="px-6 py-4">
                    <input type="checkbox" class="mapping-checkbox rounded" data-id="${m.id}" ${this.bulkOps.selected.has(m.id) ? 'checked' : ''} onchange="tcpMockUI.toggleMappingSelection('${m.id}')">
                </td>
                <td class="px-6 py-4">
                    <button onclick="tcpMockUI.toggleFavorite('${m.id}')" class="mr-2">
                        <i class="fas fa-star ${this.modules.isFavorite(m.id) ? 'text-yellow-500' : 'text-gray-300'}"></i>
                    </button>
                    <span class="text-sm font-mono text-gray-900 dark:text-gray-100">${m.id}</span>
                </td>
                <td class="px-6 py-4 text-sm font-mono text-gray-900 dark:text-gray-100">${this.truncate(m.pattern, 40)}</td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100">${m.priority}</td>
                <td class="px-6 py-4 text-sm text-gray-900 dark:text-gray-100">${m.matchCount}</td>
                <td class="px-6 py-4">
                    ${m.hasAdvancedMatching ? '<span class="badge badge-info">Advanced</span>' : ''}
                    ${m.fixedDelayMs > 0 ? '<span class="badge badge-warning">Delay</span>' : ''}
                    ${m.scenarioName ? '<span class="badge badge-success">Scenario</span>' : ''}
                </td>
                <td class="px-6 py-4 space-x-2">
                    <button class="text-blue-600 dark:text-blue-400" onclick="tcpMockUI.duplicateMapping('${m.id}')" title="Duplicate (Ctrl+D)"><i class="fas fa-copy"></i></button>
                    <button class="text-green-600 dark:text-green-400" onclick="tcpMockUI.editMapping('${m.id}')"><i class="fas fa-edit"></i></button>
                    <button class="text-red-600 dark:text-red-400" onclick="tcpMockUI.deleteMapping('${m.id}')"><i class="fas fa-trash"></i></button>
                </td>
            </tr>
        `).join('');
        this.updateBulkActionsUI();
    }

    applyTemplate(templateKey) {
        if (!templateKey) return;
        const template = this.modules.applyTemplate(templateKey);
        if (template) {
            document.getElementById('mappingId').value = template.id;
            document.getElementById('mappingPattern').value = template.pattern;
            document.getElementById('mappingResponse').value = template.response;
            document.getElementById('mappingPriority').value = template.priority;
            document.getElementById('responseDelimiter').value = template.delimiter;
            document.getElementById('fixedDelayMs').value = template.fixedDelayMs || 0;
            document.getElementById('mappingDescription').value = template.description;
            if (template.advancedMatching) {
                const type = Object.keys(template.advancedMatching)[0];
                document.getElementById('advancedMatchType').value = type;
                document.getElementById('advancedMatchFields').classList.remove('hidden');
            }
        }
    }

    validatePattern(value) {
        const result = this.modules.validateField('pattern', value);
        this.showValidation('mappingPattern', result);
    }

    validatePriority(value) {
        const result = this.modules.validateField('priority', value);
        this.showValidation('mappingPriority', result);
    }

    showValidation(fieldId, result) {
        const field = document.getElementById(fieldId);
        const feedback = field.nextElementSibling || document.createElement('div');
        feedback.className = 'text-sm mt-1';
        feedback.textContent = result.message;
        feedback.style.color = result.valid ? '#10b981' : '#ef4444';
        if (!field.nextElementSibling) field.parentNode.appendChild(feedback);
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
            responseTemplate = `{{fault:${document.getElementById('faultType').value}}}`;
        } else if (responseType === 'proxy') {
            responseTemplate = `{{proxy:${document.getElementById('proxyTarget').value}}}`;
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
                this.modules.undoRedo.record('saveMapping', mapping);
                this.closeMappingModal();
                this.loadMappings();
                this.modules.showNotification('Mapping saved successfully', 'success');
            } else {
                this.modules.showNotification('Failed to save mapping', 'error');
            }
        } catch (error) {
            console.error('Failed to save mapping:', error);
            this.modules.showNotification('Failed to save mapping', 'error');
        }
    }

    async editMapping(id) {
        const mapping = this.mappings.find(m => m.id === id);
        if (mapping) {
            this.selectedMapping = id;
            this.openMappingModal(mapping);
        }
    }

    async duplicateMapping(id) {
        const mapping = this.mappings.find(m => m.id === id);
        if (mapping) {
            const duplicate = { ...mapping, id: mapping.id + '-copy-' + Date.now() };
            this.openMappingModal(duplicate);
        }
    }

    async deleteMapping(id) {
        if (!confirm('Delete this mapping?')) return;
        try {
            const mapping = this.mappings.find(m => m.id === id);
            await fetch(`/api/ui/mappings/${id}`, { method: 'DELETE' });
            this.modules.undoRedo.record('deleteMapping', mapping);
            this.loadMappings();
            this.modules.showNotification('Mapping deleted', 'success');
        } catch (error) {
            console.error('Failed to delete mapping:', error);
        }
    }

    toggleFavorite(id) {
        this.modules.toggleFavorite(id);
        this.renderMappings();
    }

    async loadScenarios() {
        try {
            const response = await fetch('/__admin/scenarios');
            const data = await response.json();
            this.scenarios = data.scenarios || [];
            this.renderScenarios();
        } catch (error) {
            console.error('Failed to load scenarios:', error);
            this.scenarios = [];
            this.renderScenarios();
        }
    }

    renderScenarios() {
        const filtered = this.scenarios.filter(s => 
            !this.scenarioSearchTerm || 
            s.name.toLowerCase().includes(this.scenarioSearchTerm.toLowerCase()) ||
            s.state.toLowerCase().includes(this.scenarioSearchTerm.toLowerCase())
        );
        
        const tbody = document.getElementById('scenariosTable');
        if (filtered.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="4" class="px-6 py-8 text-center text-gray-500 dark:text-gray-400">
                        <i class="fas fa-project-diagram text-4xl mb-3 opacity-50"></i>
                        <p>No scenarios found. Create one or add scenario fields to your mappings.</p>
                    </td>
                </tr>
            `;
            return;
        }
        
        tbody.innerHTML = filtered.map(s => {
            const relatedMappings = this.mappings.filter(m => m.scenarioName === s.name).length;
            return `
                <tr class="hover:bg-gray-50 dark:hover:bg-gray-700">
                    <td class="px-6 py-4">
                        <span class="font-medium text-gray-900 dark:text-white">${s.name}</span>
                    </td>
                    <td class="px-6 py-4">
                        <span class="badge badge-info">${s.state || 'Not Set'}</span>
                    </td>
                    <td class="px-6 py-4 text-sm text-gray-600 dark:text-gray-400">
                        ${relatedMappings} mapping${relatedMappings !== 1 ? 's' : ''}
                    </td>
                    <td class="px-6 py-4 space-x-2">
                        <button class="text-blue-600 dark:text-blue-400" onclick="tcpMockUI.editScenario('${s.name}', '${s.state}')" title="Edit State">
                            <i class="fas fa-edit"></i>
                        </button>
                        <button class="text-orange-600 dark:text-orange-400" onclick="tcpMockUI.resetScenario('${s.name}')" title="Reset">
                            <i class="fas fa-redo"></i>
                        </button>
                        <button class="text-red-600 dark:text-red-400" onclick="tcpMockUI.deleteScenario('${s.name}')" title="Delete">
                            <i class="fas fa-trash"></i>
                        </button>
                    </td>
                </tr>
            `;
        }).join('');
    }

    openScenarioModal(scenario = null) {
        this.editingScenario = scenario;
        document.getElementById('scenarioModalTitle').textContent = scenario ? 'Edit Scenario' : 'Add Scenario';
        document.getElementById('scenarioNameInput').value = scenario?.name || '';
        document.getElementById('scenarioNameInput').disabled = !!scenario;
        document.getElementById('scenarioStateInput').value = scenario?.state || 'Started';
        document.getElementById('scenarioModal').classList.add('active');
    }

    closeScenarioModal() {
        document.getElementById('scenarioModal').classList.remove('active');
        this.editingScenario = null;
    }

    async saveScenario() {
        const name = document.getElementById('scenarioNameInput').value.trim();
        const state = document.getElementById('scenarioStateInput').value.trim();
        
        if (!name) {
            this.modules.showNotification('Scenario name is required', 'error');
            return;
        }
        
        if (!state) {
            this.modules.showNotification('State is required', 'error');
            return;
        }
        
        try {
            await fetch(`/__admin/scenarios/${name}/state`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ state })
            });
            this.modules.showNotification('Scenario saved', 'success');
            this.closeScenarioModal();
            this.loadScenarios();
        } catch (error) {
            console.error('Failed to save scenario:', error);
            this.modules.showNotification('Failed to save scenario', 'error');
        }
    }

    editScenario(name, state) {
        this.openScenarioModal({ name, state });
    }

    async deleteScenario(name) {
        if (!confirm(`Delete scenario "${name}"? This will not delete related mappings.`)) return;
        try {
            await fetch(`/__admin/scenarios/${name}`, { method: 'DELETE' });
            this.modules.showNotification('Scenario deleted', 'success');
            this.loadScenarios();
        } catch (error) {
            console.error('Failed to delete scenario:', error);
            this.modules.showNotification('Failed to delete scenario', 'error');
        }
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
            this.modules.showNotification('Pattern is required', 'error');
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
                <button class="text-red-600 dark:text-red-400" onclick="tcpMockUI.removeVerification(${i})"><i class="fas fa-times"></i></button>
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

    handleImport(file) {
        if (!file) return;
        this.modules.importFile(file, (err, mappings) => {
            if (err) {
                this.modules.showNotification('Import failed: ' + err.message, 'error');
            } else {
                this.modules.showNotification(`Imported ${mappings.length} mappings`, 'success');
                this.loadMappings();
            }
        });
    }

    undo() {
        const action = this.modules.undoRedo.undo();
        if (action) {
            this.modules.showNotification(`Undo: ${action.action}`, 'info');
            this.loadMappings();
        }
    }

    redo() {
        const action = this.modules.undoRedo.redo();
        if (action) {
            this.modules.showNotification(`Redo: ${action.action}`, 'info');
            this.loadMappings();
        }
    }

    showOnboarding() {
        if (localStorage.getItem('onboarding-shown')) return;
        setTimeout(() => {
            this.modules.showNotification('Welcome! Press Ctrl+K for commands, Ctrl+N for new mapping', 'info');
            localStorage.setItem('onboarding-shown', 'true');
        }, 1000);
    }

    truncate(text, len) {
        return text && text.length > len ? text.substring(0, len) + '...' : text;
    }

    async updateRecordingStatus() {
        const status = await this.recording.getStatus();
        const btn = document.getElementById('recordingToggle');
        if (status.recording) {
            btn.className = 'px-3 py-1 text-sm rounded-lg bg-red-500 text-white';
            btn.title = `Recording: ON (${status.recordedCount} recorded)`;
            btn.innerHTML = '<i class="fas fa-circle animate-pulse"></i> REC';
        } else {
            btn.className = 'px-3 py-1 text-sm rounded-lg bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300';
            btn.title = 'Recording: OFF';
            btn.innerHTML = '<i class="fas fa-circle text-gray-400"></i> REC';
        }
    }

    async toggleRecording() {
        if (this.recording.recording) {
            await this.recording.stop();
            this.modules.showNotification('Recording stopped', 'info');
        } else {
            await this.recording.start();
            this.modules.showNotification('Recording started', 'success');
        }
        await this.updateRecordingStatus();
    }

    async createMappingFromRequest(id) {
        const request = this.requests.find(r => r.id === id);
        if (!request) return;
        const mapping = await this.recording.createMappingFromRequest(request);
        this.openMappingModal(mapping);
        this.modules.showNotification('Mapping created from request', 'success');
    }

    checkPriorityConflicts() {
        const conflicts = this.priorityManager.detectConflicts(this.mappings);
        const container = document.getElementById('priorityConflicts');
        const list = document.getElementById('conflictsList');
        if (conflicts.length > 0) {
            container.classList.remove('hidden');
            list.innerHTML = conflicts.map(c => `
                <div class="mb-1">
                    <span class="font-mono">${c.mapping1}</span> (priority ${c.priority1}) conflicts with 
                    <span class="font-mono">${c.mapping2}</span> (priority ${c.priority2})
                    <span class="badge ${c.severity === 'high' ? 'badge-error' : 'badge-warning'} ml-2">${c.severity}</span>
                </div>
            `).join('');
        } else {
            container.classList.add('hidden');
        }
    }

    formatResponse(type) {
        const textarea = document.getElementById('mappingResponse');
        const text = textarea.value;
        if (type === 'json') {
            textarea.value = this.responseBuilder.formatJSON(text);
        } else if (type === 'xml') {
            textarea.value = this.responseBuilder.formatXML(text);
        } else if (type === 'hex') {
            const preview = document.getElementById('responsePreview');
            preview.textContent = this.responseBuilder.toHex(text);
            preview.classList.remove('hidden');
        }
    }

    showTemplateVars() {
        const vars = this.responseBuilder.getTemplateVariables();
        const msg = vars.map(v => `{{${v.name}}} - ${v.desc}`).join('\n');
        alert('Available Template Variables:\n\n' + msg);
    }

    loadSettingsUI() {
        document.getElementById('defaultDelay').value = this.globalSettings.get('defaultDelay');
        document.getElementById('defaultTimeout').value = this.globalSettings.get('defaultTimeout');
        document.getElementById('logLevel').value = this.globalSettings.get('logLevel');
        document.getElementById('corsEnabled').checked = this.globalSettings.get('corsEnabled');
        this.renderGlobalHeaders();
    }

    renderGlobalHeaders() {
        const headers = this.globalSettings.get('globalHeaders');
        const list = document.getElementById('headersList');
        list.innerHTML = Object.entries(headers).map(([name, value]) => `
            <div class="flex justify-between items-center p-2 bg-gray-100 dark:bg-gray-700 rounded">
                <span class="text-sm font-mono text-gray-900 dark:text-gray-100">${name}: ${value}</span>
                <button class="text-red-600 dark:text-red-400" onclick="tcpMockUI.removeGlobalHeader('${name}')"><i class="fas fa-times"></i></button>
            </div>
        `).join('');
    }

    addGlobalHeader() {
        const name = document.getElementById('headerName').value;
        const value = document.getElementById('headerValue').value;
        if (name && value) {
            this.globalSettings.addGlobalHeader(name, value);
            document.getElementById('headerName').value = '';
            document.getElementById('headerValue').value = '';
            this.renderGlobalHeaders();
            this.modules.showNotification('Header added', 'success');
        }
    }

    removeGlobalHeader(name) {
        this.globalSettings.removeGlobalHeader(name);
        this.renderGlobalHeaders();
        this.modules.showNotification('Header removed', 'info');
    }

    saveSettings() {
        this.globalSettings.set('defaultDelay', parseInt(document.getElementById('defaultDelay').value));
        this.globalSettings.set('defaultTimeout', parseInt(document.getElementById('defaultTimeout').value));
        this.globalSettings.set('logLevel', document.getElementById('logLevel').value);
        this.globalSettings.set('corsEnabled', document.getElementById('corsEnabled').checked);
        this.modules.showNotification('Settings saved', 'success');
    }

    resetSettings() {
        if (confirm('Reset all settings to defaults?')) {
            this.globalSettings.reset();
            this.loadSettingsUI();
            this.modules.showNotification('Settings reset', 'info');
        }
    }

    toggleMappingSelection(id) {
        this.bulkOps.toggleSelection(id);
        this.updateBulkActionsUI();
    }

    toggleSelectAll(checked) {
        if (checked) {
            this.bulkOps.selectAll(this.mappings.map(m => m.id));
        } else {
            this.bulkOps.clearSelection();
        }
        this.renderMappings();
    }

    updateBulkActionsUI() {
        const count = this.bulkOps.selected.size;
        const container = document.getElementById('bulkActions');
        const countEl = document.getElementById('selectedCount');
        if (count > 0) {
            container.classList.remove('hidden');
            countEl.textContent = `${count} selected`;
        } else {
            container.classList.add('hidden');
        }
    }

    async bulkDelete() {
        await this.bulkOps.bulkDelete(this.mappings, async (id) => {
            await fetch(`/api/ui/mappings/${id}`, { method: 'DELETE' });
        });
        this.loadMappings();
        this.modules.showNotification('Mappings deleted', 'success');
    }

    async bulkSetPriority() {
        const priority = parseInt(document.getElementById('bulkPriority').value);
        if (!priority) {
            this.modules.showNotification('Enter a priority value', 'error');
            return;
        }
        await this.bulkOps.bulkUpdatePriority(this.mappings, priority, async (mapping) => {
            await fetch('/api/ui/mappings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(mapping)
            });
        });
        this.loadMappings();
        this.modules.showNotification('Priority updated', 'success');
    }

    clearSelection() {
        this.bulkOps.clearSelection();
        document.getElementById('selectAllMappings').checked = false;
        this.renderMappings();
    }

    sortMappings(field) {
        this.mappingFilter.setSortBy(field);
        this.renderMappings();
    }

    applyFeatureFilter(value) {
        this.mappingFilter.filters.hasDelay = value === 'delay' ? true : null;
        this.mappingFilter.filters.hasScenario = value === 'scenario' ? true : null;
        this.mappingFilter.filters.hasAdvanced = value === 'advanced' ? true : null;
    }

    clearFilterInputs() {
        document.getElementById('mappingSearch').value = '';
        document.getElementById('priorityMin').value = '';
        document.getElementById('priorityMax').value = '';
        document.getElementById('featureFilter').value = '';
    }

    compareWithPrevious(id) {
        const index = this.requests.findIndex(r => r.id === id);
        if (index <= 0) {
            this.modules.showNotification('No previous request to compare', 'info');
            return;
        }
        const current = this.requests[index];
        const previous = this.requests[index - 1];
        this.showDiff(previous, current);
    }

    showDiff(req1, req2) {
        const msgDiff = this.diffViewer.computeDiff(req1.message, req2.message);
        const resDiff = this.diffViewer.computeDiff(req1.response, req2.response);
        
        const content = document.getElementById('diffContent');
        content.innerHTML = `
            <div class="mb-4">
                <h5 class="font-semibold text-gray-900 dark:text-white mb-2">Message Diff</h5>
                ${this.diffViewer.renderDiff(msgDiff)}
            </div>
            <div>
                <h5 class="font-semibold text-gray-900 dark:text-white mb-2">Response Diff</h5>
                ${this.diffViewer.renderDiff(resDiff)}
            </div>
        `;
        document.getElementById('diffModal').classList.add('active');
    }

    closeDiffModal() {
        document.getElementById('diffModal').classList.remove('active');
    }

    async updateMetrics() {
        try {
            const response = await fetch('/api/metrics');
            const metrics = await response.json();
            const display = document.getElementById('metricsDisplay');
            if (display) {
                display.textContent = `${metrics.totalRequests} / ${metrics.echoRequests} / ${metrics.jsonRequests}`;
                display.title = `Total: ${metrics.totalRequests}, Echo: ${metrics.echoRequests}, JSON: ${metrics.jsonRequests}`;
            }
        } catch (error) {
            console.error('Failed to load metrics:', error);
        }
    }

    async loadDocumentation() {
        const docs = [
            { name: 'START-HERE.md', title: '🚀 Start Here', category: 'Getting Started' },
            { name: 'README-PRODUCTION.md', title: 'Production README', category: 'Getting Started' },
            { name: 'EXECUTIVE-SUMMARY.md', title: 'Executive Summary', category: 'Overview' },
            { name: 'HANDOVER.md', title: 'Handover Document', category: 'Overview' },
            { name: 'WIREMOCK-PARITY.md', title: 'WireMock Parity', category: 'Features' },
            { name: 'POLISH-FEATURES.md', title: 'Polish Features', category: 'Features' },
            { name: 'DEPLOYMENT-CHECKLIST.md', title: 'Deployment Checklist', category: 'Deployment' },
            { name: 'MIGRATION-GUIDE.md', title: 'Migration Guide', category: 'Deployment' },
            { name: 'QUICK-REFERENCE.md', title: 'Quick Reference', category: 'Reference' },
            { name: 'SCENARIO-SETUP.md', title: 'Scenario Setup', category: 'Reference' },
            { name: 'DOCUMENTATION-INDEX-FINAL.md', title: 'Documentation Index', category: 'Reference' }
        ];

        const docsList = document.getElementById('docsList');
        const categories = {};
        
        docs.forEach(doc => {
            if (!categories[doc.category]) categories[doc.category] = [];
            categories[doc.category].push(doc);
        });

        docsList.innerHTML = Object.entries(categories).map(([category, items]) => `
            <div class="mb-4">
                <h4 class="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase mb-2">${category}</h4>
                ${items.map(doc => `
                    <button onclick="tcpMockUI.loadDoc('${doc.name}')" 
                            class="w-full text-left px-3 py-2 text-sm text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 rounded mb-1">
                        ${doc.title}
                    </button>
                `).join('')}
            </div>
        `).join('');

        // Load first doc by default
        this.loadDoc('START-HERE.md');
    }

    async loadDoc(filename) {
        try {
            const response = await fetch(`/docs/${filename}`);
            if (!response.ok) throw new Error('Document not found');
            const markdown = await response.text();
            const html = marked.parse(markdown);
            document.getElementById('docContent').innerHTML = html;
        } catch (error) {
            document.getElementById('docContent').innerHTML = `
                <div class="text-center py-12">
                    <i class="fas fa-exclamation-triangle text-4xl text-yellow-500 mb-4"></i>
                    <h3 class="text-lg font-semibold text-gray-900 dark:text-white mb-2">Document Not Found</h3>
                    <p class="text-gray-600 dark:text-gray-400">The requested documentation file could not be loaded.</p>
                    <p class="text-sm text-gray-500 dark:text-gray-500 mt-2">${filename}</p>
                </div>
            `;
        }
    }
}

let tcpMockUI;
document.addEventListener('DOMContentLoaded', () => {
    tcpMockUI = new TcpMockUIUltimate();
});
