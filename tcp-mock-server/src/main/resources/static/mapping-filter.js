// Mapping Search & Filter Module
class MappingFilterModule {
    constructor() {
        this.filters = {
            search: '',
            priorityMin: null,
            priorityMax: null,
            hasDelay: null,
            hasScenario: null,
            hasAdvanced: null
        };
        this.sortBy = 'priority';
        this.sortDir = 'desc';
    }

    filter(mappings) {
        let filtered = [...mappings];

        if (this.filters.search) {
            const term = this.filters.search.toLowerCase();
            filtered = filtered.filter(m => 
                m.id.toLowerCase().includes(term) ||
                m.pattern.toLowerCase().includes(term) ||
                (m.description && m.description.toLowerCase().includes(term))
            );
        }

        if (this.filters.priorityMin !== null) {
            filtered = filtered.filter(m => m.priority >= this.filters.priorityMin);
        }

        if (this.filters.priorityMax !== null) {
            filtered = filtered.filter(m => m.priority <= this.filters.priorityMax);
        }

        if (this.filters.hasDelay !== null) {
            filtered = filtered.filter(m => (m.fixedDelayMs > 0) === this.filters.hasDelay);
        }

        if (this.filters.hasScenario !== null) {
            filtered = filtered.filter(m => (!!m.scenarioName) === this.filters.hasScenario);
        }

        if (this.filters.hasAdvanced !== null) {
            filtered = filtered.filter(m => m.hasAdvancedMatching === this.filters.hasAdvanced);
        }

        return this.sort(filtered);
    }

    sort(mappings) {
        return mappings.sort((a, b) => {
            let aVal = a[this.sortBy];
            let bVal = b[this.sortBy];
            
            if (typeof aVal === 'string') {
                aVal = aVal.toLowerCase();
                bVal = bVal.toLowerCase();
            }

            const result = aVal < bVal ? -1 : aVal > bVal ? 1 : 0;
            return this.sortDir === 'asc' ? result : -result;
        });
    }

    setSortBy(field) {
        if (this.sortBy === field) {
            this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
        } else {
            this.sortBy = field;
            this.sortDir = 'desc';
        }
    }

    reset() {
        this.filters = {
            search: '',
            priorityMin: null,
            priorityMax: null,
            hasDelay: null,
            hasScenario: null,
            hasAdvanced: null
        };
    }
}
