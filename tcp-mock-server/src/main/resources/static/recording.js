// Recording & Playback Module
class RecordingModule {
    constructor() {
        this.recording = false;
        this.paused = false;
        this.recordedCount = 0;
        this.sessionName = '';
        this.filter = { pattern: '', matchedOnly: false, unmatchedOnly: false };
        this.autoStop = 0;
        this.sessions = this.loadSessions();
        this.currentSession = [];
        this.lastRecordedTime = null;
    }

    loadSessions() {
        const stored = localStorage.getItem('recording-sessions');
        return stored ? JSON.parse(stored) : [];
    }

    saveSessions() {
        localStorage.setItem('recording-sessions', JSON.stringify(this.sessions));
    }

    async getStatus() {
        const res = await fetch('/api/enterprise/recording/status');
        const data = await res.json();
        this.recording = data.recording;
        this.recordedCount = data.recordedCount;
        return { ...data, paused: this.paused, sessionName: this.sessionName, lastRecordedTime: this.lastRecordedTime };
    }

    async start(options = {}) {
        this.sessionName = options.name || `Session ${new Date().toLocaleString()}`;
        this.filter = options.filter || { pattern: '', matchedOnly: false, unmatchedOnly: false };
        this.autoStop = options.autoStop || 0;
        this.currentSession = [];
        this.paused = false;
        await fetch('/api/enterprise/recording/start', { method: 'POST' });
        this.recording = true;
        return this.getStatus();
    }

    async stop() {
        await fetch('/api/enterprise/recording/stop', { method: 'POST' });
        if (this.currentSession.length > 0) {
            this.sessions.unshift({ name: this.sessionName, date: new Date().toISOString(), requests: this.currentSession, count: this.currentSession.length });
            if (this.sessions.length > 20) this.sessions = this.sessions.slice(0, 20);
            this.saveSessions();
        }
        this.recording = false;
        this.paused = false;
        return this.getStatus();
    }

    pause() {
        this.paused = true;
    }

    resume() {
        this.paused = false;
    }

    recordRequest(request) {
        if (!this.recording || this.paused) return false;
        if (this.filter.pattern && !new RegExp(this.filter.pattern).test(request.message)) return false;
        if (this.filter.matchedOnly && !request.matched) return false;
        if (this.filter.unmatchedOnly && request.matched) return false;
        this.currentSession.push(request);
        this.lastRecordedTime = new Date();
        this.recordedCount++;
        if (this.autoStop > 0 && this.recordedCount >= this.autoStop) {
            this.stop();
        }
        return true;
    }

    async createMappingFromRequest(request) {
        const variables = this.detectVariables(request.message);
        return {
            id: `recorded-${Date.now()}`,
            pattern: variables.pattern,
            response: this.detectResponseVariables(request.response),
            priority: 15,
            delimiter: '\\n',
            description: `Recorded from ${new Date(request.timestamp).toLocaleString()}`,
            _variables: variables.detected
        };
    }

    async createBatchMappings(requests) {
        const grouped = this.groupSimilarRequests(requests);
        return grouped.map(group => ({
            id: `batch-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
            pattern: group.pattern,
            response: group.response,
            priority: 15,
            delimiter: '\\n',
            description: `Batch from ${group.count} similar requests`,
            _count: group.count
        }));
    }

    detectVariables(message) {
        const detected = [];
        let pattern = message;

        // Detect UUIDs
        pattern = pattern.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, (match) => {
            detected.push({ type: 'uuid', value: match });
            return '{{uuid}}';
        });

        // Detect timestamps (ISO format)
        pattern = pattern.replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z?/g, (match) => {
            detected.push({ type: 'timestamp', value: match });
            return '{{timestamp}}';
        });

        // Detect numbers (amounts, IDs)
        pattern = pattern.replace(/\b\d{4,}\b/g, (match) => {
            detected.push({ type: 'number', value: match });
            return '{{id}}';
        });

        // Detect amounts with decimals
        pattern = pattern.replace(/\b\d+\.\d{2}\b/g, (match) => {
            detected.push({ type: 'amount', value: match });
            return '{{amount}}';
        });

        return { pattern: this.escapeRegex(pattern), detected };
    }

    detectResponseVariables(response) {
        let template = response;
        template = template.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '{{uuid}}');
        template = template.replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z?/g, '{{timestamp}}');
        return template;
    }

    groupSimilarRequests(requests) {
        const groups = new Map();
        requests.forEach(req => {
            const normalized = req.message.replace(/\d+/g, 'N').replace(/[0-9a-f-]{36}/gi, 'UUID');
            if (!groups.has(normalized)) {
                groups.set(normalized, { requests: [], pattern: '', response: req.response, count: 0 });
            }
            groups.get(normalized).requests.push(req);
            groups.get(normalized).count++;
        });

        return Array.from(groups.values()).map(group => {
            const first = group.requests[0];
            const vars = this.detectVariables(first.message);
            return { ...group, pattern: vars.pattern };
        });
    }

    loadSession(index) {
        return this.sessions[index];
    }

    deleteSession(index) {
        this.sessions.splice(index, 1);
        this.saveSessions();
    }

    exportSession(index) {
        const session = this.sessions[index];
        const blob = new Blob([JSON.stringify(session, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `recording-${session.name.replace(/[^a-z0-9]/gi, '-')}.json`;
        a.click();
        URL.revokeObjectURL(url);
    }

    escapeRegex(str) {
        return '^' + str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
    }
}
