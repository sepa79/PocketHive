import * as vscode from 'vscode';
import { isMap, isSeq, parseDocument, stringify, YAMLMap, YAMLSeq } from 'yaml';

type ScenarioFormData = {
  id: string;
  name: string;
  description: string;
  bees: BeeFormData[];
};

type BeeFormData = {
  role: string;
  image: string;
  workIn: string;
  workOut: string;
  config: string;
  inputs: string;
  outputs: string;
  interceptors: string;
};

type ParsedScenarioData = {
  id: string;
  name: string;
  description?: string;
  bees: ParsedBeeData[];
};

type ParsedBeeData = {
  role: string;
  image: string;
  workIn?: string;
  workOut?: string;
  config?: Record<string, unknown>;
  inputs?: Record<string, unknown>;
  outputs?: Record<string, unknown>;
  interceptors?: Record<string, unknown>;
};

type ScenarioStateMessage =
  | { type: 'state'; ok: true; data: ScenarioFormData; raw: string }
  | { type: 'state'; ok: false; error: string; raw: string }
  | { type: 'validation'; errors: string[] };

type ScenarioCommandMessage = { type: 'save'; data: ScenarioFormData };

export class ScenarioEditorProvider implements vscode.CustomTextEditorProvider {
  static readonly viewType = 'pockethive.scenarioEditor';

  constructor(private readonly context: vscode.ExtensionContext) {}

  static register(context: vscode.ExtensionContext): vscode.Disposable {
    const provider = new ScenarioEditorProvider(context);
    return vscode.window.registerCustomEditorProvider(ScenarioEditorProvider.viewType, provider, {
      webviewOptions: { retainContextWhenHidden: true },
      supportsMultipleEditorsPerDocument: false
    });
  }

  async resolveCustomTextEditor(
    document: vscode.TextDocument,
    webviewPanel: vscode.WebviewPanel
  ): Promise<void> {
    const webview = webviewPanel.webview;
    webview.options = { enableScripts: true };
    webview.html = getScenarioEditorHtml(webview);

    const updateWebview = () => {
      const raw = document.getText();
      const parsed = buildScenarioFormData(raw);
      if ('error' in parsed) {
        webview.postMessage({ type: 'state', ok: false, error: parsed.error, raw } satisfies ScenarioStateMessage);
        return;
      }
      webview.postMessage({ type: 'state', ok: true, data: parsed.data, raw } satisfies ScenarioStateMessage);
    };

    updateWebview();

    const changeDocumentSubscription = vscode.workspace.onDidChangeTextDocument((event) => {
      if (event.document.uri.toString() === document.uri.toString()) {
        updateWebview();
      }
    });

    webviewPanel.onDidDispose(() => {
      changeDocumentSubscription.dispose();
    });

    webview.onDidReceiveMessage(async (message: ScenarioCommandMessage) => {
      if (!message || message.type !== 'save') {
        return;
      }
      const result = parseScenarioFormInput(message.data);
      if (result.errors.length > 0 || !result.data) {
        webview.postMessage({ type: 'validation', errors: result.errors } satisfies ScenarioStateMessage);
        return;
      }

      const updated = applyScenarioEdits(document.getText(), result.data);
      if ('error' in updated) {
        webview.postMessage({ type: 'validation', errors: [updated.error] } satisfies ScenarioStateMessage);
        return;
      }

      try {
        await replaceDocument(document, updated.text);
        await document.save();
      } catch (error) {
        webview.postMessage({
          type: 'validation',
          errors: [`Save failed: ${formatError(error)}`]
        } satisfies ScenarioStateMessage);
      }
    });
  }
}

