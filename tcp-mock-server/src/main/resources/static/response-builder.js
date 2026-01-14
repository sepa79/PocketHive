// Response Builder Module
class ResponseBuilderModule {
    constructor() {
        this.previewData = null;
    }

    async testExpression(type, expression, sampleData) {
        try {
            if (type === 'jsonPath') {
                const data = JSON.parse(sampleData);
                return this.evaluateJSONPath(expression, data);
            } else if (type === 'xmlPath') {
                return this.evaluateXPath(expression, sampleData);
            } else if (type === 'template') {
                return this.renderTemplate(expression, { message: sampleData });
            }
        } catch (e) {
            return { error: e.message };
        }
    }

    evaluateJSONPath(path, data) {
        const parts = path.replace(/^\$\./, '').split('.');
        let result = data;
        for (const part of parts) {
            if (result === undefined) return { error: 'Path not found' };
            result = result[part];
        }
        return { result: JSON.stringify(result, null, 2) };
    }

    evaluateXPath(path, xml) {
        const parser = new DOMParser();
        const doc = parser.parseFromString(xml, 'text/xml');
        const result = doc.evaluate(path, doc, null, XPathResult.STRING_TYPE, null);
        return { result: result.stringValue };
    }

    renderTemplate(template, context) {
        let result = template;
        for (const [key, value] of Object.entries(context)) {
            result = result.replace(new RegExp(`{{${key}}}`, 'g'), value);
        }
        return { result };
    }

    formatJSON(text) {
        try {
            const obj = JSON.parse(text);
            return JSON.stringify(obj, null, 2);
        } catch (e) {
            return text;
        }
    }

    formatXML(text) {
        try {
            const parser = new DOMParser();
            const doc = parser.parseFromString(text, 'text/xml');
            const serializer = new XMLSerializer();
            return this.prettifyXML(serializer.serializeToString(doc));
        } catch (e) {
            return text;
        }
    }

    prettifyXML(xml) {
        const formatted = [];
        let indent = 0;
        xml.split(/>\s*</).forEach(node => {
            if (node.match(/^\/\w/)) indent--;
            formatted.push('  '.repeat(indent) + '<' + node + '>');
            if (node.match(/^<?\w[^>]*[^\/]$/)) indent++;
        });
        return formatted.join('\n').replace(/^</, '').replace(/>$/, '');
    }

    toHex(text) {
        return Array.from(text).map(c => c.charCodeAt(0).toString(16).padStart(2, '0')).join(' ');
    }

    fromHex(hex) {
        const bytes = hex.split(' ').filter(b => b);
        return bytes.map(b => String.fromCharCode(parseInt(b, 16))).join('');
    }

    getTemplateVariables() {
        return [
            { name: 'message', desc: 'Original request message' },
            { name: 'now', desc: 'Current timestamp (ISO)' },
            { name: 'uuid', desc: 'Random UUID' },
            { name: 'randInt(min,max)', desc: 'Random integer' },
            { name: 'eval(expr)', desc: 'Evaluate SpEL expression' }
        ];
    }
}
