// Core Integration - Orchestrates all application features
class CoreIntegration {
    constructor(app) {
        this.app = app;
        this.auth = null;
        this.workspace = null;
        this.commandPalette = null;
        this.notificationCenter = null;
        this.dashboard = null;
        this.tour = null;
    }

    async init() {
        // Initialize authentication
        this.auth = new AuthModule();
        let authenticated = false;
        try { authenticated = await this.auth.init(); }
        catch (error) { document.getElementById('loginStatus').textContent = error.message; }
        
        if (!authenticated) {
            this.showLoginModal();
            return false;
        }

        // Hide login modal if cached session is valid
        document.getElementById('loginModal')?.classList.remove('active');

        // Initialize all modules
        const http = new HttpClient(this.auth);
        this.workspace = new WorkspaceModule(http);
        this.workspaceView = new WorkspaceView(this.workspace);
        this.commandPalette = new CommandPaletteModule();
        this.notificationCenter = new NotificationModule(http);
        this.dashboard = new DashboardModule();
        this.tour = new TourModule();

        await this.workspace.init();
        await this.notificationCenter.init();
        this.commandPalette.init(this.app);

        // Setup UI
        this.updateUserUI();
        this.bindEvents();
        this.checkFirstVisit();

        return true;
    }

    showLoginModal() {
        document.getElementById('loginModal')?.classList.add('active');
        document.getElementById('loginForm').onsubmit = async event => {
            event.preventDefault();
            try {
                const accepted = await this.auth.login(document.getElementById('loginUsername').value,
                    document.getElementById('loginPassword').value);
                if (accepted) location.reload();
                else document.getElementById('loginStatus').textContent = 'Native login rejected';
            } catch (error) { document.getElementById('loginStatus').textContent = error.message; }
        };
    }

    updateUserUI() {
        const user = this.auth.getCurrentUser();
        if (!user) return;

        document.getElementById('userMenuName').textContent = user.displayName;
        document.getElementById('userMenuRole').textContent = user.provider;
        
        this.workspaceView?.render();

        // Generate avatar
        const avatar = document.getElementById('userAvatar');
        avatar.src = `https://ui-avatars.com/api/?name=${encodeURIComponent(user.displayName)}&background=f59e0b&color=fff`;
    }

    bindEvents() {
        // Command palette
        document.getElementById('commandPaletteBtn')?.addEventListener('click', () => {
            if (this.commandPalette) this.commandPalette.open();
        });
        
        // Notifications
        document.getElementById('notificationBtn')?.addEventListener('click', () => {
            if (this.notificationCenter) this.notificationCenter.toggle();
        });
        
        // Workspace switcher
        document.getElementById('workspaceSwitcher')?.addEventListener('click', () => this.toggleWorkspaceDropdown());
        
        // User menu
        document.getElementById('userMenuBtn')?.addEventListener('click', () => this.toggleUserMenu());
        
        // Global keyboard shortcuts
        document.addEventListener('keydown', (e) => this.handleGlobalShortcuts(e));
        
        // Close dropdowns on outside click
        document.addEventListener('click', (e) => this.handleOutsideClick(e));
    }

    handleGlobalShortcuts(e) {
        // Ctrl/Cmd + K: Command palette
        if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
            e.preventDefault();
            if (this.commandPalette) this.commandPalette.open();
        }
        
        // Escape: Close modals
        if (e.key === 'Escape') {
            if (this.commandPalette) this.commandPalette.close();
            this.closeAllDropdowns();
        }
        
        // ?: Show shortcuts
        if (e.key === '?' && !e.ctrlKey && !e.metaKey) {
            const target = e.target;
            if (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA') {
                document.getElementById('shortcutsModal')?.classList.add('active');
            }
        }
    }

    handleOutsideClick(e) {
        const path = e.composedPath();
        if (!path.includes(document.getElementById('workspaceSwitcher')) &&
            !path.includes(document.getElementById('workspaceDropdown'))) {
            document.getElementById('workspaceDropdown')?.classList.add('hidden');
        }
        if (!e.target.closest('#userMenuBtn') && !e.target.closest('#userMenuDropdown')) {
            document.getElementById('userMenuDropdown')?.classList.add('hidden');
        }
        if (!e.target.closest('#notificationBtn') && !e.target.closest('#notificationPanel')) {
            document.getElementById('notificationPanel')?.classList.add('hidden');
        }
    }

    toggleWorkspaceDropdown() {
        const dropdown = document.getElementById('workspaceDropdown');
        if (dropdown) {
            dropdown.classList.toggle('hidden');
            if (!dropdown.classList.contains('hidden')) {
                this.renderWorkspaceList();
            }
        }
    }

    renderWorkspaceList() {
        this.workspaceView.render();
    }

    toggleUserMenu() {
        const dropdown = document.getElementById('userMenuDropdown');
        dropdown?.classList.toggle('hidden');
    }

    closeAllDropdowns() {
        document.getElementById('workspaceDropdown')?.classList.add('hidden');
        document.getElementById('userMenuDropdown')?.classList.add('hidden');
        document.getElementById('notificationPanel')?.classList.add('hidden');
    }

    checkFirstVisit() {
        if (this.tour && !localStorage.getItem('tour-completed')) {
            setTimeout(() => this.tour.start(), 1000);
        }
    }

    // Public API for app
    logout() {
        this.auth.logout();
        location.reload();
    }

    async switchWorkspace(id) {
        return this.workspaceView.select(id);
    }

    createWorkspace() {
        document.getElementById('workspaceCreateModal')?.classList.add('active');
    }

    async saveWorkspace() {
        return this.workspaceView.create();
    }

    showAuditLog() {
        const modal = document.getElementById('auditLogModal');
        const list = document.getElementById('auditLogList');
        
        const logs = BrowserAuditLog.read();
        list.innerHTML = logs.map(log => `
            <div class="flex items-start space-x-3 p-3 bg-gray-50 dark:bg-gray-900 rounded-lg">
                <i class="fas fa-${this.getAuditIcon(log.action)} text-gray-400 mt-1"></i>
                <div class="flex-1">
                    <p class="text-sm text-gray-900 dark:text-white">${log.action}</p>
                    <p class="text-xs text-gray-500 dark:text-gray-400">${new Date(log.timestamp).toLocaleString()}</p>
                </div>
            </div>
        `).join('');
        
        modal?.classList.add('active');
    }

    getAuditIcon(action) {
        const icons = {
            'login': 'sign-in-alt',
            'logout': 'sign-out-alt',
            'create': 'plus',
            'update': 'edit',
            'delete': 'trash'
        };
        return icons[action] || 'circle';
    }
}
