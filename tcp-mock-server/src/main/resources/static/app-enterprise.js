// Enterprise TCP Mock Server UI
class TcpMockServerUI {
    constructor() {
        this.currentStep = 1;
        this.totalSteps = 3;
        this.init();
    }

    init() {
        this.checkFirstVisit();
        this.setupEventListeners();
        this.loadData();
        this.startPolling();
    }

    checkFirstVisit() {
        const hasVisited = localStorage.getItem('tcp-mock-visited');
        if (!hasVisited) {
            this.showTutorial();
            localStorage.setItem('tcp-mock-visited', 'true');
        }
    }

    setupEventListeners() {
        // Tutorial navigation
        window.nextStep = () => this.nextStep();
        window.prevStep = () => this.prevStep();
        window.skipTutorial = () => this.skipTutorial();
        window.completeTutorial = () => this.completeTutorial();
        window.showTutorial = () => this.showTutorial();

        // Enterprise features
        window.addMessageType = () => this.addMessageType();
        window.addMapping = () => this.addMapping();
        window.clearRequests = () => this.clearRequests();
        window.toggleRecording = () => this.toggleRecording();
        window.hotReload = () => this.hotReload();
        window.importWireMock = () => this.importWireMock();
    }

    showTutorial() {
        document.getElementById('tutorialOverlay').style.display = 'block';
        this.currentStep = 1;
        this.updateTutorialStep();
    }

    nextStep() {
        if (this.currentStep < this.totalSteps) {
            this.currentStep++;
            this.updateTutorialStep();
        }
    }

    prevStep() {
        if (this.currentStep > 1) {
            this.currentStep--;
            this.updateTutorialStep();
        }
    }

    updateTutorialStep() {
        document.querySelectorAll('.tutorial-step').forEach((step, index) => {
            step.classList.toggle('active', index + 1 === this.currentStep);
        });
    }

    skipTutorial() {
        this.hideTutorial();
    }

    completeTutorial() {
        this.hideTutorial();
        this.showWelcomeMessage();
    }

    hideTutorial() {
        document.getElementById('tutorialOverlay').style.display = 'none';
    }

    showWelcomeMessage() {
        this.showNotification('Welcome to TCP Mock Server Enterprise! 🚀', 'success');
    }

    async loadData() {
        await Promise.all([
            this.loadMetrics(),
            this.loadMessageTypes(),
            this.loadMappings(),
            this.loadRequests(),
            this.loadClusterStatus()
        ]);
    }

    async loadMetrics() {
        try {
            const response = await fetch('/api/metrics');
            const metrics = await response.json();

            document.getElementById('totalRequests').textContent = metrics.totalRequests || 0;
            document.getElementById('activeConnections').textContent = metrics.activeConnections || 0;
            document.getElementById('avgLatency').textContent = `${metrics.avgLatency || 0}ms`;
            document.getElementById('errorRate').textContent = `${metrics.errorRate || 0}%`;

            this.updateServerStatus(true);
        } catch (error) {
            console.error('Failed to load metrics:', error);
            this.updateServerStatus(false);
        }
    }

    updateServerStatus(isRunning) {
        const statusIndicator = document.getElementById('serverStatus');
        const statusText = document.getElementById('serverStatusText');

        if (isRunning) {
            statusIndicator.className = 'status-indicator status-running';
            statusText.textContent = 'Running';
        } else {
            statusIndicator.className = 'status-indicator status-stopped';
            statusText.textContent = 'Stopped';
        }
    }

    async loadMessageTypes() {
        try {
            const response = await fetch('/api/message-types');
            const messageTypes = await response.json();
            this.renderMessageTypes(messageTypes);
        } catch (error) {
            console.error('Failed to load message types:', error);
        }
    }

