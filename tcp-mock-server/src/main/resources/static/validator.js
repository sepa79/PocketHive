// Validation Module
const Validator = {
    validateRegex(pattern) {
        try {
            new RegExp(pattern);
            return { valid: true, message: 'Valid regex pattern' };
        } catch (e) {
            return { valid: false, message: e.message };
        }
    },
    
    validateJSON(text) {
        try {
            JSON.parse(text);
            return { valid: true, message: 'Valid JSON' };
        } catch (e) {
            return { valid: false, message: e.message };
        }
    },
    
    validateRequired(value, fieldName) {
        if (!value || value.trim() === '') {
            return { valid: false, message: `${fieldName} is required` };
        }
        return { valid: true, message: '' };
    },
    
    validatePriority(value) {
        const num = parseInt(value);
        if (isNaN(num) || num < 1 || num > 100) {
            return { valid: false, message: 'Priority must be between 1 and 100' };
        }
        return { valid: true, message: '' };
    },
    
    validateDelay(value) {
        const num = parseInt(value);
        if (isNaN(num) || num < 0) {
            return { valid: false, message: 'Delay must be 0 or positive' };
        }
        return { valid: true, message: '' };
    },
    
    validateMapping(mapping) {
        const errors = [];
        
        const idCheck = this.validateRequired(mapping.id, 'ID');
        if (!idCheck.valid) errors.push(idCheck.message);
        
        const patternCheck = this.validateRequired(mapping.pattern, 'Pattern');
        if (!patternCheck.valid) errors.push(patternCheck.message);
        else {
            const regexCheck = this.validateRegex(mapping.pattern);
            if (!regexCheck.valid) errors.push(`Pattern: ${regexCheck.message}`);
        }
        
        const priorityCheck = this.validatePriority(mapping.priority);
        if (!priorityCheck.valid) errors.push(priorityCheck.message);
        
        if (mapping.fixedDelayMs) {
            const delayCheck = this.validateDelay(mapping.fixedDelayMs);
            if (!delayCheck.valid) errors.push(delayCheck.message);
        }
        
        return { valid: errors.length === 0, errors };
    }
};
