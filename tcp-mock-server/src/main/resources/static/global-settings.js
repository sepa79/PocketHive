// Global Settings Module
class GlobalSettingsModule {
    constructor() {
        this.settings = {
            globalHeaders: {},
            defaultDelay: 0,
            defaultTimeout: 30000,
            corsEnabled: false,
            logLevel: 'INFO',
            serverPort: 8080,
            serverHost: '0.0.0.0'
        };
        this.load();
    }

    load() {
        const saved = localStorage.getItem('globalSettings');
        if (saved) {
            this.settings = { ...this.settings, ...JSON.parse(saved) };
        }
    }

    save() {
        localStorage.setItem('globalSettings', JSON.stringify(this.settings));
    }

    get(key) {
        return this.settings[key];
    }

    set(key, value) {
        this.settings[key] = value;
        this.save();
    }

    addGlobalHeader(name, value) {
        this.settings.globalHeaders[name] = value;
        this.save();
    }

    removeGlobalHeader(name) {
        delete this.settings.globalHeaders[name];
        this.save();
    }

    reset() {
        localStorage.removeItem('globalSettings');
        this.settings = {
            globalHeaders: {},
            defaultDelay: 0,
            defaultTimeout: 30000,
            corsEnabled: false,
            logLevel: 'INFO',
            serverPort: 8080,
            serverHost: '0.0.0.0'
        };
    }

    export() {
        return JSON.stringify(this.settings, null, 2);
    }

    import(json) {
        try {
            const imported = JSON.parse(json);
            this.settings = { ...this.settings, ...imported };
            this.save();
            return true;
        } catch (e) {
            return false;
        }
    }
}
