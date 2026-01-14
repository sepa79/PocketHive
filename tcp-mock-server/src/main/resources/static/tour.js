// Tour Module - First-time user onboarding
class TourModule {
    constructor() {
        this.steps = [
            { target: '[data-tab="dashboard"]', title: 'Dashboard', text: 'View statistics and quick actions' },
            { target: '[data-tab="mappings"]', title: 'Mappings', text: 'Create and manage TCP message mappings' },
            { target: '#commandPaletteBtn', title: 'Command Palette', text: 'Press Ctrl+K to quickly access any feature' },
            { target: '#recordingToggle', title: 'Recording', text: 'Record requests to create mappings automatically' },
            { target: '#notificationBtn', title: 'Notifications', text: 'Stay updated with system notifications' },
            { target: '#userMenuBtn', title: 'User Menu', text: 'Access settings and logout' }
        ];
        this.currentStep = 0;
    }

    start() {
        if (localStorage.getItem('tour-completed')) return;
        this.currentStep = 0;
        this.showStep();
    }

    showStep() {
        if (this.currentStep >= this.steps.length) {
            this.complete();
            return;
        }

        const step = this.steps[this.currentStep];
        const target = document.querySelector(step.target);
        if (!target) {
            this.next();
            return;
        }

        const tooltip = document.getElementById('tourTooltip');
        const overlay = document.getElementById('tourOverlay');
        
        if (!tooltip || !overlay) return;

        target.classList.add('tour-highlight');
        overlay.classList.add('active');
        tooltip.classList.add('active');

        const rect = target.getBoundingClientRect();
        tooltip.style.top = `${rect.bottom + 10}px`;
        tooltip.style.left = `${rect.left}px`;

        tooltip.innerHTML = `
            <div class="p-4">
                <h3 class="font-semibold text-gray-900 dark:text-white mb-2">${step.title}</h3>
                <p class="text-sm text-gray-600 dark:text-gray-400 mb-4">${step.text}</p>
                <div class="flex justify-between items-center">
                    <span class="text-xs text-gray-500">${this.currentStep + 1} of ${this.steps.length}</span>
                    <div class="space-x-2">
                        <button onclick="if(tcpMockUI.core && tcpMockUI.core.tour) tcpMockUI.core.tour.skip()" class="px-3 py-1 text-sm text-gray-600 hover:text-gray-800">Skip</button>
                    <button onclick="if(tcpMockUI.core && tcpMockUI.core.tour) tcpMockUI.core.tour.next()" class="px-3 py-1 text-sm bg-primary-500 text-white rounded">Next</button>
                    </div>
                </div>
            </div>
        `;
    }

    next() {
        this.clearHighlight();
        this.currentStep++;
        this.showStep();
    }

    skip() {
        this.complete();
    }

    complete() {
        this.clearHighlight();
        document.getElementById('tourOverlay')?.classList.remove('active');
        document.getElementById('tourTooltip')?.classList.remove('active');
        localStorage.setItem('tour-completed', 'true');
    }

    reset() {
        localStorage.removeItem('tour-completed');
        this.start();
    }

    clearHighlight() {
        document.querySelectorAll('.tour-highlight').forEach(el => el.classList.remove('tour-highlight'));
    }
}
