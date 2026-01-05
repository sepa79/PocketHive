class TcpMockUI {
    constructor() {
        this.requests = [];
        this.mappings = [];
        this.currentTab = 'requests';
        this.searchTerm = '';
        this.showMatched = true;
        this.showUnmatched = true;
        this.refreshInterval = null;
        this.isRecording = false;
        this.startTime = Date.now();
        this.theme = localStorage.getItem('theme') || 'light';

        this.init();
    }

    init() {
        this.initTheme();
        this.bindEvents();
        this.loadRequests();
        this.loadMappings();
        this.loadRecordingStatus();
        this.startAutoRefresh();
        this.updateUptime();
    }

    initTheme() {
        if (this.theme === 'dark') {
            document.documentElement.classList.add('dark');
        } else {
            document.documentElement.classList.remove('dark');
        }
    }

    bindEvents() {
        // Tab navigation
        document.querySelectorAll('.nav-tab').forEach(tab => {
            tab.addEventListener('click', (e) => {
                const tabName = e.target.closest('.nav-tab').dataset.tab;
                this.switchTab(tabName);
            });
        });

        // Theme toggle
        document.getElementById('themeToggle').addEventListener('click', () => {
            this.toggleTheme();
        });

        // Search and filters
        document.getElementById('searchInput').addEventListener('input', (e) => {
            this.searchTerm = e.target.value.toLowerCase();
            this.filterRequests();
        });

        document.getElementById('showMatched').addEventListener('change', (e) => {
            this.showMatched = e.target.checked;
            this.filterRequests();
        });

        document.getElementById('showUnmatched').addEventListener('change', (e) => {
            this.showUnmatched = e.target.checked;
            this.filterRequests();
        });

        // Buttons
        document.getElementById('refreshBtn').addEventListener('click', () => {
            this.loadRequests();
        });

        document.getElementById('clearRequestsBtn').addEventListener('click', () => {
            this.clearRequests();
        });

        // Modal
        document.getElementById('closeModalBtn').addEventListener('click', () => {
            this.closeModal();
        });

        // Mappings
        const addMappingBtn = document.getElementById('addMappingBtn');
        if (addMappingBtn) {
            addMappingBtn.addEventListener('click', () => {
                // TODO: Implement add mapping modal
                alert('Add mapping functionality - to be implemented');
            });
        }

        // Test functionality
        document.getElementById('sendTestBtn').addEventListener('click', () => {
            this.sendTestMessage();
        });

        // Recording controls
        document.getElementById('recordingToggle').addEventListener('click', () => {
            this.toggleRecording();
        });

        document.getElementById('adminRecordingToggle').addEventListener('click', () => {
            this.toggleRecording();
        });

        // Admin actions
        const exportBtn = document.getElementById('exportRequestsBtn');
        const resetBtn = document.getElementById('resetAllBtn');
        const saveConfigBtn = document.getElementById('saveConfigBtn');
        const autoRefreshCheckbox = document.getElementById('autoRefresh');

        if (exportBtn) {
            exportBtn.addEventListener('click', () => {
                this.exportRequests();
            });
        }

        if (resetBtn) {
            resetBtn.addEventListener('click', () => {
                this.resetAllData();
            });
        }

        if (saveConfigBtn) {
            saveConfigBtn.addEventListener('click', () => {
                this.saveConfiguration();
            });
        }

        if (autoRefreshCheckbox) {
            autoRefreshCheckbox.addEventListener('change', (e) => {
                if (e.target.checked) {
                    this.startAutoRefresh();
                } else {
                    this.stopAutoRefresh();
                }
            });
        }
    }

    switchTab(tabName) {
        // Update nav
        document.querySelectorAll('.nav-tab').forEach(tab => {
            tab.classList.remove('active', 'border-primary-500', 'text-primary-600', 'dark:text-primary-400');
            tab.classList.add('border-transparent', 'text-gray-500', 'hover:text-gray-700', 'dark:text-gray-400', 'dark:hover:text-gray-200');
        });
        const activeTab = document.querySelector(`[data-tab="${tabName}"]`);
        activeTab.classList.add('active', 'border-primary-500', 'text-primary-600', 'dark:text-primary-400');
        activeTab.classList.remove('border-transparent', 'text-gray-500', 'hover:text-gray-700', 'dark:text-gray-400', 'dark:hover:text-gray-200');

        // Update content
        document.querySelectorAll('.tab-content').forEach(content => {
            content.classList.add('hidden');
        });
        document.getElementById(`${tabName}Tab`).classList.remove('hidden');

        this.currentTab = tabName;

        // Load data for specific tabs
        if (tabName === 'mappings') {
            this.loadMappings();
        } else if (tabName === 'admin') {
            this.loadRecordingStatus();
        }
    }

    toggleTheme() {
        this.theme = this.theme === 'light' ? 'dark' : 'light';
        localStorage.setItem('theme', this.theme);

        if (this.theme === 'dark') {
            document.documentElement.classList.add('dark');
        } else {
            document.documentElement.classList.remove('dark');
        }
    }

    async loadRequests() {
        try {
            const response = await fetch('/api/requests');
            if (response.ok) {
                this.requests = await response.json();
                this.renderRequests();
                this.updateRequestCount();
            }
        } catch (error) {
            console.error('Failed to load requests:', error);
        }
    }

    async loadMappings() {
        try {
            const response = await fetch('/api/ui/mappings');
            if (response.ok) {
                this.mappings = await response.json();
                this.renderMappings();
            }
        } catch (error) {
            console.error('Failed to load mappings:', error);
            // Show empty state if mappings fail to load
            this.mappings = [];
            this.renderMappings();
        }
    }

    renderRequests() {
        const tbody = document.getElementById('requestsTable');
        const noRequests = document.getElementById('noRequests');

        if (this.requests.length === 0) {
            tbody.innerHTML = '';
            noRequests.classList.remove('hidden');
            return;
        }

        noRequests.classList.add('hidden');
        this.filterRequests();
    }

    filterRequests() {
        const tbody = document.getElementById('requestsTable');
        const filteredRequests = this.requests.filter(request => {
            // Status filter
            const isMatched = request.matched || false;
            if (isMatched && !this.showMatched) return false;
            if (!isMatched && !this.showUnmatched) return false;

            // Search filter
            if (this.searchTerm) {
                const searchableText = [
                    request.message || '',
                    request.response || '',
                    request.timestamp || ''
                ].join(' ').toLowerCase();

                if (!searchableText.includes(this.searchTerm)) return false;
            }

            return true;
        });

        tbody.innerHTML = filteredRequests.map(request => this.renderRequestRow(request)).join('');
    }

    renderRequestRow(request) {
        const isMatched = request.matched || false;
        const statusClass = isMatched ? 'bg-green-50 dark:bg-green-900/20 border-l-4 border-green-500' : 'bg-red-50 dark:bg-red-900/20 border-l-4 border-red-500';
        const statusIcon = isMatched ? 'fa-check-circle text-green-600 dark:text-green-400' : 'fa-times-circle text-red-600 dark:text-red-400';
        const statusText = isMatched ? 'Matched' : 'Unmatched';

        const timestamp = new Date(request.timestamp).toLocaleString();
        const messagePreview = this.truncateText(request.message || '', 50);
        const responsePreview = this.truncateText(request.response || '', 50);

        return `
            <tr class="${statusClass} hover:bg-gray-50 dark:hover:bg-gray-700 cursor-pointer transition-colors duration-150" onclick="tcpMockUI.showRequestDetail('${request.id}')">
                <td class="px-6 py-4 whitespace-nowrap">
                    <div class="flex items-center space-x-2">
                        <i class="fas ${statusIcon}"></i>
                        <span class="text-sm font-medium ${isMatched ? 'text-green-800 dark:text-green-200' : 'text-red-800 dark:text-red-200'}">${statusText}</span>
                    </div>
                </td>
                <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900 dark:text-gray-100 font-mono">${timestamp}</td>
                <td class="px-6 py-4 text-sm">
                    <div class="code-block bg-gray-100 dark:bg-gray-700 p-2 rounded text-xs max-w-xs overflow-hidden">
                        <code class="text-gray-800 dark:text-gray-200">${this.highlightSearch(this.escapeHtml(messagePreview))}</code>
                    </div>
                </td>
                <td class="px-6 py-4 text-sm">
                    <div class="code-block bg-gray-100 dark:bg-gray-700 p-2 rounded text-xs max-w-xs overflow-hidden">
                        <code class="text-gray-800 dark:text-gray-200">${this.highlightSearch(this.escapeHtml(responsePreview))}</code>
                    </div>
                </td>
                <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                    <button class="text-primary-600 dark:text-primary-400 hover:text-primary-800 dark:hover:text-primary-300 transition-colors duration-150" onclick="event.stopPropagation(); tcpMockUI.showRequestDetail('${request.id}')">
                        <i class="fas fa-eye"></i>
                    </button>
                </td>
            </tr>
        `;
    }

    renderMappings() {
        const tbody = document.getElementById('mappingsTable');
        if (!tbody) return;

        if (this.mappings.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="4" class="px-6 py-12 text-center text-gray-500 dark:text-gray-400">
                        <i class="fas fa-route text-4xl mb-4 block"></i>
                        <p class="text-lg font-medium mb-2">No mappings configured</p>
                        <p class="text-sm">Add message mappings to route TCP requests</p>
                    </td>
                </tr>
            `;
            return;
        }

        tbody.innerHTML = this.mappings.map(mapping => `
            <tr class="hover:bg-gray-50 dark:hover:bg-gray-700">
                <td class="px-6 py-4 text-sm font-mono text-gray-900 dark:text-gray-100">${this.escapeHtml(mapping.pattern || 'N/A')}</td>
                <td class="px-6 py-4 text-sm font-mono text-gray-900 dark:text-gray-100">${this.escapeHtml(this.truncateText(mapping.response || '', 50))}</td>
                <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900 dark:text-gray-100">${mapping.matchCount || 0}</td>
                <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                    <button class="text-red-600 hover:text-red-900 dark:text-red-400 dark:hover:text-red-300 transition-colors duration-150">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    renderMappings() {
        const tbody = document.getElementById('mappingsTable');
        tbody.innerHTML = this.mappings.map(mapping => `
            <tr class="hover:bg-gray-50">
                <td class="px-6 py-4 text-sm font-mono text-gray-900">${mapping.pattern || 'N/A'}</td>
                <td class="px-6 py-4 text-sm font-mono text-gray-900">${this.truncateText(mapping.response || '', 50)}</td>
                <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">${mapping.matchCount || 0}</td>
                <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                    <button class="text-red-600 hover:text-red-900">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    showRequestDetail(requestId) {
        const request = this.requests.find(r => r.id === requestId);
        if (!request) return;

        const modalRequest = document.getElementById('modalRequest');
        const modalResponse = document.getElementById('modalResponse');

        modalRequest.textContent = request.message || 'No message';
        modalResponse.textContent = request.response || 'No response';

        document.getElementById('requestModal').classList.remove('hidden');
        document.getElementById('requestModal').classList.add('flex');
    }

    closeModal() {
        document.getElementById('requestModal').classList.add('hidden');
        document.getElementById('requestModal').classList.remove('flex');
    }

    async clearRequests() {
        if (!confirm('Are you sure you want to clear all requests?')) return;

        try {
            const response = await fetch('/api/requests', { method: 'DELETE' });
            if (response.ok) {
                this.requests = [];
                this.renderRequests();
                this.updateRequestCount();
            }
        } catch (error) {
            console.error('Failed to clear requests:', error);
        }
    }

    async sendTestMessage() {
        const message = document.getElementById('testMessage').value;
        const host = document.getElementById('testHost').value;
        const port = parseInt(document.getElementById('testPort').value);
        const transport = document.getElementById('testTransport').value;
        const protocol = document.getElementById('testProtocol').value;
        const format = document.getElementById('testFormat').value;
        const ssl = document.getElementById('testSsl').value === 'true';
        const responseElement = document.getElementById('testResponse');

        if (!message.trim()) {
            responseElement.textContent = 'Please enter a message to test';
            return;
        }

        responseElement.textContent = 'Sending test message...';

        try {
            const response = await fetch('/api/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message, host, port, transport, protocol, format, ssl })
            });

            const result = await response.text();
            responseElement.textContent = result;

            // Refresh requests to show the test
            setTimeout(() => this.loadRequests(), 500);
        } catch (error) {
            responseElement.textContent = `Error: ${error.message}`;
        }
    }

    updateMessageTemplate() {
        const requestType = document.getElementById('requestType').value;
        const messageField = document.getElementById('testMessage');

        const templates = {
            echo: 'ECHO Hello World',
            balance: '\u0002BALANCE_INQUIRY|ACCT123456|\u0003',
            json: '{\n  "messageId": "test123",\n  "type": "inquiry",\n  "data": {\n    "account": "123456"\n  }\n}',
            custom: ''
        };

        if (templates[requestType] !== undefined) {
            messageField.value = templates[requestType];
        }
    }

    updateRequestCount() {
        const total = this.requests.length;
        const matched = this.requests.filter(r => r.matched).length;
        const unmatched = total - matched;

        document.getElementById('requestCount').textContent =
            `${total} requests (${matched} matched, ${unmatched} unmatched)`;

        // Update admin panel
        const totalElement = document.getElementById('totalRequestsCount');
        if (totalElement) {
            totalElement.textContent = total;
        }
    }

    async loadRecordingStatus() {
        try {
            const response = await fetch('/api/enterprise/recording/status');
            if (response.ok) {
                const data = await response.json();
                this.updateRecordingUI(data.recording, data.recordedCount || 0);
            }
        } catch (error) {
            console.error('Failed to load recording status:', error);
        }
    }

    async toggleRecording() {
        try {
            const endpoint = this.isRecording ? '/api/enterprise/recording/stop' : '/api/enterprise/recording/start';
            const response = await fetch(endpoint, { method: 'POST' });

            if (response.ok) {
                const data = await response.json();
                this.updateRecordingUI(data.recording, 0); // Reset count on toggle
            }
        } catch (error) {
            console.error('Failed to toggle recording:', error);
        }
    }

    updateRecordingUI(recording, recordedCount = 0) {
        this.isRecording = recording;

        // Header recording button
        const headerBtn = document.getElementById('recordingToggle');
        const headerIndicator = document.getElementById('recordingIndicator');

        if (recording) {
            headerBtn.innerHTML = '<i class="fas fa-stop text-xs"></i><span>Stop</span>';
            headerBtn.className = 'px-3 py-1.5 bg-gray-500 hover:bg-gray-600 text-white text-sm rounded-lg transition-colors duration-200 flex items-center space-x-1';
            headerIndicator.classList.remove('hidden');
        } else {
            headerBtn.innerHTML = '<i class="fas fa-record-vinyl text-xs"></i><span>Record</span>';
            headerBtn.className = 'px-3 py-1.5 bg-red-500 hover:bg-red-600 text-white text-sm rounded-lg transition-colors duration-200 flex items-center space-x-1';
            headerIndicator.classList.add('hidden');
        }

        // Admin panel recording button
        const adminBtn = document.getElementById('adminRecordingToggle');
        const adminStatus = document.getElementById('adminRecordingStatus');
        const recordedCountElement = document.getElementById('recordedCount');

        if (adminBtn) {
            if (recording) {
                adminBtn.innerHTML = '<i class="fas fa-stop mr-2"></i>Stop';
                adminBtn.className = 'px-4 py-2 bg-gray-500 hover:bg-gray-600 text-white text-sm rounded-lg transition-colors duration-200';
                adminStatus.textContent = 'Active';
                adminStatus.className = 'text-green-600 dark:text-green-400 font-medium';
            } else {
                adminBtn.innerHTML = '<i class="fas fa-record-vinyl mr-2"></i>Start';
                adminBtn.className = 'px-4 py-2 bg-red-500 hover:bg-red-600 text-white text-sm rounded-lg transition-colors duration-200';
                adminStatus.textContent = 'Inactive';
                adminStatus.className = 'text-gray-600 dark:text-gray-400 font-medium';
            }
        }

        if (recordedCountElement) {
            recordedCountElement.textContent = recordedCount;
        }
    }

    exportRequests() {
        if (this.requests.length === 0) {
            alert('No requests to export');
            return;
        }

        const dataStr = JSON.stringify(this.requests, null, 2);
        const dataBlob = new Blob([dataStr], {type: 'application/json'});
        const url = URL.createObjectURL(dataBlob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `tcp-requests-${new Date().toISOString().split('T')[0]}.json`;
        link.click();
        URL.revokeObjectURL(url);
    }

    async resetAllData() {
        if (!confirm('Are you sure you want to reset all data? This will clear all requests and mappings.')) return;

        try {
            await fetch('/api/requests', { method: 'DELETE' });
            this.requests = [];
            this.renderRequests();
            this.updateRequestCount();
            alert('Data reset successfully');
        } catch (error) {
            console.error('Failed to reset data:', error);
            alert('Failed to reset data');
        }
    }

    saveConfiguration() {
        const requestLimitElement = document.getElementById('requestLimit');
        const autoRefreshElement = document.getElementById('autoRefresh');

        if (!requestLimitElement || !autoRefreshElement) {
            alert('Configuration elements not found');
            return;
        }

        const requestLimit = requestLimitElement.value;
        const autoRefresh = autoRefreshElement.checked;

        // Save to localStorage for now
        localStorage.setItem('tcpMockConfig', JSON.stringify({
            requestLimit: parseInt(requestLimit),
            autoRefresh
        }));

        alert('Configuration saved!');
    }

    updateUptime() {
        const uptimeElement = document.getElementById('uptime');
        if (uptimeElement) {
            const uptime = Date.now() - this.startTime;
            const hours = Math.floor(uptime / (1000 * 60 * 60));
            const minutes = Math.floor((uptime % (1000 * 60 * 60)) / (1000 * 60));
            const seconds = Math.floor((uptime % (1000 * 60)) / 1000);
            uptimeElement.textContent = `${hours}h ${minutes}m ${seconds}s`;
        }

        setTimeout(() => this.updateUptime(), 1000);
    }

    truncateText(text, maxLength) {
        if (text.length <= maxLength) return text;
        return text.substring(0, maxLength) + '...';
    }

    highlightSearch(text) {
        if (!this.searchTerm) return text;
        const regex = new RegExp(`(${this.escapeRegex(this.searchTerm)})`, 'gi');
        return text.replace(regex, '<span class="bg-yellow-200 dark:bg-yellow-600">$1</span>');
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    escapeRegex(string) {
        return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    detectFormat(content, type) {
        if (!content || content.trim() === '') return 'empty';

        // ECHO detection
        if (content.startsWith('ECHO')) return 'echo';

        // JSON detection
        try {
            JSON.parse(content);
            return 'json';
        } catch (e) {}

        // XML detection
        if (content.trim().startsWith('<') && content.trim().endsWith('>')) {
            return 'xml';
        }

        // Response-specific detection
        if (type === 'response') {
            if (content.includes('ERROR') || content.includes('INVALID')) return 'error';
            if (content.startsWith('RESP|')) return 'structured';
        }

        return 'text';
    }

    formatCode(content, type) {
        if (!content || content.trim() === '') {
            return '<span class="placeholder-text">No ' + type + '</span>';
        }

        const format = this.detectFormat(content, type);

        switch (format) {
            case 'json':
                return this.formatJson(content);
            case 'xml':
                return this.formatXml(content);
            case 'echo':
                return `<span class="text-green-300">${this.escapeHtml(content)}</span>`;
            case 'error':
                return `<span class="text-red-300">${this.escapeHtml(content)}</span>`;
            case 'structured':
                return this.formatStructured(content);
            case 'empty':
                return '<span class="placeholder-text">Empty ' + type + '</span>';
            default:
                return this.escapeHtml(content);
        }
    }

    formatCodePreview(content, type) {
        const format = this.detectFormat(content, type);
        return this.formatCode(content, type);
    }

    getCodeBlockClass(content, type) {
        const format = this.detectFormat(content, type);
        const baseClass = 'code-block rounded-lg p-4';

        switch (format) {
            case 'json':
                return baseClass + ' json-format';
            case 'xml':
                return baseClass + ' xml-format';
            case 'echo':
                return baseClass + ' echo-format';
            case 'error':
                return baseClass + ' text-format';
            default:
                return baseClass + ' text-format';
        }
    }

    formatJson(jsonString) {
        try {
            const obj = JSON.parse(jsonString);
            const formatted = JSON.stringify(obj, null, 2);
            return formatted.replace(
                /"([^"]+)":/g, '<span class="json-key">"$1"</span>:'
            ).replace(
                /: "([^"]*)"/g, ': <span class="json-string">"$1"</span>'
            ).replace(
                /: (\d+)/g, ': <span class="json-number">$1</span>'
            ).replace(
                /: (true|false)/g, ': <span class="json-boolean">$1</span>'
            );
        } catch (e) {
            return this.escapeHtml(jsonString);
        }
    }

    formatXml(xmlString) {
        return xmlString.replace(
            /<([^>]+)>/g, '<span class="xml-tag">&lt;$1&gt;</span>'
        ).replace(
            /([a-zA-Z-]+)="([^"]*)"/g, '<span class="xml-attr">$1</span>="<span class="json-string">$2</span>"'
        );
    }

    formatStructured(content) {
        return content.replace(
            /\|/g, '<span class="text-gray-400">|</span>'
        ).replace(
            /(ERROR|INVALID)/g, '<span class="text-red-300">$1</span>'
        ).replace(
            /(SUCCESS|OK)/g, '<span class="text-green-300">$1</span>'
        );
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    startAutoRefresh() {
        if (this.refreshInterval) return; // Already running

        this.refreshInterval = setInterval(() => {
            // Always refresh requests on main tab
            if (this.currentTab === 'requests') {
                this.loadRequests();
            }
            // Refresh mappings if on mappings tab
            if (this.currentTab === 'mappings') {
                this.loadMappings();
            }
            // Always refresh recording status
            this.loadRecordingStatus();
        }, 5000); // Refresh every 5 seconds
    }

    stopAutoRefresh() {
        if (this.refreshInterval) {
            clearInterval(this.refreshInterval);
            this.refreshInterval = null;
        }
    }
}

// Initialize the UI when the page loads
let tcpMockUI;
document.addEventListener('DOMContentLoaded', () => {
    tcpMockUI = new TcpMockUI();
});

// Global functions for HTML onclick handlers
function updateMessageTemplate() {
    if (tcpMockUI) {
        tcpMockUI.updateMessageTemplate();
    }
}

function sendTest() {
    if (tcpMockUI) {
        tcpMockUI.sendTestMessage();
    }
}

// Handle page visibility for auto-refresh
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        tcpMockUI?.stopAutoRefresh();
    } else {
        tcpMockUI?.startAutoRefresh();
    }
});
