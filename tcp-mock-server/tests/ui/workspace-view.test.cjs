const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function element() {
    return { textContent: '', value: '', checked: false, children: [], attributes: {}, events: {},
        classList: { remove() {} },
        replaceChildren(...children) { this.children = children; },
        append(...children) { this.children.push(...children); },
        setAttribute(key, value) { this.attributes[key] = value; },
        addEventListener(key, action) { this.events[key] = action; },
    };
}
function fixture(workspace) {
    const elements = new Map();
    const document = { getElementById(id) {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    }, createElement: element };
    const window = { prompt: () => 'Renamed', confirm: () => true };
    const context = vm.createContext({ document, window, ErrorHandler: { handle() {} } });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../src/main/resources/static/workspace-view.js'), 'utf8') + '\nglobalThis.View = WorkspaceView;', context);
    return { view: new context.View(workspace), document, window };
}
const entry = { id: 'not-a-default-convention', name: '<img src=x onerror=alert(1)>', deletable: false };

test('renders names as text, server deletion policy and absent selection safely', () => {
    const { view, document } = fixture({ getCurrentWorkspace: () => null, getAll: () => [entry] });
    view.render();
    assert.equal(document.getElementById('currentWorkspaceName').textContent, 'Catalogue unavailable');
    const list = document.getElementById('workspaceList');
    assert.equal(list.children[0].children[0].textContent, entry.name);
    assert.equal(list.children[0].children.length, 2);
    assert.equal(list.children[1].textContent, 'Reload catalogue');
});

test('failed edits preserve visible selection and offer reconciliation without retry', async () => {
    let calls = 0;
    const workspace = { getCurrentWorkspace: () => entry, getAll: () => [entry],
        rename: async () => { calls++; return false; },
        delete: async () => { calls++; return false; },
        loadWorkspaces: async () => { calls++; return true; } };
    const { view, document } = fixture(workspace);
    assert.equal(await view.rename(entry), false);
    assert.match(document.getElementById('workspaceStatus').textContent, /Reload/);
    assert.equal(await view.remove(entry), false);
    assert.equal(calls, 2);
    assert.equal(document.getElementById('currentWorkspaceName').textContent, entry.name);
    assert.equal(await view.reload(), true);
    assert.equal(calls, 3);
});

test('creation failure preserves form and blocks overlapping submissions', async () => {
    let resolve, calls = 0, closed = false;
    const { view, document } = fixture({ getCurrentWorkspace: () => null, getAll: () => [],
        create: () => { calls++; return new Promise(done => { resolve = done; }); } });
    document.getElementById('workspaceName').value = 'Keep this name';
    document.getElementById('workspaceCreateModal').classList.remove = () => { closed = true; };
    const first = view.create();
    await view.create();
    assert.equal(calls, 1);
    resolve(null);
    await first;
    assert.equal(closed, false);
    assert.equal(document.getElementById('workspaceName').value, 'Keep this name');
    assert.match(document.getElementById('workspaceCreateStatus').textContent, /reload/i);
});

test('cancelled rename and deletion do not dispatch requests', async () => {
    const { view, window } = fixture({});
    window.prompt = () => null;
    window.confirm = () => false;
    assert.equal(await view.rename(entry), false);
    assert.equal(await view.remove(entry), false);
});
