// Notification Center Module - Real Backend Integration
class NotificationModule {
    constructor(http) {
        this.http = http;
        this.notifications = [];
        this.unreadCount = 0;
    }

    async init() {
        await this.loadNotifications();
        await this.updateUnreadCount();
        this.updateBadge();
    }

    async loadNotifications() {
        try {
            const response = await this.http.get('/api/notifications');
            this.notifications = await response.json();
        } catch (error) {
            console.error('Failed to load notifications:', error);
            this.notifications = [];
        }
    }

    async updateUnreadCount() {
        try {
            const response = await this.http.get('/api/notifications/unread-count');
            const data = await response.json();
            this.unreadCount = data.count;
        } catch (error) {
            console.error('Failed to get unread count:', error);
        }
    }

    async add(message, type = 'info', persistent = false) {
        try {
            const response = await this.http.post('/api/notifications', { message, type, persistent });
            const notification = await response.json();
            this.notifications.unshift(notification);
            this.unreadCount++;
            this.updateBadge();
            return notification;
        } catch (error) {
            console.error('Failed to create notification:', error);
            return null;
        }
    }

    async markRead(id) {
        try {
            await this.http.put(`/api/notifications/${id}/read`);
            const notif = this.notifications.find(n => n.id === id);
            if (notif && !notif.read) {
                notif.read = true;
                this.unreadCount--;
                this.updateBadge();
                this.render();
            }
        } catch (error) {
            console.error('Failed to mark notification as read:', error);
        }
    }

    async markAllRead() {
        try {
            await this.http.put('/api/notifications/mark-all-read');
            this.notifications.forEach(n => n.read = true);
            this.unreadCount = 0;
            this.updateBadge();
            this.render();
        } catch (error) {
            console.error('Failed to mark all as read:', error);
        }
    }

    async clear() {
        try {
            await this.http.delete('/api/notifications');
            this.notifications = [];
            this.unreadCount = 0;
            this.updateBadge();
            this.render();
        } catch (error) {
            console.error('Failed to clear notifications:', error);
        }
    }

    updateBadge() {
        const badge = document.getElementById('notificationBadge');
        if (badge) {
            badge.textContent = this.unreadCount;
            badge.classList.toggle('hidden', this.unreadCount === 0);
        }
    }

    toggle() {
        const panel = document.getElementById('notificationPanel');
        if (panel) {
            panel.classList.toggle('hidden');
            if (!panel.classList.contains('hidden')) {
                this.render();
            }
        }
    }

    async requestPermission() {
        if ('Notification' in window && Notification.permission === 'default') {
            await Notification.requestPermission();
        }
    }

    render() {
        const list = document.getElementById('notificationList');
        if (!list) return;

        if (this.notifications.length === 0) {
            list.innerHTML = '<p class="text-center text-gray-500 dark:text-gray-400 py-8">No notifications</p>';
            return;
        }

        list.innerHTML = this.notifications.map(n => `
            <div class="border-b border-gray-200 dark:border-gray-700 p-3 ${n.read ? 'opacity-60' : ''} hover:bg-gray-50 dark:hover:bg-gray-700">
                <div class="flex items-start space-x-3">
                    <i class="fas fa-${this.getIcon(n.type)} text-${this.getColor(n.type)}-500 mt-1"></i>
                    <div class="flex-1">
                        <p class="text-sm text-gray-900 dark:text-white">${n.message}</p>
                        <p class="text-xs text-gray-500 dark:text-gray-400 mt-1">${new Date(n.timestamp).toLocaleString()}</p>
                    </div>
                    ${!n.read ? `<button onclick="if(tcpMockUI.core && tcpMockUI.core.notificationCenter) tcpMockUI.core.notificationCenter.markRead(${n.id})" class="text-primary-500 hover:text-primary-600 text-xs">Mark read</button>` : ''}
                </div>
            </div>
        `).join('');
    }

    getIcon(type) {
        const icons = { success: 'check-circle', error: 'exclamation-circle', warning: 'exclamation-triangle', info: 'info-circle' };
        return icons[type] || 'bell';
    }

    getColor(type) {
        const colors = { success: 'green', error: 'red', warning: 'yellow', info: 'blue' };
        return colors[type] || 'gray';
    }
}
