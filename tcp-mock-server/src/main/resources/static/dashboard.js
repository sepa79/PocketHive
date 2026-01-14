// Dashboard Module
class DashboardModule {
    constructor() {
        this.stats = { totalRequests: 0, totalMappings: 0, matchRate: 0, avgResponseTime: 0 };
    }

    async load(ui) {
        this.ui = ui;
        await this.calculateStats();
        this.render();
    }

    async calculateStats() {
        const requests = this.ui?.requests || [];
        const mappings = this.ui?.mappings || [];
        
        this.stats.totalRequests = requests.length;
        this.stats.totalMappings = mappings.length;
        this.stats.matchRate = requests.length > 0 
            ? Math.round((requests.filter(r => r.matched).length / requests.length) * 100) 
            : 0;
        this.stats.avgResponseTime = requests.length > 0
            ? Math.round(requests.reduce((sum, r) => sum + (r.responseTime || 0), 0) / requests.length)
            : 0;
    }

    render() {
        const container = document.getElementById('dashboardContent');
        if (!container) return;

        container.innerHTML = `
            <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-6">
                ${this.renderStatCard('Total Requests', this.stats.totalRequests, 'list', 'blue')}
                ${this.renderStatCard('Mappings', this.stats.totalMappings, 'route', 'green')}
                ${this.renderStatCard('Match Rate', this.stats.matchRate + '%', 'check-circle', 'purple')}
                ${this.renderStatCard('Avg Response', this.stats.avgResponseTime + 'ms', 'clock', 'orange')}
            </div>
            
            <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
                <div class="bg-white dark:bg-gray-800 rounded-xl shadow-sm border border-gray-200 dark:border-gray-700 p-6">
                    <h3 class="text-lg font-semibold text-gray-900 dark:text-white mb-4">Quick Actions</h3>
                    <div class="grid grid-cols-2 gap-3">
                        <button onclick="tcpMockUI.openMappingModal()" class="p-4 bg-primary-50 dark:bg-primary-900/20 rounded-lg hover:bg-primary-100 dark:hover:bg-primary-900/30 text-left">
                            <i class="fas fa-plus text-primary-500 text-xl mb-2"></i>
                            <p class="text-sm font-medium text-gray-900 dark:text-white">New Mapping</p>
                        </button>
                        <button onclick="tcpMockUI.openRecordingStartDialog()" class="p-4 bg-red-50 dark:bg-red-900/20 rounded-lg hover:bg-red-100 dark:hover:bg-red-900/30 text-left">
                            <i class="fas fa-record-vinyl text-red-500 text-xl mb-2"></i>
                            <p class="text-sm font-medium text-gray-900 dark:text-white">Start Recording</p>
                        </button>
                        <button onclick="tcpMockUI.switchTab('test')" class="p-4 bg-green-50 dark:bg-green-900/20 rounded-lg hover:bg-green-100 dark:hover:bg-green-900/30 text-left">
                            <i class="fas fa-vial text-green-500 text-xl mb-2"></i>
                            <p class="text-sm font-medium text-gray-900 dark:text-white">Test Console</p>
                        </button>
                        <button onclick="tcpMockUI.modules.exportAll(tcpMockUI.mappings)" class="p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg hover:bg-blue-100 dark:hover:bg-blue-900/30 text-left">
                            <i class="fas fa-download text-blue-500 text-xl mb-2"></i>
                            <p class="text-sm font-medium text-gray-900 dark:text-white">Export All</p>
                        </button>
                    </div>
                </div>
                
                <div class="bg-white dark:bg-gray-800 rounded-xl shadow-sm border border-gray-200 dark:border-gray-700 p-6">
                    <h3 class="text-lg font-semibold text-gray-900 dark:text-white mb-4">Recent Activity</h3>
                    <div id="recentActivity" class="space-y-2"></div>
                </div>
            </div>
            
            <div class="bg-white dark:bg-gray-800 rounded-xl shadow-sm border border-gray-200 dark:border-gray-700 p-6">
                <h3 class="text-lg font-semibold text-gray-900 dark:text-white mb-4">Top Mappings by Usage</h3>
                <div id="topMappings"></div>
            </div>
        `;

        this.renderRecentActivity();
        this.renderTopMappings();
    }

    renderStatCard(title, value, icon, color) {
        return `
            <div class="bg-white dark:bg-gray-800 rounded-xl shadow-sm border border-gray-200 dark:border-gray-700 p-6">
                <div class="flex items-center justify-between">
                    <div>
                        <p class="text-sm text-gray-600 dark:text-gray-400">${title}</p>
                        <p class="text-3xl font-bold text-gray-900 dark:text-white mt-2">${value}</p>
                    </div>
                    <div class="w-12 h-12 bg-${color}-100 dark:bg-${color}-900/20 rounded-lg flex items-center justify-center">
                        <i class="fas fa-${icon} text-${color}-500 text-xl"></i>
                    </div>
                </div>
            </div>
        `;
    }

    renderRecentActivity() {
        const container = document.getElementById('recentActivity');
        if (!container) return;

        const requests = (this.ui?.requests || []).slice(0, 5);
        if (requests.length === 0) {
            container.innerHTML = '<p class="text-sm text-gray-500 text-center py-4">No recent activity</p>';
            return;
        }

        container.innerHTML = requests.map(r => `
            <div class="flex items-center space-x-3 p-2 hover:bg-gray-50 dark:hover:bg-gray-700 rounded">
                <span class="badge ${r.matched ? 'badge-success' : 'badge-error'} text-xs">${r.matched ? 'OK' : 'MISS'}</span>
                <code class="text-xs text-gray-800 dark:text-gray-200 flex-1 truncate">${r.message}</code>
                <span class="text-xs text-gray-500">${new Date(r.timestamp).toLocaleTimeString()}</span>
            </div>
        `).join('');
    }

    renderTopMappings() {
        const container = document.getElementById('topMappings');
        if (!container) return;

        const mappings = (this.ui?.mappings || [])
            .filter(m => m.matchCount > 0)
            .sort((a, b) => b.matchCount - a.matchCount)
            .slice(0, 5);

        if (mappings.length === 0) {
            container.innerHTML = '<p class="text-sm text-gray-500 text-center py-4">No mappings used yet</p>';
            return;
        }

        const maxCount = mappings[0].matchCount;
        container.innerHTML = mappings.map(m => `
            <div class="mb-3">
                <div class="flex justify-between items-center mb-1">
                    <span class="text-sm font-mono text-gray-900 dark:text-white">${m.id}</span>
                    <span class="text-sm text-gray-600 dark:text-gray-400">${m.matchCount} matches</span>
                </div>
                <div class="w-full bg-gray-200 dark:bg-gray-700 rounded-full h-2">
                    <div class="bg-primary-500 h-2 rounded-full" style="width: ${(m.matchCount / maxCount) * 100}%"></div>
                </div>
            </div>
        `).join('');
    }
}
