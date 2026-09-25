const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

for (const base of ['https://hive.test/tcp-mock/', 'https://hive.test/tcp-mock/index.html', 'http://localhost:8080/']) {
    test(`API requests retain the served application path: ${base}`, async () => {
        const requests = [];
        const context = vm.createContext({ document: { baseURI: base }, URL,
            fetch: async (url, options) => { requests.push({ url, options }); return { status: 200 }; } });
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../src/main/resources/static/http-client.js'), 'utf8') + '\nglobalThis.Client = HttpClient;', context);
        const client = new context.Client({ getAuthHeader: () => ({ Authorization: 'Basic fixture' }) });
        await client.get('/api/workspaces');
        await client.put('/api/workspaces/ws-one', { name: 'Renamed', shared: false });
        const directory = new URL('.', base).href;
        assert.equal(requests[0].url, directory + 'api/workspaces');
        assert.equal(requests[1].url, directory + 'api/workspaces/ws-one');
        assert.equal(requests[1].options.headers.Authorization, 'Basic fixture');
        assert.equal(requests[1].options.body, JSON.stringify({ name: 'Renamed', shared: false }));
        assert.equal(context.Client.endpoint('/api/requests'), directory + 'api/requests');
        assert.throws(() => context.Client.endpoint('//another-host/api'), /Expected/);
    });
}