function buildScenarioFormData(text: string): { data: ScenarioFormData } | { error: string } {
  const doc = parseDocument(text, { prettyErrors: true });
  if (doc.errors.length > 0) {
    return { error: doc.errors.map((err) => err.message).join('\n') };
  }
  const root = doc.toJS();
  const rootObj = isRecord(root) ? root : {};
  const template = isRecord(rootObj.template) ? rootObj.template : {};
  const bees = Array.isArray(template.bees) ? template.bees : [];
  const beeForms = bees.map((bee) => {
    const beeObj = isRecord(bee) ? bee : {};
    const work = isRecord(beeObj.work) ? beeObj.work : {};
    const configValue = beeObj.config;
    let configText = '';
    let inputsText = '';
    let outputsText = '';
    let interceptorsText = '';

    if (isRecord(configValue)) {
      const { inputs, outputs, interceptors, ...rest } = configValue;
      configText = formatYamlForEditor(rest);
      inputsText = formatYamlForEditor(inputs);
      outputsText = formatYamlForEditor(outputs);
      interceptorsText = formatYamlForEditor(interceptors);
    } else if (configValue !== undefined) {
      configText = formatYamlForEditor(configValue);
    }

    return {
      role: asString(beeObj.role),
      image: asString(beeObj.image),
      workIn: asString(work.in),
      workOut: asString(work.out),
      config: configText,
      inputs: inputsText,
      outputs: outputsText,
      interceptors: interceptorsText
    };
  });

  return {
    data: {
      id: asString(rootObj.id),
      name: asString(rootObj.name),
      description: asString(rootObj.description),
      bees: beeForms
    }
  };
}

function parseScenarioFormInput(form: ScenarioFormData): { data?: ParsedScenarioData; errors: string[] } {
  const errors: string[] = [];
  const id = form.id.trim();
  const name = form.name.trim();
  const description = form.description.trim();

  if (!id) {
    errors.push('Scenario id is required.');
  }
  if (!name) {
    errors.push('Scenario name is required.');
  }

  const bees: ParsedBeeData[] = form.bees.map((bee, index) => {
    const label = `Bee ${index + 1}`;
    const role = bee.role.trim();
    const image = bee.image.trim();

    if (!role) {
      errors.push(`${label}: role is required.`);
    }
    if (!image) {
      errors.push(`${label}: image is required.`);
    }

    const configResult = parseYamlField(`${label} config`, bee.config);
    const inputsResult = parseYamlField(`${label} inputs`, bee.inputs);
    const outputsResult = parseYamlField(`${label} outputs`, bee.outputs);
    const interceptorsResult = parseYamlField(`${label} interceptors`, bee.interceptors);

    errors.push(...configResult.errors, ...inputsResult.errors, ...outputsResult.errors, ...interceptorsResult.errors);

    if (configResult.value !== undefined && !isRecord(configResult.value)) {
      errors.push(`${label} config must be a map/object.`);
    }
    if (inputsResult.value !== undefined && !isRecord(inputsResult.value)) {
      errors.push(`${label} inputs must be a map/object.`);
    }
    if (outputsResult.value !== undefined && !isRecord(outputsResult.value)) {
      errors.push(`${label} outputs must be a map/object.`);
    }
    if (interceptorsResult.value !== undefined && !isRecord(interceptorsResult.value)) {
      errors.push(`${label} interceptors must be a map/object.`);
    }

    return {
      role,
      image,
      workIn: emptyToUndefined(bee.workIn),
      workOut: emptyToUndefined(bee.workOut),
      config: isRecord(configResult.value) ? (configResult.value as Record<string, unknown>) : undefined,
      inputs: isRecord(inputsResult.value) ? (inputsResult.value as Record<string, unknown>) : undefined,
      outputs: isRecord(outputsResult.value) ? (outputsResult.value as Record<string, unknown>) : undefined,
      interceptors: isRecord(interceptorsResult.value) ? (interceptorsResult.value as Record<string, unknown>) : undefined
    };
  });

  if (errors.length > 0) {
    return { errors };
  }

  return {
    errors: [],
    data: {
      id,
      name,
      description: description || undefined,
      bees
    }
  };
}

function parseYamlField(label: string, raw: string): { value?: unknown; errors: string[] } {
  const trimmed = raw.trim();
  if (!trimmed) {
    return { errors: [] };
  }
  const doc = parseDocument(trimmed, { prettyErrors: true });
  if (doc.errors.length > 0) {
    return { errors: doc.errors.map((err) => `${label}: ${err.message}`) };
  }
  return { value: doc.toJS(), errors: [] };
}

