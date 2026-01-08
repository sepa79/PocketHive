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
        this.testEditor = null;
        this.core = null;
        this.init();
    }

    async init() {
        this.initTheme();
        this.core = new CoreIntegration(this);
        const authenticated = await this.core.init();
        if (authenticated) {
            this.http = new HttpClient(this.core.auth);
            this.recording.setHttpClient(this.http);
            this.initTestEditor();
            this.bindEvents();
            this.modules.init(this);
            this.loadData();
            setInterval(() => {
                if (this.currentTab === 'requests') this.loadRequests();
                if (this.currentTab === 'mappings') this.loadMappings();
                this.updateRecordingStatus();
                this.updateMetrics();
            }, 5000);
        } else {
            // Still bind events for login to work
            this.bindEvents();
        }
    }

    initTheme() {
        document.documentElement.classList.toggle('dark', this.theme === 'dark');
    }

    initTestEditor() {
        const textarea = document.getElementById('testMessage');
        this.testEditor = CodeMirror(document.getElementById('testMessageEditor'), {
            value: textarea.value,
            mode: 'text/plain',
            theme: this.theme === 'dark' ? 'monokai' : 'eclipse',
            lineNumbers: true,
            lineWrapping: true,
            autoCloseBrackets: true,
            matchBrackets: true,
            styleActiveLine: true,
            indentUnit: 2,
            tabSize: 2
        });
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
        document.getElementById('loadTemplateBtn')?.addEventListener('click', () => this.loadTestTemplate());
        document.getElementById('testMessageType')?.addEventListener('change', (e) => this.updateTestTemplate(e.target.value));
        document.getElementById('testTransport')?.addEventListener('change', (e) => this.toggleTcpFields(e.target.value));
        document.getElementById('testSsl')?.addEventListener('change', (e) => this.toggleSslVerify(e.target.checked));
        document.getElementById('closeRequestModalBtn').addEventListener('click', () => this.closeRequestModal());
        document.getElementById('addHeaderBtn')?.addEventListener('click', () => this.addGlobalHeader());
        document.getElementById('saveSettingsBtn')?.addEventListener('click', () => this.saveSettings());
        document.getElementById('resetSettingsBtn')?.addEventListener('click', () => this.resetSettings());
        document.getElementById('templateSelector')?.addEventListener('change', (e) => this.applyTemplate(e.target.value));
        document.getElementById('exportAllBtn')?.addEventListener('click', () => this.modules.exportAll(this.mappings));
        document.getElementById('exportSelectedBtn')?.addEventListener('click', () => this.modules.exportSelected(this.mappings));
        document.getElementById('importBtn')?.addEventListener('click', () => document.getElementById('importFile').click());
        document.getElementById('importFile')?.addEventListener('change', (e) => this.handleImportMultiple(e.target.files));
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
        document.getElementById('recordingHistoryBtn')?.addEventListener('click', () => this.openRecordingHistory());
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
        if (tab === 'dashboard' && this.core?.dashboard) this.core.dashboard.load(this);
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
        if (this.testEditor) {
            this.testEditor.setOption('theme', this.theme === 'dark' ? 'monokai' : 'eclipse');
        }
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
            const response = await this.http.get('/api/requests');
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
            await this.http.delete('/api/requests');
            this.modules.undoRedo.record('clearRequests', this.requests);
            this.requests = [];
            this.renderRequests();
        } catch (error) {
            console.error('Failed to clear requests:', error);
        }
    }

    async loadMappings() {
        try {
            const response = await this.http.get('/api/mappings');
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
                <td class="px-6 py-4 text-sm font-mono text-gray-900 dark:text-gray-100">${this.truncate(m.requestPattern, 40)}</td>
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
            document.getElementById('mappingPattern').value = mapping.requestPattern;
            document.getElementById('mappingPriority').value = mapping.priority;
            document.getElementById('mappingResponse').value = mapping.responseTemplate;
            document.getElementById('responseDelimiter').value = mapping.responseDelimiter || '\\n';
            document.getElementById('fixedDelayMs').value = mapping.fixedDelayMs || 0;
            document.getElementById('scenarioName').value = mapping.scenarioName || '';
            document.getElementById('requiredState').value = mapping.requiredScenarioState || '';
            document.getElementById('newState').value = mapping.newScenarioState || '';
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
            requestPattern: document.getElementById('mappingPattern').value,
            responseTemplate: responseTemplate,
            priority: parseInt(document.getElementById('mappingPriority').value),
            responseDelimiter: document.getElementById('responseDelimiter').value,
            fixedDelayMs: parseInt(document.getElementById('fixedDelayMs').value) || null,
            scenarioName: document.getElementById('scenarioName').value || null,
            requiredScenarioState: document.getElementById('requiredState').value || null,
            newScenarioState: document.getElementById('newState').value || null,
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
            const response = await this.http.post('/api/mappings', mapping);
            if (response.ok) {
                this.modules.undoRedo.record('saveMapping', mapping);
                this.closeMappingModal();
                await this.loadMappings();
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
            await this.http.delete(`/api/mappings/${id}`);
            this.modules.undoRedo.record('deleteMapping', mapping);
            await this.loadMappings();
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
            const response = await this.http.get('/__admin/scenarios');
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
            const response = await this.http.put(`/__admin/scenarios/${name}/state`, { state });

            if (!response.ok) {
                throw new Error('Failed to save scenario');
            }

            this.modules.showNotification('Scenario saved', 'success');
            this.closeScenarioModal();
            await this.loadScenarios();
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
            await this.http.delete(`/__admin/scenarios/${name}`);
            this.modules.showNotification('Scenario deleted', 'success');
            await this.loadScenarios();
        } catch (error) {
            console.error('Failed to delete scenario:', error);
            this.modules.showNotification('Failed to delete scenario', 'error');
        }
    }

    async resetScenario(name) {
        try {
            await this.http.fetch(`/__admin/scenarios/${name}/reset`, { method: 'POST' });
            await this.loadScenarios();
        } catch (error) {
            console.error('Failed to reset scenario:', error);
        }
    }

    async resetAllScenarios() {
        if (!confirm('Reset all scenarios?')) return;
        try {
            await this.http.fetch('/__admin/reset', { method: 'POST' });
            await this.loadScenarios();
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
        const message = this.testEditor ? this.testEditor.getValue() : document.getElementById('testMessage').value;
        const transport = document.getElementById('testTransport')?.value || 'mock';
        const host = document.getElementById('testHost')?.value || 'localhost';
        const port = parseInt(document.getElementById('testPort')?.value || '8080');
        const delimiter = document.getElementById('testDelimiter')?.value || '\n';
        const timeout = parseInt(document.getElementById('testTimeout')?.value || '5000');
        const ssl = document.getElementById('testSsl')?.checked || false;
        const sslVerify = document.getElementById('testSslVerify')?.checked || false;
        const messageType = document.getElementById('testMessageType')?.value || 'text';
        const responseEl = document.getElementById('testResponse');
        const metricsEl = document.getElementById('performanceMetrics');

        if (!message.trim()) {
            responseEl.textContent = 'Please enter a message';
            return;
        }

        responseEl.textContent = 'Sending...';
        metricsEl.style.display = 'none';

        try {
            const payload = {
                message,
                transport,
                delimiter,
                timeout,
                encoding: messageType === 'hex' ? 'hex' : messageType === 'base64' ? 'base64' : 'utf-8'
            };

            if (transport !== 'mock') {
                payload.host = host;
                payload.port = port;
                payload.ssl = ssl;
                payload.sslVerify = sslVerify;
            }

            const response = await this.http.post('/api/test', payload);

            const result = await response.json();

            if (result.success) {
                // Show performance metrics
                if (result.connectTime !== undefined) {
                    metricsEl.style.display = 'grid';
                    document.getElementById('metricConnect').textContent = result.connectTime + 'ms';
                    document.getElementById('metricFirstByte').textContent = (result.firstByteTime || 0) + 'ms';
                    document.getElementById('metricTotal').textContent = result.totalTime + 'ms';
                    document.getElementById('metricBytes').textContent = result.bytesReceived;
                }

                // Format response
                let responseText = `Transport: ${result.transport}\n`;
                if (result.totalTime) {
                    responseText += `Duration: ${result.totalTime}ms\n`;
                }
                responseText += `\nResponse:\n${result.response}`;
                responseEl.textContent = responseText;

                this.modules.showNotification('Test completed successfully', 'success');
            } else {
                responseEl.textContent = `Error (${result.errorType || 'UNKNOWN'}):\n${result.error}`;
                this.modules.showNotification('Test failed: ' + result.error, 'error');
            }

            setTimeout(() => this.loadRequests(), 500);
        } catch (error) {
            responseEl.textContent = `Network Error:\n${error.message}`;
            this.modules.showNotification('Network error: ' + error.message, 'error');
        }
    }

    toggleTcpFields(transport) {
        const fields = document.getElementById('tcpConnectionFields');
        if (fields) {
            fields.style.display = transport !== 'mock' ? 'block' : 'none';
        }
    }

    toggleSslVerify(sslEnabled) {
        const verifyLabel = document.getElementById('sslVerifyLabel');
        if (verifyLabel) {
            verifyLabel.style.display = sslEnabled ? 'block' : 'none';
        }
    }

    updateTestTemplate(type) {
        const templates = {
            text: 'ECHO Hello World',
            json: '{\n  "type": "payment",\n  "amount": 1000,\n  "currency": "USD"\n}',
            xml: '<soap:Envelope>\n  <soap:Body>\n    <GetBalance>\n      <AccountId>12345</AccountId>\n    </GetBalance>\n  </soap:Body>\n</soap:Envelope>',
            iso8583: '0200B220000000000000000000000000000000001234567890123456000000010000',
            hex: '48656c6c6f20576f726c64',
            binary: 'SGVsbG8gV29ybGQ='
        };
        const modes = {
            text: 'text/plain',
            json: 'application/json',
            xml: 'application/xml',
            iso8583: 'text/plain',
            hex: 'text/plain',
            binary: 'text/plain'
        };
        const content = templates[type] || templates.text;
        if (this.testEditor) {
            this.testEditor.setValue(content);
            this.testEditor.setOption('mode', modes[type] || 'text/plain');
        } else {
            document.getElementById('testMessage').value = content;
        }
    }

    loadTestTemplate() {
        const type = document.getElementById('testMessageType').value;
        this.updateTestTemplate(type);
        this.modules.showNotification(`Loaded ${type} template`, 'info');
    }

    handleImportMultiple(files) {
        if (!files || files.length === 0) return;
        let imported = 0;
        let errors = 0;
        Array.from(files).forEach(file => {
            this.modules.importFile(file, async (err, mappings) => {
                if (err) {
                    errors++;
                    console.error(`Failed to import ${file.name}:`, err);
                } else {
                    imported += mappings.length;
                }
                if (imported + errors === files.length || (imported > 0 && imported + errors >= files.length)) {
                    if (errors > 0) {
                        this.modules.showNotification(`Imported ${imported} mappings (${errors} files failed)`, 'warning');
                    } else {
                        this.modules.showNotification(`Imported ${imported} mappings from ${files.length} files`, 'success');
                    }
                    await this.loadMappings();
                }
            });
        });
    }

    handleImport(file) {
        if (!file) return;
        this.modules.importFile(file, async (err, mappings) => {
            if (err) {
                this.modules.showNotification('Import failed: ' + err.message, 'error');
            } else {
                this.modules.showNotification(`Imported ${mappings.length} mappings`, 'success');
                await this.loadMappings();
            }
        });
    }

    async undo() {
        const action = this.modules.undoRedo.undo();
        if (action) {
            this.modules.showNotification(`Undo: ${action.action}`, 'info');
            await this.loadMappings();
        }
    }

    async redo() {
        const action = this.modules.undoRedo.redo();
        if (action) {
            this.modules.showNotification(`Redo: ${action.action}`, 'info');
            await this.loadMappings();
        }
    }

    logout() {
        if (this.core) this.core.logout();
    }

    async switchWorkspace(id) {
        if (this.core) await this.core.switchWorkspace(id);
    }

    async createWorkspace() {
        if (this.core) this.core.createWorkspace();
    }

    async saveWorkspace() {
        if (this.core) await this.core.saveWorkspace();
    }

    showAuditLog() {
        if (this.core) this.core.showAuditLog();
    }

    truncate(text, len) {
        return text && text.length > len ? text.substring(0, len) + '...' : text;
    }

    async updateRecordingStatus() {
        const status = await this.recording.getStatus();
        const btn = document.getElementById('recordingToggle');
        if (status.recording) {
            const state = status.paused ? 'PAUSED' : 'REC';
            const color = status.paused ? 'bg-yellow-500' : 'bg-red-500';
            btn.className = `px-3 py-1 text-sm rounded-lg ${color} text-white`;
            const lastRec = status.lastRecordedTime ? ` (last: ${new Date(status.lastRecordedTime).toLocaleTimeString()})` : '';
            btn.title = `Recording: ${status.paused ? 'PAUSED' : 'ON'} - ${status.recordedCount} recorded${lastRec}`;
            btn.innerHTML = `<i class="fas fa-circle ${status.paused ? '' : 'animate-pulse'}"></i> ${state}`;
        } else {
            btn.className = 'px-3 py-1 text-sm rounded-lg bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-300';
            btn.title = 'Recording: OFF';
            btn.innerHTML = '<i class="fas fa-circle text-gray-400"></i> REC';
        }
    }

    async toggleRecording() {
        if (this.recording.recording) {
            this.openRecordingPanel();
        } else {
            this.openRecordingStartDialog();
        }
    }

    openRecordingStartDialog() {
        const modal = document.getElementById('recordingStartModal');
        if (modal) modal.classList.add('active');
    }

    closeRecordingStartDialog() {
        const modal = document.getElementById('recordingStartModal');
        if (modal) modal.classList.remove('active');
    }

    async startRecordingWithOptions() {
        const name = document.getElementById('recordingSessionName').value || `Session ${new Date().toLocaleString()}`;
        const pattern = document.getElementById('recordingFilterPattern').value;
        const matchedOnly = document.getElementById('recordingMatchedOnly').checked;
        const unmatchedOnly = document.getElementById('recordingUnmatchedOnly').checked;
        const autoStop = parseInt(document.getElementById('recordingAutoStop').value) || 0;

        await this.recording.start({ name, filter: { pattern, matchedOnly, unmatchedOnly }, autoStop });
        this.closeRecordingStartDialog();
        this.modules.showNotification(`Recording started: ${name}`, 'success');
        await this.updateRecordingStatus();
    }

    openRecordingPanel() {
        const modal = document.getElementById('recordingPanelModal');
        if (modal) {
            this.renderRecordingPanel();
            modal.classList.add('active');
        }
    }

    closeRecordingPanel() {
        const modal = document.getElementById('recordingPanelModal');
        if (modal) modal.classList.remove('active');
    }

    renderRecordingPanel() {
        const status = this.recording;
        const current = document.getElementById('currentRecordingList');
        if (current) {
            current.innerHTML = status.currentSession.map((req, i) => `
                <div class="flex justify-between items-center p-2 bg-gray-50 dark:bg-gray-700 rounded mb-1">
                    <div class="flex-1">
                        <code class="text-xs text-gray-800 dark:text-gray-200">${this.truncate(req.message, 60)}</code>
                        <span class="text-xs text-gray-500 ml-2">${new Date(req.timestamp).toLocaleTimeString()}</span>
                    </div>
                    <button class="text-blue-600 dark:text-blue-400 text-sm" onclick="tcpMockUI.createMappingFromRecording(${i})" title="Create Mapping">
                        <i class="fas fa-plus-circle"></i>
                    </button>
                </div>
            `).join('') || '<p class="text-gray-500 text-sm text-center py-4">No requests recorded yet</p>';
        }
    }

    async pauseRecording() {
        this.recording.pause();
        await this.updateRecordingStatus();
        this.modules.showNotification('Recording paused', 'info');
    }

    async resumeRecording() {
        this.recording.resume();
        await this.updateRecordingStatus();
        this.modules.showNotification('Recording resumed', 'success');
    }

    async stopRecording() {
        await this.recording.stop();
        this.closeRecordingPanel();
        this.modules.showNotification('Recording stopped and saved', 'success');
        await this.updateRecordingStatus();
    }

    async createMappingFromRequest(id) {
        const request = this.requests.find(r => r.id === id);
        if (!request) return;
        const mapping = await this.recording.createMappingFromRequest(request);
        this.openMappingModal(mapping);
        if (mapping._variables && mapping._variables.length > 0) {
            this.modules.showNotification(`Mapping created with ${mapping._variables.length} detected variables`, 'success');
        } else {
            this.modules.showNotification('Mapping created from request', 'success');
        }
    }

    async createMappingFromRecording(index) {
        const request = this.recording.currentSession[index];
        if (!request) return;
        const mapping = await this.recording.createMappingFromRequest(request);
        this.openMappingModal(mapping);
        this.modules.showNotification('Mapping created with smart variables', 'success');
    }

    async createBatchMappings() {
        const mappings = await this.recording.createBatchMappings(this.recording.currentSession);
        if (mappings.length === 0) {
            this.modules.showNotification('No requests to convert', 'warning');
            return;
        }

        for (const mapping of mappings) {
            try {
                await this.http.post('/api/mappings', mapping);
            } catch (error) {
                console.error('Failed to save mapping:', error);
            }
        }

        this.modules.showNotification(`Created ${mappings.length} mappings from ${this.recording.currentSession.length} requests`, 'success');
        await this.loadMappings();
        this.closeRecordingPanel();
    }

    openRecordingHistory() {
        const modal = document.getElementById('recordingHistoryModal');
        if (modal) {
            this.renderRecordingHistory();
            modal.classList.add('active');
        }
    }

    closeRecordingHistory() {
        const modal = document.getElementById('recordingHistoryModal');
        if (modal) modal.classList.remove('active');
    }

    renderRecordingHistory() {
        const list = document.getElementById('recordingHistoryList');
        if (list) {
            list.innerHTML = this.recording.sessions.map((session, i) => `
                <div class="border border-gray-200 dark:border-gray-700 rounded-lg p-4 mb-3">
                    <div class="flex justify-between items-start mb-2">
                        <div>
                            <h4 class="font-semibold text-gray-900 dark:text-white">${session.name}</h4>
                            <p class="text-sm text-gray-500 dark:text-gray-400">${new Date(session.date).toLocaleString()}</p>
                        </div>
                        <span class="badge badge-info">${session.count} requests</span>
                    </div>
                    <div class="flex space-x-2 mt-3">
                        <button class="px-3 py-1 bg-blue-500 hover:bg-blue-600 text-white text-sm rounded" onclick="tcpMockUI.viewSession(${i})">
                            <i class="fas fa-eye mr-1"></i>View
                        </button>
                        <button class="px-3 py-1 bg-green-500 hover:bg-green-600 text-white text-sm rounded" onclick="tcpMockUI.replaySession(${i})">
                            <i class="fas fa-play mr-1"></i>Replay
                        </button>
                        <button class="px-3 py-1 bg-purple-500 hover:bg-purple-600 text-white text-sm rounded" onclick="tcpMockUI.exportSessionUI(${i})">
                            <i class="fas fa-download mr-1"></i>Export
                        </button>
                        <button class="px-3 py-1 bg-red-500 hover:bg-red-600 text-white text-sm rounded" onclick="tcpMockUI.deleteSessionUI(${i})">
                            <i class="fas fa-trash mr-1"></i>Delete
                        </button>
                    </div>
                </div>
            `).join('') || '<p class="text-gray-500 text-center py-8">No recording sessions yet</p>';
        }
    }

    viewSession(index) {
        const session = this.recording.loadSession(index);
        if (!session) return;
        this.closeRecordingHistory();
        const modal = document.getElementById('sessionViewModal');
        if (modal) {
            document.getElementById('sessionViewTitle').textContent = session.name;
            document.getElementById('sessionViewContent').innerHTML = session.requests.map(req => `
                <div class="border-b border-gray-200 dark:border-gray-700 py-3">
                    <div class="flex justify-between items-start mb-1">
                        <span class="badge ${req.matched ? 'badge-success' : 'badge-error'}">${req.matched ? 'Matched' : 'Unmatched'}</span>
                        <span class="text-xs text-gray-500">${new Date(req.timestamp).toLocaleString()}</span>
                    </div>
                    <div class="mt-2">
                        <p class="text-xs text-gray-600 dark:text-gray-400 mb-1">Request:</p>
                        <code class="text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded block">${req.message}</code>
                    </div>
                    <div class="mt-2">
                        <p class="text-xs text-gray-600 dark:text-gray-400 mb-1">Response:</p>
                        <code class="text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded block">${req.response}</code>
                    </div>
                </div>
            `).join('');
            modal.classList.add('active');
        }
    }

    closeSessionView() {
        const modal = document.getElementById('sessionViewModal');
        if (modal) modal.classList.remove('active');
    }

    async replaySession(index) {
        const session = this.recording.loadSession(index);
        if (!session || !confirm(`Replay ${session.count} requests from "${session.name}"?`)) return;

        let success = 0;
        for (const req of session.requests) {
            try {
                await this.http.post('/api/test', { message: req.message, transport: 'mock', delimiter: '\n' });
                success++;
                await new Promise(resolve => setTimeout(resolve, 100));
            } catch (error) {
                console.error('Replay failed:', error);
            }
        }

        this.modules.showNotification(`Replayed ${success}/${session.count} requests`, success === session.count ? 'success' : 'warning');
    }

    exportSessionUI(index) {
        this.recording.exportSession(index);
        this.modules.showNotification('Session exported', 'success');
    }

    deleteSessionUI(index) {
        const session = this.recording.sessions[index];
        if (!confirm(`Delete recording session "${session.name}"?`)) return;
        this.recording.deleteSession(index);
        this.renderRecordingHistory();
        this.modules.showNotification('Session deleted', 'info');
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
            await this.http.delete(`/api/mappings/${id}`);
        });
        await this.loadMappings();
        this.modules.showNotification('Mappings deleted', 'success');
    }

    async bulkSetPriority() {
        const priority = parseInt(document.getElementById('bulkPriority').value);
        if (!priority) {
            this.modules.showNotification('Enter a priority value', 'error');
            return;
        }
        await this.bulkOps.bulkUpdatePriority(this.mappings, priority, async (mapping) => {
            await this.http.post('/api/mappings', mapping);
        });
        await this.loadMappings();
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
            const response = await this.http.get('/api/metrics');
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
            { name: 'UI-USER-GUIDE.md', title: '📱 UI User Guide', category: 'Getting Started' },
            { name: 'README-PRODUCTION.md', title: 'Production README', category: 'Getting Started' },
            { name: 'CAPABILITIES.md', title: '⚡ Capabilities & Features', category: 'Overview' },
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
            const response = await this.http.fetch(`/docs/${filename}`);
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
