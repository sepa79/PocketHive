// Performance Analytics Dashboard
class AnalyticsDashboard {
    constructor() {
        this.charts = {};
        this.init();
    }

    init() {
        this.loadData();
        this.initCharts();
        this.startRealTimeUpdates();
    }

    async loadData() {
        try {
            const [metrics, performance, errors] = await Promise.all([
                fetch('/api/metrics/detailed').then(r => r.json()),
                fetch('/api/metrics/performance').then(r => r.json()),
                fetch('/api/metrics/errors').then(r => r.json())
            ]);

            this.updateKPIs(metrics);
            this.updateCharts(performance, errors);
        } catch (error) {
            console.error('Failed to load analytics data:', error);
        }
    }

    updateKPIs(metrics) {
        document.getElementById('totalThroughput').textContent = metrics.throughput || 0;
        document.getElementById('avgResponseTime').textContent = `${metrics.avgResponseTime || 0}ms`;
        document.getElementById('successRate').textContent = `${metrics.successRate || 99.9}%`;
        document.getElementById('errorRate').textContent = `${metrics.errorRate || 0.1}%`;
        document.getElementById('peakThroughput').textContent = metrics.peakThroughput || 0;
        document.getElementById('p99Latency').textContent = `${metrics.p99Latency || 0}ms`;
    }

    initCharts() {
        this.createThroughputChart();
        this.createLatencyChart();
        this.createErrorChart();
        this.createLoadPatternChart();
        this.createMessageTypeChart();
        this.createResourceChart();
        this.createCapacityChart();
    }

    createThroughputChart() {
        const ctx = document.getElementById('throughputChart').getContext('2d');
        this.charts.throughput = new Chart(ctx, {
            type: 'line',
            data: {
                labels: this.generateTimeLabels(24),
                datasets: [{
                    label: 'Requests/sec',
                    data: this.generateThroughputData(),
                    borderColor: '#2b77ad',
                    backgroundColor: 'rgba(43, 119, 173, 0.1)',
                    fill: true,
                    tension: 0.4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: { beginAtZero: true, title: { display: true, text: 'Requests/sec' } },
                    x: { title: { display: true, text: 'Time' } }
                },
                plugins: { legend: { display: false } }
            }
        });
    }

    createLatencyChart() {
        const ctx = document.getElementById('latencyChart').getContext('2d');
        this.charts.latency = new Chart(ctx, {
            type: 'bar',
            data: {
                labels: ['<10ms', '10-50ms', '50-100ms', '100-200ms', '200-500ms', '>500ms'],
                datasets: [{
                    label: 'Request Count',
                    data: [45, 35, 15, 3, 1.5, 0.5],
                    backgroundColor: [
                        '#38a169', '#68d391', '#d69e2e', '#ed8936', '#e53e3e', '#c53030'
                    ]
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: { beginAtZero: true, title: { display: true, text: 'Percentage (%)' } }
                },
                plugins: { legend: { display: false } }
            }
        });
    }

    createErrorChart() {
        const ctx = document.getElementById('errorChart').getContext('2d');
        this.charts.error = new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: ['Success', 'Timeout', 'Connection Error', 'Parse Error', 'Other'],
                datasets: [{
                    data: [99.2, 0.3, 0.2, 0.2, 0.1],
                    backgroundColor: ['#38a169', '#d69e2e', '#e53e3e', '#ed8936', '#9f7aea']
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { position: 'bottom' }
                }
            }
        });
    }

