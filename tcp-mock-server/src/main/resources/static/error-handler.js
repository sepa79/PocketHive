// Error Handler - User-friendly error messages
class ErrorHandler {
    static handle(error, context = '') {
        console.error(`Error in ${context}:`, error);
        
        let message = 'An error occurred';
        if (error.message) {
            message = error.message;
        } else if (typeof error === 'string') {
            message = error;
        }

        if (context) {
            message = `${context}: ${message}`;
        }

        this.show(message, 'error');
    }

    static show(message, type = 'info') {
        const container = document.getElementById('errorToast');
        if (!container) {
            this.createToastContainer();
        }

        const toast = document.createElement('div');
        toast.className = `toast toast-${type} animate-slide-in`;
        toast.innerHTML = `
            <div class="flex items-center space-x-3 p-4 bg-white dark:bg-gray-800 rounded-lg shadow-lg border border-gray-200 dark:border-gray-700">
                <i class="fas fa-${this.getIcon(type)} text-${this.getColor(type)}-500"></i>
                <span class="text-sm text-gray-900 dark:text-white">${message}</span>
                <button onclick="this.parentElement.parentElement.remove()" class="text-gray-400 hover:text-gray-600">
                    <i class="fas fa-times"></i>
                </button>
            </div>
        `;

        document.getElementById('errorToast').appendChild(toast);
        setTimeout(() => toast.remove(), 5000);
    }

    static createToastContainer() {
        const container = document.createElement('div');
        container.id = 'errorToast';
        container.className = 'fixed top-20 right-4 z-50 space-y-2';
        document.body.appendChild(container);
    }

    static getIcon(type) {
        const icons = { success: 'check-circle', error: 'exclamation-circle', warning: 'exclamation-triangle', info: 'info-circle' };
        return icons[type] || 'bell';
    }

    static getColor(type) {
        const colors = { success: 'green', error: 'red', warning: 'yellow', info: 'blue' };
        return colors[type] || 'gray';
    }
}
