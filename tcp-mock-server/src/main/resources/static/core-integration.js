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
        const authenticated = await this.auth.init();
        
        if (!authenticated) {
            this.showLoginModal();
            return false;
        }

        // Hide login modal if cached session is valid
        document.getElementById('loginModal')?.classList.remove('active');

        // Initialize all modules
        const http = new HttpClient(this.auth);
        this.workspace = new WorkspaceModule(http);
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
        const modal = document.getElementById('loginModal');
        if (modal) {
            modal.classList.add('active');
            document.getElementById('loginForm').onsubmit = (e) => {
                e.preventDefault();
                this.handleLogin();
            };
        }
    }

    async handleLogin() {
        const username = document.getElementById('loginUsername').value;
        const password = document.getElementById('loginPassword').value;
        
        if (!username || !password) {
            alert('Username and password required');
            return;
        }
        
        try {
            const success = await this.auth.login(username, password);
            
            if (!success) {
                alert('Invalid credentials');
                return;
            }
            
            document.getElementById('loginModal').classList.remove('active');
            
            // Initialize modules after successful login
            const http = new HttpClient(this.auth);
            this.workspace = new WorkspaceModule(http);
            this.notificationCenter = new NotificationModule(http);
            this.commandPalette = new CommandPaletteModule();
            this.dashboard = new DashboardModule();
            this.tour = new TourModule();

            await this.workspace.init();
            await this.notificationCenter.init();
            this.commandPalette.init(this.app);
            
            // Initialize app components
            this.app.http = http;
            this.app.recording.setHttpClient(http);
            this.app.initTestEditor();
            this.app.modules.init(this.app);
            
            this.updateUserUI();
            this.app.loadData();
            this.notificationCenter.add('Welcome back!', 'success');
            
            // Start auto-refresh
            setInterval(() => this.app.loadData(), 5000);
        } catch (error) {
            console.error('Login error:', error);
            alert('Login failed: ' + error.message);
        }
    }

    updateUserUI() {
        const user = this.auth.getCurrentUser();
        if (!user) return;

        document.getElementById('userMenuName').textContent = user.username;
        document.getElementById('userMenuRole').textContent = user.role;
        
        if (this.workspace) {
            const currentWorkspace = this.workspace.getCurrentWorkspace();
            if (currentWorkspace) {
                document.getElementById('currentWorkspaceName').textContent = currentWorkspace.name;
            }
        }
        
        // Generate avatar
        const avatar = document.getElementById('userAvatar');
        avatar.src = `https://ui-avatars.com/api/?name=${encodeURIComponent(user.username)}&background=f59e0b&color=fff`;
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
        if (!e.target.closest('#workspaceSwitcher') && !e.target.closest('#workspaceDropdown')) {
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
        const list = document.getElementById('workspaceList');
        const workspaces = this.workspace.getAll();
        const current = this.workspace.getCurrentWorkspace();
        
        list.innerHTML = workspaces.map(ws => `
            <button onclick="tcpMockUI.switchWorkspace('${ws.id}')" 
                    class="w-full text-left px-3 py-2 text-sm rounded-lg hover:bg-gray-50 dark:hover:bg-gray-700 ${ws.id === current.id ? 'bg-primary-50 dark:bg-primary-900/20 text-primary-600 dark:text-primary-400' : 'text-gray-700 dark:text-gray-300'}">
                <i class="fas fa-${ws.shared ? 'users' : 'user'} mr-2"></i>${ws.name}
            </button>
        `).join('');
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
        await this.workspace.switch(id);
        this.updateUserUI();
        this.closeAllDropdowns();
        this.app.loadData();
        await this.notificationCenter.add('Switched workspace', 'info');
    }

    createWorkspace() {
        document.getElementById('workspaceCreateModal')?.classList.add('active');
    }

    async saveWorkspace() {
        const name = document.getElementById('workspaceName').value.trim();
        const shared = document.getElementById('workspaceShared').checked;
        
        if (!name) {
            this.app.modules.showNotification('Workspace name required', 'error');
            return;
        }
        
        const workspace = await this.workspace.create(name, shared);
        if (workspace) {
            document.getElementById('workspaceCreateModal')?.classList.remove('active');
            this.updateUserUI();
            await this.notificationCenter.add(`Workspace "${name}" created`, 'success');
        } else {
            this.app.modules.showNotification('Failed to create workspace', 'error');
        }
    }

    showAuditLog() {
        const modal = document.getElementById('auditLogModal');
        const list = document.getElementById('auditLogList');
        
        const logs = this.auth.getAuditLog();
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