    createLoadPatternChart() {
        const ctx = document.getElementById('loadPatternChart').getContext('2d');
        this.charts.loadPattern = new Chart(ctx, {
            type: 'line',
            data: {
                labels: this.generateTimeLabels(7, 'day'),
                datasets: [{
                    label: 'Peak Load',
                    data: this.generateLoadPatternData(),
                    borderColor: '#d69e2e',
                    backgroundColor: 'rgba(214, 158, 46, 0.1)',
                    fill: true
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: { beginAtZero: true, title: { display: true, text: 'Peak RPS' } }
                },
                plugins: { legend: { display: false } }
            }
        });
    }

    createMessageTypeChart() {
        const ctx = document.getElementById('messageTypeChart').getContext('2d');
        this.charts.messageType = new Chart(ctx, {
            type: 'bar',
            data: {
                labels: ['ISO8583_AUTH', 'ISO8583_SETTLE', 'ECHO_TEST', 'HEARTBEAT', 'CUSTOM'],
                datasets: [
                    {
                        label: 'Avg Latency (ms)',
                        data: [45, 52, 12, 8, 78],
                        backgroundColor: '#2b77ad',
                        yAxisID: 'y'
                    },
                    {
                        label: 'Throughput (RPS)',
                        data: [1200, 800, 2000, 500, 300],
                        backgroundColor: '#d4af37',
                        yAxisID: 'y1'
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: { type: 'linear', display: true, position: 'left', title: { display: true, text: 'Latency (ms)' } },
                    y1: { type: 'linear', display: true, position: 'right', title: { display: true, text: 'Throughput (RPS)' }, grid: { drawOnChartArea: false } }
                }
            }
        });
    }

    createResourceChart() {
        const ctx = document.getElementById('resourceChart').getContext('2d');
        this.charts.resource = new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: ['CPU', 'Memory', 'Network', 'Available'],
                datasets: [{
                    data: [25, 35, 15, 25],
                    backgroundColor: ['#e53e3e', '#d69e2e', '#2b77ad', '#38a169']
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { position: 'bottom' }
                }
            }
        });
    }

    createCapacityChart() {
        const ctx = document.getElementById('capacityChart').getContext('2d');
        this.charts.capacity = new Chart(ctx, {
            type: 'line',
            data: {
                labels: ['Week 1', 'Week 2', 'Week 3', 'Week 4', 'Forecast +1', 'Forecast +2'],
                datasets: [
                    {
                        label: 'Current Capacity',
                        data: [3000, 3200, 3500, 3800, 4000, 4200],
                        borderColor: '#38a169',
                        backgroundColor: 'rgba(56, 161, 105, 0.1)',
                        fill: true
                    },
                    {
                        label: 'Projected Demand',
                        data: [2800, 3100, 3400, 3900, 4300, 4800],
                        borderColor: '#e53e3e',
                        borderDash: [5, 5],
                        fill: false
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: { beginAtZero: true, title: { display: true, text: 'Requests/sec' } }
                }
            }
        });
    }

    generateTimeLabels(count, unit = 'hour') {
        const labels = [];
        const now = new Date();
        for (let i = count - 1; i >= 0; i--) {
            const time = new Date(now);
            if (unit === 'hour') {
                time.setHours(time.getHours() - i);
                labels.push(time.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
            } else if (unit === 'day') {
                time.setDate(time.getDate() - i);
                labels.push(time.toLocaleDateString([], { month: 'short', day: 'numeric' }));
            }
        }
        return labels;
    }

    generateThroughputData() {
        return Array.from({ length: 24 }, () => Math.floor(Math.random() * 2000) + 1000);
    }

    generateLoadPatternData() {
        return Array.from({ length: 7 }, () => Math.floor(Math.random() * 1000) + 2000);
    }

    updateCharts(performance, errors) {
        // Update charts with real data when available
        if (performance.throughput) {
            this.charts.throughput.data.datasets[0].data = performance.throughput;
            this.charts.throughput.update();
        }

        if (errors.distribution) {
            this.charts.error.data.datasets[0].data = errors.distribution;
            this.charts.error.update();
        }
    }

    startRealTimeUpdates() {
        setInterval(() => {
            this.loadData();
        }, 30000); // Update every 30 seconds
    }

    exportReport() {
        const reportData = {
            timestamp: new Date().toISOString(),
            kpis: {
                throughput: document.getElementById('totalThroughput').textContent,
                avgResponseTime: document.getElementById('avgResponseTime').textContent,
                successRate: document.getElementById('successRate').textContent,
                errorRate: document.getElementById('errorRate').textContent
            },
            charts: Object.keys(this.charts).map(key => ({
                name: key,
                data: this.charts[key].data
            }))
        };

        const blob = new Blob([JSON.stringify(reportData, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `tcp-mock-performance-report-${new Date().toISOString().split('T')[0]}.json`;
        a.click();
        URL.revokeObjectURL(url);
    }
}

// Export function for button
window.exportReport = function() {
    if (window.dashboard) {
        window.dashboard.exportReport();
    }
};

// Initialize dashboard
document.addEventListener('DOMContentLoaded', () => {
    window.dashboard = new AnalyticsDashboard();
});