    renderMessageTypes(messageTypes) {
        const tbody = document.getElementById('messageTypesTable');
        tbody.innerHTML = messageTypes.map(type => `
            <tr>
                <td><code>${type.id}</code></td>
                <td>${type.name}</td>
                <td><code>${type.pattern}</code></td>
                <td><span class="badge bg-primary">${type.handler}</span></td>
                <td>
                    <button class="btn btn-sm btn-outline-primary" onclick="editMessageType('${type.id}')">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-danger" onclick="deleteMessageType('${type.id}')">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    async loadMappings() {
        try {
            const response = await fetch('/api/mappings');
            const mappings = await response.json();
            this.renderMappings(mappings);
        } catch (error) {
            console.error('Failed to load mappings:', error);
        }
    }

    renderMappings(mappings) {
        const tbody = document.getElementById('mappingsTable');
        tbody.innerHTML = mappings.map(mapping => `
            <tr>
                <td><code>${mapping.id}</code></td>
                <td><code>${mapping.pattern}</code></td>
                <td><small>${mapping.response.substring(0, 50)}...</small></td>
                <td><span class="badge bg-info">${mapping.hits || 0}</span></td>
                <td>
                    <button class="btn btn-sm btn-outline-primary" onclick="editMapping('${mapping.id}')">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-outline-danger" onclick="deleteMapping('${mapping.id}')">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    async loadRequests() {
        try {
            const response = await fetch('/api/requests');
            const requests = await response.json();
            this.renderRequests(requests);
        } catch (error) {
            console.error('Failed to load requests:', error);
        }
    }

    renderRequests(requests) {
        const tbody = document.getElementById('requestsTable');
        tbody.innerHTML = requests.slice(0, 100).map(request => `
            <tr>
                <td><small>${new Date(request.timestamp).toLocaleString()}</small></td>
                <td><code>${request.clientAddress}</code></td>
                <td><small>${request.message.substring(0, 30)}...</small></td>
                <td><small>${request.response ? request.response.substring(0, 30) + '...' : 'No response'}</small></td>
                <td><span class="badge bg-secondary">${request.latency}ms</span></td>
            </tr>
        `).join('');
    }

    async loadClusterStatus() {
        try {
            const response = await fetch('/api/enterprise/cluster/status');
            const status = await response.json();
            this.renderClusterStatus(status);
        } catch (error) {
            console.error('Failed to load cluster status:', error);
            document.getElementById('clusterStatus').innerHTML =
                '<span class="text-muted">Cluster not configured</span>';
        }
    }

    renderClusterStatus(status) {
        const container = document.getElementById('clusterStatus');
        container.innerHTML = `
            <div class="mb-2">
                <strong>Nodes:</strong> ${status.nodes.length}
                <span class="badge bg-success ms-2">${status.activeNodes} active</span>
            </div>
            <div class="small text-muted">
                Last sync: ${new Date(status.lastSync).toLocaleString()}
            </div>
        `;
    }

    async addMessageType() {
        const result = await this.showModal('Add Message Type', `
            <div class="mb-3">
                <label class="form-label">Type ID</label>
                <input type="text" class="form-control" id="typeId" placeholder="e.g., ISO8583_AUTH">
            </div>
            <div class="mb-3">
                <label class="form-label">Name</label>
                <input type="text" class="form-control" id="typeName" placeholder="e.g., Authorization Request">
            </div>
            <div class="mb-3">
                <label class="form-label">Pattern</label>
                <input type="text" class="form-control" id="typePattern" placeholder="e.g., ^0100.*">
            </div>
            <div class="mb-3">
                <label class="form-label">Handler</label>
                <select class="form-control" id="typeHandler">
                    <option value="ISO8583">ISO-8583</option>
                    <option value="ECHO">Echo</option>
                    <option value="CUSTOM">Custom</option>
                </select>
            </div>
        `);

        if (result) {
            const typeData = {
                id: document.getElementById('typeId').value,
                name: document.getElementById('typeName').value,
                pattern: document.getElementById('typePattern').value,
                handler: document.getElementById('typeHandler').value
            };

            try {
                await fetch('/api/message-types', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(typeData)
                });
                this.showNotification('Message type added successfully', 'success');
                this.loadMessageTypes();
            } catch (error) {
                this.showNotification('Failed to add message type', 'error');
            }
        }
    }

