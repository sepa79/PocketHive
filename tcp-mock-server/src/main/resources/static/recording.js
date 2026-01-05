// Recording & Playback Module
class RecordingModule {
    constructor() {
        this.recording = false;
        this.recordedCount = 0;
    }

    async getStatus() {
        const res = await fetch('/api/enterprise/recording/status');
        const data = await res.json();
        this.recording = data.recording;
        this.recordedCount = data.recordedCount;
        return data;
    }

    async start() {
        await fetch('/api/enterprise/recording/start', { method: 'POST' });
        this.recording = true;
        return this.getStatus();
    }

    async stop() {
        await fetch('/api/enterprise/recording/stop', { method: 'POST' });
        this.recording = false;
        return this.getStatus();
    }

    async createMappingFromRequest(request) {
        return {
            id: `recorded-${Date.now()}`,
            pattern: this.escapeRegex(request.message),
            response: request.response,
            priority: 15,
            delimiter: '\\n',
            description: `Recorded from ${new Date(request.timestamp).toLocaleString()}`
        };
    }

    escapeRegex(str) {
        return '^' + str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
    }
}