function applyScenarioEdits(text: string, data: ParsedScenarioData): { text: string } | { error: string } {
  const doc = parseDocument(text, { prettyErrors: true });
  if (doc.errors.length > 0) {
    return { error: doc.errors.map((err) => err.message).join('\n') };
  }

  const root = ensureRootMap(doc);
  root.set('id', data.id);
  root.set('name', data.name);
  if (data.description) {
    root.set('description', data.description);
  } else {
    root.delete('description');
  }

  const templateNode = ensureMap(root, 'template');
  const beesNode = ensureSeq(templateNode, 'bees');

  while (beesNode.items.length > data.bees.length) {
    beesNode.items.pop();
  }
  while (beesNode.items.length < data.bees.length) {
    beesNode.items.push(new YAMLMap());
  }

  data.bees.forEach((bee, index) => {
    const beeNode = beesNode.items[index];
    const beeMap = isMap(beeNode) ? beeNode : new YAMLMap();
    if (!isMap(beeNode)) {
      beesNode.items[index] = beeMap;
    }

    beeMap.set('role', bee.role);
    beeMap.set('image', bee.image);

    const workNode = ensureMap(beeMap, 'work');
    setOptionalString(workNode, 'in', bee.workIn);
    setOptionalString(workNode, 'out', bee.workOut);

    const configValue: Record<string, unknown> = { ...(bee.config ?? {}) };
    delete configValue.inputs;
    delete configValue.outputs;
    delete configValue.interceptors;

    if (bee.inputs !== undefined) {
      configValue.inputs = bee.inputs;
    } else {
      delete configValue.inputs;
    }
    if (bee.outputs !== undefined) {
      configValue.outputs = bee.outputs;
    } else {
      delete configValue.outputs;
    }
    if (bee.interceptors !== undefined) {
      configValue.interceptors = bee.interceptors;
    } else {
      delete configValue.interceptors;
    }

    if (Object.keys(configValue).length > 0) {
      beeMap.set('config', configValue);
    } else {
      beeMap.delete('config');
    }
  });

  return { text: doc.toString() };
}

async function replaceDocument(document: vscode.TextDocument, text: string): Promise<void> {
  const lastLine = document.lineAt(document.lineCount - 1);
  const fullRange = new vscode.Range(0, 0, document.lineCount - 1, lastLine.text.length);
  const edit = new vscode.WorkspaceEdit();
  edit.replace(document.uri, fullRange, text);
  await vscode.workspace.applyEdit(edit);
}

function ensureRootMap(doc: ReturnType<typeof parseDocument>): YAMLMap {
  if (doc.contents && isMap(doc.contents)) {
    return doc.contents;
  }
  const root = new YAMLMap();
  doc.contents = root;
  return root;
}

function ensureMap(parent: YAMLMap, key: string): YAMLMap {
  const current = parent.get(key, true);
  if (current && isMap(current)) {
    return current;
  }
  const next = new YAMLMap();
  parent.set(key, next);
  return next;
}

function ensureSeq(parent: YAMLMap, key: string): YAMLSeq {
  const current = parent.get(key, true);
  if (current && isSeq(current)) {
    return current;
  }
  const next = new YAMLSeq();
  parent.set(key, next);
  return next;
}

function setOptionalString(map: YAMLMap, key: string, value?: string): void {
  if (!value) {
    map.delete(key);
    return;
  }
  map.set(key, value);
}