    async addMapping() {
        const result = await this.showModal('Add Mapping', `
            <div class="mb-3">
                <label class="form-label">Mapping ID</label>
                <input type="text" class="form-control" id="mappingId" placeholder="e.g., auth-success">
            </div>
            <div class="mb-3">
                <label class="form-label">Request Pattern</label>
                <input type="text" class="form-control" id="mappingPattern" placeholder="e.g., .*AUTH.*">
            </div>
            <div class="mb-3">
                <label class="form-label">Response Template</label>
                <textarea class="form-control" id="mappingResponse" rows="3" placeholder="Response template..."></textarea>
            </div>
        `);

        if (result) {
            const mappingData = {
                id: document.getElementById('mappingId').value,
                pattern: document.getElementById('mappingPattern').value,
                response: document.getElementById('mappingResponse').value
            };

            try {
                await fetch('/api/mappings', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(mappingData)
                });
                this.showNotification('Mapping added successfully', 'success');
                this.loadMappings();
            } catch (error) {
                this.showNotification('Failed to add mapping', 'error');
            }
        }
    }

    async clearRequests() {
        if (confirm('Are you sure you want to clear all request logs?')) {
            try {
                await fetch('/api/requests', { method: 'DELETE' });
                this.showNotification('Request log cleared', 'success');
                this.loadRequests();
            } catch (error) {
                this.showNotification('Failed to clear requests', 'error');
            }
        }
    }

    async toggleRecording() {
        try {
            const response = await fetch('/api/enterprise/recording/toggle', { method: 'POST' });
            const result = await response.json();

            const button = event.target;
            if (result.recording) {
                button.innerHTML = '<i class="fas fa-stop me-2"></i>Stop Recording';
                button.className = 'btn btn-danger';
                this.showNotification('Recording started', 'success');
            } else {
                button.innerHTML = '<i class="fas fa-play me-2"></i>Start Recording';
                button.className = 'btn btn-enterprise';
                this.showNotification('Recording stopped', 'info');
            }
        } catch (error) {
            this.showNotification('Failed to toggle recording', 'error');
        }
    }

    async hotReload() {
        try {
            await fetch('/api/enterprise/config/reload', { method: 'POST' });
            this.showNotification('Configuration reloaded successfully', 'success');
            this.loadData();
        } catch (error) {
            this.showNotification('Failed to reload configuration', 'error');
        }
    }

    async importWireMock() {
        const fileInput = document.getElementById('wireMockFile');
        const file = fileInput.files[0];

        if (!file) {
            this.showNotification('Please select a file to import', 'warning');
            return;
        }

        const formData = new FormData();
        formData.append('file', file);

        try {
            const response = await fetch('/api/enterprise/wiremock/import', {
                method: 'POST',
                body: formData
            });

            const result = await response.json();
            this.showNotification(`Imported ${result.mappingsCount} mappings successfully`, 'success');
            this.loadMappings();
        } catch (error) {
            this.showNotification('Failed to import WireMock configuration', 'error');
        }
    }

    showModal(title, content) {
        return new Promise((resolve) => {
            const modal = document.createElement('div');
            modal.className = 'modal fade';
            modal.innerHTML = `
                <div class="modal-dialog">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title">${title}</h5>
                            <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                        </div>
                        <div class="modal-body">
                            ${content}
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                            <button type="button" class="btn btn-primary" id="modalConfirm">Confirm</button>
                        </div>
                    </div>
                </div>
            `;

            document.body.appendChild(modal);
            const bsModal = new bootstrap.Modal(modal);

            modal.querySelector('#modalConfirm').onclick = () => {
                bsModal.hide();
                resolve(true);
            };

            modal.addEventListener('hidden.bs.modal', () => {
                document.body.removeChild(modal);
                resolve(false);
            });

            bsModal.show();
        });
    }

    showNotification(message, type = 'info') {
        const notification = document.createElement('div');
        notification.className = `alert alert-${type === 'error' ? 'danger' : type} alert-dismissible fade show position-fixed`;
        notification.style.cssText = 'top: 20px; right: 20px; z-index: 10000; min-width: 300px;';
        notification.innerHTML = `
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        `;

        document.body.appendChild(notification);

        setTimeout(() => {
            if (notification.parentNode) {
                notification.parentNode.removeChild(notification);
            }
        }, 5000);
    }

    startPolling() {
        setInterval(() => {
            this.loadMetrics();
            this.loadRequests();
        }, 5000);
    }
}

// Initialize the UI when the page loads
document.addEventListener('DOMContentLoaded', () => {
    new TcpMockServerUI();
});
