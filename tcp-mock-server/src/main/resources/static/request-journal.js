// Request Journal Module
class RequestJournalModule {
    constructor() {
        this.filters = {
            dateFrom: null,
            dateTo: null,
            matched: null,
            pattern: ''
        };
        this.page = 1;
        this.pageSize = 50;
    }

    filter(requests) {
        let filtered = [...requests];
        
        if (this.filters.dateFrom) {
            const from = new Date(this.filters.dateFrom);
            filtered = filtered.filter(r => new Date(r.timestamp) >= from);
        }
        
        if (this.filters.dateTo) {
            const to = new Date(this.filters.dateTo);
            filtered = filtered.filter(r => new Date(r.timestamp) <= to);
        }
        
        if (this.filters.matched !== null) {
            filtered = filtered.filter(r => r.matched === this.filters.matched);
        }
        
        if (this.filters.pattern) {
            const regex = new RegExp(this.filters.pattern, 'i');
            filtered = filtered.filter(r => regex.test(r.message) || regex.test(r.response));
        }
        
        return filtered;
    }

    paginate(requests) {
        const start = (this.page - 1) * this.pageSize;
        return {
            items: requests.slice(start, start + this.pageSize),
            total: requests.length,
            pages: Math.ceil(requests.length / this.pageSize),
            page: this.page
        };
    }

    exportToJSON(requests) {
        const blob = new Blob([JSON.stringify(requests, null, 2)], { type: 'application/json' });
        this.download(blob, `requests-${Date.now()}.json`);
    }

    exportToCSV(requests) {
        const headers = ['Timestamp', 'Matched', 'Message', 'Response'];
        const rows = requests.map(r => [
            new Date(r.timestamp).toISOString(),
            r.matched,
            r.message.replace(/"/g, '""'),
            r.response.replace(/"/g, '""')
        ]);
        const csv = [headers, ...rows].map(row => row.map(cell => `"${cell}"`).join(',')).join('\n');
        const blob = new Blob([csv], { type: 'text/csv' });
        this.download(blob, `requests-${Date.now()}.csv`);
    }

    download(blob, filename) {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        URL.revokeObjectURL(url);
    }

    compareRequests(req1, req2) {
        return {
            messageDiff: this.diff(req1.message, req2.message),
            responseDiff: this.diff(req1.response, req2.response),
            timeDiff: new Date(req2.timestamp) - new Date(req1.timestamp)
        };
    }

    diff(str1, str2) {
        if (str1 === str2) return { same: true };
        const lines1 = str1.split('\n');
        const lines2 = str2.split('\n');
        const changes = [];
        const maxLen = Math.max(lines1.length, lines2.length);
        for (let i = 0; i < maxLen; i++) {
            if (lines1[i] !== lines2[i]) {
                changes.push({ line: i + 1, old: lines1[i] || '', new: lines2[i] || '' });
            }
        }
        return { same: false, changes };
    }
}
