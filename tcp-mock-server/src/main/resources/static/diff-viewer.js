// Visual Diff Viewer Module
class DiffViewerModule {
    constructor() {
        this.diffCache = new Map();
    }

    computeDiff(text1, text2) {
        const lines1 = text1.split('\n');
        const lines2 = text2.split('\n');
        const diff = [];
        const maxLen = Math.max(lines1.length, lines2.length);

        for (let i = 0; i < maxLen; i++) {
            const line1 = lines1[i] || '';
            const line2 = lines2[i] || '';
            
            if (line1 === line2) {
                diff.push({ type: 'equal', line1, line2, lineNum: i + 1 });
            } else if (!line1) {
                diff.push({ type: 'added', line1: '', line2, lineNum: i + 1 });
            } else if (!line2) {
                diff.push({ type: 'removed', line1, line2: '', lineNum: i + 1 });
            } else {
                diff.push({ type: 'changed', line1, line2, lineNum: i + 1 });
            }
        }

        return diff;
    }

    renderDiff(diff) {
        return diff.map(d => {
            const bgClass = {
                equal: 'bg-gray-50 dark:bg-gray-800',
                added: 'bg-green-100 dark:bg-green-900/30',
                removed: 'bg-red-100 dark:bg-red-900/30',
                changed: 'bg-yellow-100 dark:bg-yellow-900/30'
            }[d.type];

            return `
                <div class="grid grid-cols-2 gap-2 ${bgClass} border-b border-gray-200 dark:border-gray-700">
                    <div class="px-3 py-1 font-mono text-xs">
                        <span class="text-gray-400 mr-2">${d.lineNum}</span>
                        <span class="text-gray-900 dark:text-gray-100">${this.escapeHtml(d.line1)}</span>
                    </div>
                    <div class="px-3 py-1 font-mono text-xs">
                        <span class="text-gray-400 mr-2">${d.lineNum}</span>
                        <span class="text-gray-900 dark:text-gray-100">${this.escapeHtml(d.line2)}</span>
                    </div>
                </div>
            `;
        }).join('');
    }

    highlightSyntax(text, type) {
        try {
            if (type === 'json') {
                const obj = JSON.parse(text);
                return this.syntaxHighlightJSON(obj);
            } else if (type === 'xml') {
                return this.syntaxHighlightXML(text);
            }
        } catch (e) {
            return this.escapeHtml(text);
        }
        return this.escapeHtml(text);
    }

    syntaxHighlightJSON(obj, indent = 0) {
        const spaces = '  '.repeat(indent);
        if (typeof obj === 'object' && obj !== null) {
            if (Array.isArray(obj)) {
                return '[' + obj.map(item => '\n' + spaces + '  ' + this.syntaxHighlightJSON(item, indent + 1)).join(',') + '\n' + spaces + ']';
            } else {
                const entries = Object.entries(obj).map(([key, val]) => 
                    `\n${spaces}  <span class="text-blue-600 dark:text-blue-400">"${key}"</span>: ${this.syntaxHighlightJSON(val, indent + 1)}`
                );
                return '{' + entries.join(',') + '\n' + spaces + '}';
            }
        } else if (typeof obj === 'string') {
            return `<span class="text-green-600 dark:text-green-400">"${this.escapeHtml(obj)}"</span>`;
        } else if (typeof obj === 'number') {
            return `<span class="text-purple-600 dark:text-purple-400">${obj}</span>`;
        } else if (typeof obj === 'boolean') {
            return `<span class="text-orange-600 dark:text-orange-400">${obj}</span>`;
        } else {
            return '<span class="text-gray-500">null</span>';
        }
    }

    syntaxHighlightXML(xml) {
        return xml
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/(&lt;\/?)([\w-]+)/g, '$1<span class="text-blue-600 dark:text-blue-400">$2</span>')
            .replace(/([\w-]+)=/g, '<span class="text-green-600 dark:text-green-400">$1</span>=')
            .replace(/="([^"]*)"/g, '="<span class="text-purple-600 dark:text-purple-400">$1</span>"');
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    detectFormat(text) {
        const trimmed = text.trim();
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) return 'json';
        if (trimmed.startsWith('<')) return 'xml';
        return 'text';
    }
}
