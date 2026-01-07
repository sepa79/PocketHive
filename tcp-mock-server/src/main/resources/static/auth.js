// Authentication Module - Uses Spring Security HTTP Basic Auth
class AuthModule {
    constructor() {
        this.user = this.loadUser();
        this.credentials = this.loadCredentials();
        this.sessionTimeout = 30 * 60 * 1000;
        this.lastActivity = Date.now();
        this.initSessionMonitor();
    }

    loadUser() {
        const stored = sessionStorage.getItem('auth-user');
        return stored ? JSON.parse(stored) : null;
    }

    loadCredentials() {
        const stored = sessionStorage.getItem('auth-credentials');
        return stored ? stored : null;
    }

    saveUser(user) {
        this.user = user;
        sessionStorage.setItem('auth-user', JSON.stringify(user));
    }

    saveCredentials(username, password) {
        this.credentials = btoa(`${username}:${password}`);
        sessionStorage.setItem('auth-credentials', this.credentials);
    }

    isAuthenticated() {
        return !!this.credentials && !!this.user;
    }

    async init() {
        if (this.isAuthenticated()) {
            const valid = await this.validateSession();
            if (!valid) {
                this.logout();
                return false;
            }
            return true;
        }
        return false;
    }

    async validateSession() {
        try {
            const response = await fetch('/api/requests', {
                headers: { 'Authorization': `Basic ${this.credentials}` }
            });
            return response.ok;
        } catch {
            return false;
        }
    }

    async login(username, password) {
        try {
            const credentials = btoa(`${username}:${password}`);
            const response = await fetch('/api/requests', {
                headers: { 'Authorization': `Basic ${credentials}` }
            });
            
            if (response.ok) {
                const user = {
                    username,
                    role: 'admin',
                    avatar: `https://ui-avatars.com/api/?name=${encodeURIComponent(username)}&background=f59e0b&color=fff`
                };
                this.saveUser(user);
                this.saveCredentials(username, password);
                this.lastActivity = Date.now();
                this.addAuditEntry('login', `User ${username} logged in`);
                return true;
            }
            return false;
        } catch (error) {
            console.error('Login failed:', error);
            return false;
        }
    }

    logout() {
        if (this.user) {
            this.addAuditEntry('logout', `User ${this.user.username} logged out`);
        }
        this.user = null;
        this.credentials = null;
        sessionStorage.clear();
    }

    getCurrentUser() {
        return this.user;
    }

    getAuthHeader() {
        return this.credentials ? { 'Authorization': `Basic ${this.credentials}` } : {};
    }

    updateActivity() {
        this.lastActivity = Date.now();
    }

    initSessionMonitor() {
        setInterval(() => {
            if (this.isAuthenticated() && Date.now() - this.lastActivity > this.sessionTimeout) {
                this.logout();
                window.location.reload();
            }
        }, 60000);
        
        document.addEventListener('click', () => this.updateActivity());
        document.addEventListener('keypress', () => this.updateActivity());
    }

    getAuditLog() {
        const log = localStorage.getItem('audit-log');
        return log ? JSON.parse(log) : [];
    }

    addAuditEntry(action, details) {
        const log = this.getAuditLog();
        log.unshift({
            id: Date.now(),
            user: this.user?.username || 'system',
            action,
            details,
            timestamp: new Date().toISOString()
        });
        if (log.length > 100) log.pop();
        localStorage.setItem('audit-log', JSON.stringify(log));
    }
}