function formatYamlForEditor(value: unknown): string {
  if (value === undefined) {
    return '';
  }
  if (isRecord(value) && Object.keys(value).length === 0) {
    return '';
  }
  return stringify(value).trimEnd();
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function emptyToUndefined(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

function formatError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

function getScenarioEditorHtml(webview: vscode.Webview): string {
  const nonce = getNonce();
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource} 'unsafe-inline'; script-src 'nonce-${nonce}';" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <style>
    :root {
      color-scheme: light dark;
    }
    body {
      padding: 16px;
      color: var(--vscode-foreground);
      background: var(--vscode-editor-background);
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
    }
    h2 {
      margin: 0 0 8px 0;
      font-size: 1.1em;
    }
    .toolbar {
      display: flex;
      gap: 8px;
      align-items: center;
      margin-bottom: 12px;
    }
    button {
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: 1px solid var(--vscode-button-border);
      padding: 6px 10px;
      cursor: pointer;
    }
    button.secondary {
      background: transparent;
      color: var(--vscode-foreground);
      border-color: var(--vscode-contrastBorder);
    }
    .section {
      border: 1px solid var(--vscode-panel-border);
      padding: 12px;
      border-radius: 6px;
      margin-bottom: 16px;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
    }
    label {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    input, textarea {
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-input-border);
      padding: 6px;
      border-radius: 4px;
    }
    textarea {
      min-height: 120px;
      resize: vertical;
    }
    .mono {
      font-family: var(--vscode-editor-font-family);
      font-size: var(--vscode-editor-font-size);
    }
    .bee {
      border: 1px solid var(--vscode-panel-border);
      border-radius: 6px;
      padding: 10px;
      margin-bottom: 12px;
    }
    .bee-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }
    .errors {
      background: var(--vscode-inputValidation-errorBackground);
      border: 1px solid var(--vscode-errorForeground);
      padding: 8px;
      border-radius: 6px;
      margin-bottom: 12px;
    }
    .errors div {
      margin-bottom: 4px;
    }
    .raw {
      width: 100%;
      min-height: 180px;
    }
  </style>
</head>
<body>
  <div id="app">Loading scenario...</div>
  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    const app = document.getElementById('app');
    let state = { data: null, errors: [], parseError: null, raw: '' };

    window.addEventListener('message', (event) => {
      const message = event.data;
      if (!message || !message.type) {
        return;
      }
      if (message.type === 'state') {
        if (message.ok) {
          state = { data: message.data, errors: [], parseError: null, raw: message.raw };
        } else {
          state = { data: null, errors: [], parseError: message.error, raw: message.raw };
        }
        render();
      }
      if (message.type === 'validation') {
        const current = state.data ? readForm() : null;
        state = { data: current, errors: message.errors || [], parseError: null, raw: state.raw };
        render();
      }
    });

    document.addEventListener('keydown', (event) => {
      const isSave = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's';
      if (!isSave || !state.data) {
        return;
      }
      event.preventDefault();
      const payload = readForm();
      vscode.postMessage({ type: 'save', data: payload });
    });

    function render() {
      if (state.parseError) {
        app.innerHTML = \`
          <div class="errors">
            <div><strong>YAML parse error</strong></div>
            <div>\${escapeHtml(state.parseError)}</div>
          </div>
          <div class="section">
            <h2>Raw YAML (read-only)</h2>
            <textarea class="mono raw" readonly>\${escapeHtml(state.raw)}</textarea>
          </div>
        \`;
        return;
      }

      if (!state.data) {
        app.textContent = 'Loading scenario...';
        return;
      }

      app.innerHTML = \`
        \${renderErrors(state.errors)}
        <div class="toolbar">
          <button data-action="add-bee">Add bee</button>
          <button data-action="save">Save</button>
        </div>
        <div class="section">
          <h2>Scenario</h2>
          <div class="grid">
            <label>
              Id
              <input data-field="id" value="\${escapeHtml(state.data.id)}" />
            </label>
            <label>
              Name
              <input data-field="name" value="\${escapeHtml(state.data.name)}" />
            </label>
          </div>
          <label style="margin-top: 12px;">
            Description
            <textarea data-field="description">\${escapeHtml(state.data.description)}</textarea>
          </label>
        </div>
        <div class="section">
          <h2>Bees</h2>
          \${state.data.bees.map(renderBee).join('')}
        </div>
        <details class="section">
          <summary>Raw YAML (read-only)</summary>
          <textarea class="mono raw" readonly>\${escapeHtml(state.raw)}</textarea>
        </details>
      \`;

      app.onclick = (event) => {
        const target = event.target;
        if (!target || !target.dataset) {
          return;
        }
        if (target.dataset.action === 'add-bee') {
          const current = readForm();
          current.bees.push({
            role: '',
            image: '',
            workIn: '',
            workOut: '',
            config: '',
            inputs: '',
            outputs: '',
            interceptors: ''
          });
          state = { ...state, data: current };
          render();
          return;
        }
        if (target.dataset.action === 'remove-bee') {
          const index = Number(target.dataset.index || '0');
          const current = readForm();
          current.bees.splice(index, 1);
          state = { ...state, data: current };
          render();
          return;
        }
        if (target.dataset.action === 'save') {
          const payload = readForm();
          vscode.postMessage({ type: 'save', data: payload });
        }
      };
    }

    function renderBee(bee, index) {
      const title = bee.role ? bee.role : 'Untitled bee';
      return \`
        <div class="bee" data-index="\${index}">
          <div class="bee-header">
            <strong>\${escapeHtml(title)}</strong>
            <button class="secondary" data-action="remove-bee" data-index="\${index}">Remove</button>
          </div>
          <div class="grid">
            <label>
              Role
              <input data-field="role" value="\${escapeHtml(bee.role)}" />
            </label>
            <label>
              Image
              <input data-field="image" value="\${escapeHtml(bee.image)}" />
            </label>
            <label>
              Work in
              <input data-field="workIn" value="\${escapeHtml(bee.workIn)}" />
            </label>
            <label>
              Work out
              <input data-field="workOut" value="\${escapeHtml(bee.workOut)}" />
            </label>
          </div>
          <label style="margin-top: 12px;">
            Config (YAML/JSON)
            <textarea class="mono" data-field="config">\${escapeHtml(bee.config)}</textarea>
          </label>
          <label style="margin-top: 12px;">
            Inputs (YAML/JSON)
            <textarea class="mono" data-field="inputs">\${escapeHtml(bee.inputs)}</textarea>
          </label>
          <label style="margin-top: 12px;">
            Outputs (YAML/JSON)
            <textarea class="mono" data-field="outputs">\${escapeHtml(bee.outputs)}</textarea>
          </label>
          <label style="margin-top: 12px;">
            Interceptors (YAML/JSON)
            <textarea class="mono" data-field="interceptors">\${escapeHtml(bee.interceptors)}</textarea>
          </label>
        </div>
      \`;
    }

    function renderErrors(errors) {
      if (!errors || errors.length === 0) {
        return '';
      }
      return \`
        <div class="errors">
          \${errors.map((error) => \`<div>\${escapeHtml(error)}</div>\`).join('')}
        </div>
      \`;
    }

    function readForm() {
      const scenario = {
        id: '',
        name: '',
        description: '',
        bees: []
      };
      const idInput = app.querySelector('[data-field="id"]');
      const nameInput = app.querySelector('[data-field="name"]');
      const descriptionInput = app.querySelector('[data-field="description"]');
      scenario.id = idInput ? idInput.value : '';
      scenario.name = nameInput ? nameInput.value : '';
      scenario.description = descriptionInput ? descriptionInput.value : '';

      const bees = Array.from(app.querySelectorAll('.bee'));
      scenario.bees = bees.map((bee) => ({
        role: getField(bee, 'role'),
        image: getField(bee, 'image'),
        workIn: getField(bee, 'workIn'),
        workOut: getField(bee, 'workOut'),
        config: getField(bee, 'config'),
        inputs: getField(bee, 'inputs'),
        outputs: getField(bee, 'outputs'),
        interceptors: getField(bee, 'interceptors')
      }));
      return scenario;
    }

    function getField(container, field) {
      const element = container.querySelector('[data-field="' + field + '"]');
      return element ? element.value : '';
    }

    function escapeHtml(value) {
      return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    }
  </script>
</body>
</html>`;
}

function getNonce(): string {
  let text = '';
  const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  for (let i = 0; i < 32; i += 1) {
    text += possible.charAt(Math.floor(Math.random() * possible.length));
  }
  return text;
}
