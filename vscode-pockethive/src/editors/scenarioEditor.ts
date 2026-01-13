import * as vscode from 'vscode';
import { isMap, isSeq, parseDocument, stringify, YAMLMap, YAMLSeq } from 'yaml';

import { requestJson } from '../api';
import { resolveServiceConfig } from '../config';

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
  configData?: Record<string, unknown>;
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
  | { type: 'state'; ok: true; data: ScenarioFormData; raw: string; dirty: boolean }
  | { type: 'state'; ok: false; error: string; raw: string; dirty: boolean }
  | { type: 'validation'; errors: string[] }
  | { type: 'raw'; raw: string; dirty: boolean }
  | { type: 'capabilities'; ok: true; manifests: unknown[] }
  | { type: 'capabilities'; ok: false; error: string };

type ScenarioCommandMessage =
  | { type: 'save'; data: ScenarioFormData }
  | { type: 'capabilityChange'; beeIndex: number; path: string; value: unknown }
  | { type: 'change'; data: ScenarioFormData }
  | { type: 'openRaw' };

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
      const dirty = document.isDirty;
      const parsed = buildScenarioFormData(raw);
      if ('error' in parsed) {
        webview.postMessage({
          type: 'state',
          ok: false,
          error: parsed.error,
          raw,
          dirty
        } satisfies ScenarioStateMessage);
        return;
      }
      webview.postMessage({
        type: 'state',
        ok: true,
        data: parsed.data,
        raw,
        dirty
      } satisfies ScenarioStateMessage);
    };

    updateWebview();
    void sendCapabilities(webview);

    let ignoreNextDocumentChange = false;
    const changeDocumentSubscription = vscode.workspace.onDidChangeTextDocument((event) => {
      if (event.document.uri.toString() !== document.uri.toString()) {
        return;
      }
      if (ignoreNextDocumentChange) {
        ignoreNextDocumentChange = false;
        return;
      }
      updateWebview();
    });

    webviewPanel.onDidDispose(() => {
      changeDocumentSubscription.dispose();
    });

    webview.onDidReceiveMessage(async (message: ScenarioCommandMessage) => {
      if (!message) {
        return;
      }
      if (message.type === 'openRaw') {
        const textDoc = await vscode.workspace.openTextDocument(document.uri);
        if (textDoc.languageId === 'plaintext') {
          await vscode.languages.setTextDocumentLanguage(textDoc, 'yaml');
        }
        await vscode.window.showTextDocument(textDoc, {
          viewColumn: vscode.ViewColumn.Beside,
          preview: true
        });
        return;
      }
      if (message.type === 'change') {
        const result = parseScenarioFormInput(message.data, { requireFields: false });
        if (result.errors.length > 0 || !result.data) {
          return;
        }
        const currentText = document.getText();
        const updated = applyScenarioEdits(currentText, result.data);
        if ('error' in updated) {
          return;
        }
        if (updated.text === currentText) {
          return;
        }
        try {
          ignoreNextDocumentChange = true;
          await replaceDocument(document, updated.text);
          webview.postMessage({
            type: 'raw',
            raw: updated.text,
            dirty: document.isDirty
          } satisfies ScenarioStateMessage);
        } catch (error) {
          ignoreNextDocumentChange = false;
        }
        return;
      }
      if (message.type === 'capabilityChange') {
        const currentText = document.getText();
        const updated = applyCapabilityChange(currentText, message.beeIndex, message.path, message.value);
        if ('error' in updated) {
          webview.postMessage({
            type: 'validation',
            errors: [updated.error]
          } satisfies ScenarioStateMessage);
          return;
        }
        if (updated.text === currentText) {
          return;
        }
        try {
          await replaceDocument(document, updated.text);
        } catch (error) {
          webview.postMessage({
            type: 'validation',
            errors: [`Update failed: ${formatError(error)}`]
          } satisfies ScenarioStateMessage);
        }
        return;
      }
      if (message.type !== 'save') {
        return;
      }
      const result = parseScenarioFormInput(message.data, { requireFields: true });
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
        const saved = await document.save();
        if (!saved) {
          throw new Error('Scenario save did not complete.');
        }
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
      interceptors: interceptorsText,
      configData: isRecord(configValue) ? (configValue as Record<string, unknown>) : undefined
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

function parseScenarioFormInput(
  form: ScenarioFormData,
  options: { requireFields: boolean }
): { data?: ParsedScenarioData; errors: string[] } {
  const errors: string[] = [];
  const id = form.id.trim();
  const name = form.name.trim();
  const description = form.description.trim();

  if (options.requireFields && !id) {
    errors.push('Scenario id is required.');
  }
  if (options.requireFields && !name) {
    errors.push('Scenario name is required.');
  }

  const bees: ParsedBeeData[] = form.bees.map((bee, index) => {
    const label = `Bee ${index + 1}`;
    const role = bee.role.trim();
    const image = bee.image.trim();

    if (options.requireFields && !role) {
      errors.push(`${label}: role is required.`);
    }
    if (options.requireFields && !image) {
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

  return { text: doc.toString({ lineWidth: 0 }) };
}

function applyCapabilityChange(
  text: string,
  beeIndex: number,
  path: string,
  value: unknown
): { text: string } | { error: string } {
  const doc = parseDocument(text, { prettyErrors: true });
  if (doc.errors.length > 0) {
    return { error: doc.errors.map((err) => err.message).join('\n') };
  }
  const root = ensureRootMap(doc);
  const templateNode = ensureMap(root, 'template');
  const beesNode = ensureSeq(templateNode, 'bees');
  if (beeIndex < 0 || beeIndex >= beesNode.items.length) {
    return { error: `Bee index ${beeIndex + 1} is out of range.` };
  }
  const beeNode = beesNode.items[beeIndex];
  const beeMap = isMap(beeNode) ? beeNode : new YAMLMap();
  if (!isMap(beeNode)) {
    beesNode.items[beeIndex] = beeMap;
  }
  const configNode = ensureMap(beeMap, 'config');
  setYamlPathValue(configNode, path, value);
  return { text: doc.toString({ lineWidth: 0 }) };
}

async function replaceDocument(document: vscode.TextDocument, text: string): Promise<void> {
  const lastLine = document.lineAt(document.lineCount - 1);
  const fullRange = new vscode.Range(0, 0, document.lineCount - 1, lastLine.text.length);
  const edit = new vscode.WorkspaceEdit();
  edit.replace(document.uri, fullRange, text);
  const applied = await vscode.workspace.applyEdit(edit);
  if (!applied) {
    throw new Error('Failed to apply edits to scenario document.');
  }
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

function setYamlPathValue(map: YAMLMap, path: string, value: unknown): void {
  const segments = path.split('.').filter(Boolean);
  if (segments.length === 0) {
    return;
  }
  let current = map;
  for (let i = 0; i < segments.length - 1; i += 1) {
    const segment = segments[i];
    const existing = current.get(segment, true);
    if (existing && isMap(existing)) {
      current = existing;
      continue;
    }
    const next = new YAMLMap();
    current.set(segment, next);
    current = next;
  }
  const last = segments[segments.length - 1];
  if (value === undefined) {
    current.delete(last);
  } else {
    current.set(last, value);
  }
}

function formatYamlForEditor(value: unknown): string {
  if (value === undefined) {
    return '';
  }
  if (isRecord(value) && Object.keys(value).length === 0) {
    return '';
  }
  return stringify(value, { lineWidth: 0 }).trimEnd();
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

async function sendCapabilities(webview: vscode.Webview): Promise<void> {
  const config = resolveServiceConfig('scenarioManagerUrl');
  if ('error' in config) {
    webview.postMessage({
      type: 'capabilities',
      ok: false,
      error: config.error
    } satisfies ScenarioStateMessage);
    return;
  }
  try {
    const manifests = await requestJson<unknown[]>(
      config.baseUrl,
      config.authToken,
      'GET',
      '/api/capabilities?all=true'
    );
    webview.postMessage({
      type: 'capabilities',
      ok: true,
      manifests
    } satisfies ScenarioStateMessage);
  } catch (error) {
    webview.postMessage({
      type: 'capabilities',
      ok: false,
      error: formatError(error)
    } satisfies ScenarioStateMessage);
  }
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
      position: sticky;
      top: 0;
      z-index: 2;
      display: flex;
      gap: 8px;
      align-items: center;
      margin-bottom: 12px;
      padding: 10px 0;
      background: var(--vscode-editor-background);
      border-bottom: 1px solid var(--vscode-panel-border);
    }
    .toolbar-status {
      margin-left: auto;
      display: inline-flex;
      gap: 8px;
      align-items: center;
    }
    .status {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 2px 8px;
      border-radius: 999px;
      border: 1px solid var(--vscode-panel-border);
      font-size: 0.85em;
    }
    .status.parsing {
      color: var(--vscode-inputValidation-infoForeground);
      background: var(--vscode-inputValidation-infoBackground);
    }
    .status.dirty {
      color: var(--vscode-inputValidation-warningForeground);
      background: var(--vscode-inputValidation-warningBackground);
    }
    .page-header {
      margin-bottom: 16px;
    }
    .page-title {
      font-size: 1.9em;
      font-weight: 600;
      margin: 0 0 4px 0;
    }
    .page-subtitle {
      color: var(--vscode-descriptionForeground);
      font-size: 1.1em;
      margin: 0;
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
      white-space: pre;
    }
    .mono {
      font-family: var(--vscode-editor-font-family);
      font-size: var(--vscode-editor-font-size);
    }
    .bee {
      border: 1px solid var(--vscode-panel-border);
      background: var(--vscode-editorWidget-background);
      border-radius: 6px;
      padding: 10px;
      margin-bottom: 12px;
    }
    .bee-header {
      padding-bottom: 6px;
      border-bottom: 1px dashed var(--vscode-panel-border);
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }
    .bee-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 1.1em;
      color: var(--vscode-descriptionForeground);
    }
    .bee-index {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      border-radius: 999px;
      border: 1px solid var(--vscode-panel-border);
      font-size: 0.95em;
      color: var(--vscode-foreground);
      background: var(--vscode-editor-background);
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
    .capabilities {
      margin-top: 12px;
      border-top: 1px solid var(--vscode-panel-border);
      padding-top: 12px;
    }
    .capabilities-header {
      font-size: 1em;
      font-weight: 600;
      margin-bottom: 8px;
    }
    .cap-group {
      margin-top: 8px;
    }
    .cap-group-title {
      font-size: 0.9em;
      color: var(--vscode-descriptionForeground);
      margin-bottom: 6px;
    }
    .cap-entry {
      display: grid;
      grid-template-columns: minmax(160px, 220px) 1fr;
      gap: 8px;
      align-items: start;
      margin-bottom: 8px;
    }
    .cap-label {
      font-size: 0.9em;
      color: var(--vscode-descriptionForeground);
    }
    .cap-path {
      font-family: var(--vscode-editor-font-family);
      font-size: 0.8em;
      color: var(--vscode-descriptionForeground);
      opacity: 0.8;
    }
    .cap-help {
      font-size: 0.8em;
      color: var(--vscode-descriptionForeground);
    }
    .cap-control input,
    .cap-control select,
    .cap-control textarea {
      width: 100%;
      box-sizing: border-box;
    }
    .cap-empty {
      font-size: 0.85em;
      color: var(--vscode-descriptionForeground);
    }
  </style>
</head>
<body>
  <div id="app">Loading scenario...</div>
  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    const app = document.getElementById('app');
    let state = { data: null, errors: [], parseError: null, raw: '', dirty: false, status: 'idle' };
    let changeTimer = null;
    let capabilities = { status: 'loading', error: null, manifests: [], index: null };
    const capabilityTimers = new Map();

    window.addEventListener('message', (event) => {
      const message = event.data;
      if (!message || !message.type) {
        return;
      }
      if (message.type === 'state') {
        if (message.ok) {
          state = {
            data: message.data,
            errors: [],
            parseError: null,
            raw: message.raw,
            dirty: message.dirty,
            status: 'idle'
          };
        } else {
          state = {
            data: null,
            errors: [],
            parseError: message.error,
            raw: message.raw,
            dirty: message.dirty,
            status: 'idle'
          };
        }
        render();
      }
      if (message.type === 'validation') {
        const current = state.data ? readForm() : null;
        state = { ...state, data: current, errors: message.errors || [], parseError: null };
        render();
      }
      if (message.type === 'capabilities') {
        if (message.ok) {
          const normalized = normalizeManifests(message.manifests);
          capabilities = {
            status: 'ready',
            error: null,
            manifests: normalized,
            index: buildManifestIndex(normalized)
          };
        } else {
          capabilities = { status: 'error', error: message.error, manifests: [], index: null };
        }
        render();
      }
      if (message.type === 'raw') {
        state = { ...state, raw: message.raw, dirty: message.dirty, status: 'idle' };
        const rawAreas = app.querySelectorAll('textarea.raw');
        rawAreas.forEach((area) => {
          area.value = message.raw;
        });
        updateIndicators();
      }
    });

    document.addEventListener('keydown', (event) => {
      const key = event.key.toLowerCase();
      const isSave = (event.ctrlKey || event.metaKey) && key === 's';
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
            <textarea class="mono raw" wrap="off" readonly>\${escapeHtml(state.raw)}</textarea>
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
        <div class="page-header">
          <div class="page-title">\${escapeHtml(state.data.name || 'Untitled scenario')}</div>
          <div class="page-subtitle">\${escapeHtml(state.data.id)}</div>
        </div>
        <div class="toolbar">
          <button data-action="add-bee">Add bee</button>
          <button class="secondary" data-action="open-raw">Open raw YAML</button>
          <button data-action="save">Save</button>
          <div class="toolbar-status">
            <span id="parsing-indicator" class="status parsing" style="display: none;">Parsing…</span>
            <span id="dirty-indicator" class="status dirty" style="display: none;">Dirty</span>
          </div>
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
            <textarea data-field="description" wrap="off">\${escapeHtml(state.data.description)}</textarea>
          </label>
        </div>
        <div class="section">
          <h2>Bees</h2>
          \${state.data.bees.map(renderBee).join('')}
        </div>
        <details class="section">
          <summary>Raw YAML (read-only)</summary>
          <textarea class="mono raw" wrap="off" readonly>\${escapeHtml(state.raw)}</textarea>
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
          state = { ...state, data: current, status: 'parsing' };
          render();
          queueChange(current);
          return;
        }
        if (target.dataset.action === 'remove-bee') {
          const index = Number(target.dataset.index || '0');
          const current = readForm();
          current.bees.splice(index, 1);
          state = { ...state, data: current, status: 'parsing' };
          render();
          queueChange(current);
          return;
        }
        if (target.dataset.action === 'save') {
          const payload = readForm();
          vscode.postMessage({ type: 'save', data: payload });
        }
        if (target.dataset.action === 'open-raw') {
          vscode.postMessage({ type: 'openRaw' });
        }
      };

      app.oninput = (event) => {
        if (handleCapabilityInput(event)) {
          return;
        }
        if (!state.data) {
          return;
        }
        if (changeTimer) {
          clearTimeout(changeTimer);
        }
        changeTimer = setTimeout(() => {
          const payload = readForm();
          state = { ...state, data: payload, status: 'parsing' };
          updateIndicators();
          queueChange(payload);
        }, 200);
      };

      app.onchange = (event) => {
        if (handleCapabilityInput(event)) {
          return;
        }
      };

      updateIndicators();
    }

    function renderBee(bee, index) {
      const title = bee.role ? bee.role : 'Untitled bee';
      return \`
        <div class="bee" data-index="\${index}">
          <div class="bee-header">
            <div class="bee-badge">
              <span class="bee-index">\${index + 1}</span>
              <strong>\${escapeHtml(title)}</strong>
            </div>
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
            <textarea class="mono" wrap="off" data-field="config">\${escapeHtml(bee.config)}</textarea>
          </label>
          <label style="margin-top: 12px;">
            Inputs (YAML/JSON)
            <textarea class="mono" wrap="off" data-field="inputs">\${escapeHtml(bee.inputs)}</textarea>
          </label>
          <label style="margin-top: 12px;">
            Outputs (YAML/JSON)
            <textarea class="mono" wrap="off" data-field="outputs">\${escapeHtml(bee.outputs)}</textarea>
          </label>
          <label style="margin-top: 12px;">
            Interceptors (YAML/JSON)
            <textarea class="mono" wrap="off" data-field="interceptors">\${escapeHtml(bee.interceptors)}</textarea>
          </label>
          \${renderCapabilities(bee, index)}
        </div>
      \`;
    }

    function renderCapabilities(bee, index) {
      const header = '<div class="capabilities-header">Capabilities</div>';
      if (capabilities.status === 'loading') {
        return \`<div class="capabilities">\${header}<div class="cap-empty">Loading capabilities...</div></div>\`;
      }
      if (capabilities.status === 'error') {
        return \`<div class="capabilities">\${header}<div class="cap-empty">\${escapeHtml(capabilities.error || 'Failed to load capabilities.')}</div></div>\`;
      }
      if (!capabilities.index) {
        return \`<div class="capabilities">\${header}<div class="cap-empty">Capabilities unavailable.</div></div>\`;
      }
      const manifest = findManifestForImage(bee.image, capabilities.index);
      if (!manifest) {
        return \`<div class="capabilities">\${header}<div class="cap-empty">No capabilities for image: \${escapeHtml(bee.image || 'unknown')}</div></div>\`;
      }
      const configData = bee.configData || {};
      const entries = buildConfigEntriesForComponent(manifest, configData, capabilities.manifests);
      const visible = entries.filter((entry) => matchesCapabilityWhen(entry.when, (path) => getValueForPath(configData, path)));
      const groups = groupCapabilityConfigEntries(visible);
      if (groups.length === 0) {
        return \`<div class="capabilities">\${header}<div class="cap-empty">No fields available.</div></div>\`;
      }
      const groupHtml = groups
        .map((group) => {
          const entriesHtml = group.entries
            .map((entry) => renderCapabilityEntry(entry, configData, index))
            .join('');
          return \`<div class="cap-group"><div class="cap-group-title">\${escapeHtml(group.label)}</div>\${entriesHtml}</div>\`;
        })
        .join('');
      return \`<div class="capabilities">\${header}\${groupHtml}</div>\`;
    }

    function renderCapabilityEntry(entry, configData, beeIndex) {
      const label = capabilityEntryUiString(entry, 'label') || entry.name;
      const help = capabilityEntryUiString(entry, 'help');
      const normalizedType = (entry.type || '').toLowerCase();
      const options = Array.isArray(entry.options) ? entry.options : undefined;
      const currentValue = getValueForPath(configData, entry.name);
      const effectiveValue = currentValue !== undefined ? currentValue : entry.default;
      let control = '';
      if (options && options.length > 0) {
        const value = typeof effectiveValue === 'string' ? effectiveValue : formatCapabilityValue(effectiveValue);
        control = \`
          <select data-capability="true" data-bee-index="\${beeIndex}" data-capability-path="\${escapeHtml(entry.name)}" data-capability-type="\${escapeHtml(normalizedType)}">
            \${options
              .map((option) => {
                const optionLabel =
                  option === null || option === undefined
                    ? ''
                    : typeof option === 'string'
                      ? option
                      : JSON.stringify(option);
                const selected = optionLabel === value ? 'selected' : '';
                return \`<option value="\${escapeHtml(optionLabel)}" \${selected}>\${escapeHtml(optionLabel)}</option>\`;
              })
              .join('')}
          </select>\`;
      } else if (normalizedType === 'boolean' || normalizedType === 'bool') {
        const checked = effectiveValue === true ? 'checked' : '';
        control = \`
          <label class="cap-label">
            <input type="checkbox" data-capability="true" data-bee-index="\${beeIndex}" data-capability-path="\${escapeHtml(entry.name)}" data-capability-type="boolean" \${checked} />
            Enabled
          </label>\`;
      } else if (normalizedType === 'json' || entry.multiline || normalizedType === 'text') {
        const value = formatCapabilityValue(effectiveValue);
        control = \`
          <textarea class="mono" rows="3" wrap="off" data-capability="true" data-bee-index="\${beeIndex}" data-capability-path="\${escapeHtml(entry.name)}" data-capability-type="\${escapeHtml(normalizedType)}">\${escapeHtml(value)}</textarea>\`;
      } else {
        const inputType = inferCapabilityInputType(normalizedType);
        const value = formatCapabilityValue(effectiveValue);
        control = \`
          <input type="\${inputType}" value="\${escapeHtml(value)}" data-capability="true" data-bee-index="\${beeIndex}" data-capability-path="\${escapeHtml(entry.name)}" data-capability-type="\${escapeHtml(normalizedType)}" />\`;
      }

      return \`
        <div class="cap-entry">
          <div>
            <div class="cap-label">\${escapeHtml(label)}</div>
            <div class="cap-path">\${escapeHtml(entry.name)}</div>
            \${help ? \`<div class="cap-help">\${escapeHtml(help)}</div>\` : ''}
          </div>
          <div class="cap-control">\${control}</div>
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

    function queueChange(payload) {
      if (!payload) {
        return;
      }
      vscode.postMessage({ type: 'change', data: payload });
    }

    function queueCapabilityChange(beeIndex, path, value) {
      const key = beeIndex + ':' + path;
      const existing = capabilityTimers.get(key);
      if (existing) {
        clearTimeout(existing);
      }
      const timer = setTimeout(() => {
        capabilityTimers.delete(key);
        vscode.postMessage({ type: 'capabilityChange', beeIndex, path, value });
      }, 250);
      capabilityTimers.set(key, timer);
    }

    function handleCapabilityInput(event) {
      const target = event.target;
      if (!target || !target.dataset || target.dataset.capability !== 'true') {
        return false;
      }
      const beeIndex = Number(target.dataset.beeIndex || '0');
      const path = target.dataset.capabilityPath;
      const type = (target.dataset.capabilityType || '').toLowerCase();
      if (!path) {
        return true;
      }

      let value;
      if (type === 'boolean' || type === 'bool') {
        value = Boolean(target.checked);
      } else if (
        type === 'int' ||
        type === 'integer' ||
        type === 'number' ||
        type === 'double' ||
        type === 'float'
      ) {
        const raw = String(target.value || '').trim();
        if (!raw) {
          value = undefined;
        } else {
          const parsed = Number(raw);
          if (!Number.isFinite(parsed)) {
            state = { ...state, status: 'parsing' };
            updateIndicators();
            return true;
          }
          value = parsed;
        }
      } else if (type === 'json') {
        const raw = String(target.value || '').trim();
        if (!raw) {
          value = undefined;
        } else {
          try {
            value = JSON.parse(raw);
          } catch {
            state = { ...state, status: 'parsing' };
            updateIndicators();
            return true;
          }
        }
      } else {
        value = String(target.value ?? '');
      }

      state = { ...state, status: 'parsing' };
      updateIndicators();
      queueCapabilityChange(beeIndex, path, value);
      return true;
    }

    function updateIndicators() {
      const parsing = app.querySelector('#parsing-indicator');
      if (parsing) {
        parsing.style.display = state.status === 'parsing' ? 'inline-flex' : 'none';
      }
      const dirty = app.querySelector('#dirty-indicator');
      if (dirty) {
        dirty.style.display = state.dirty ? 'inline-flex' : 'none';
      }
    }

    function getField(container, field) {
      const element = container.querySelector('[data-field="' + field + '"]');
      return element ? element.value : '';
    }

    function normalizeManifests(data) {
      if (!Array.isArray(data)) return [];
      return data.map(normalizeManifest).filter((entry) => entry);
    }

    function normalizeManifest(entry) {
      if (!entry || typeof entry !== 'object') return null;
      const value = entry;
      if (typeof value.schemaVersion !== 'string' || typeof value.capabilitiesVersion !== 'string') {
        return null;
      }
      const imageValue = value.image && typeof value.image === 'object' ? value.image : {};
      const image = {
        name: typeof imageValue.name === 'string' ? imageValue.name : null,
        tag: typeof imageValue.tag === 'string' ? imageValue.tag : null,
        digest: typeof imageValue.digest === 'string' ? imageValue.digest : null
      };
      const config = Array.isArray(value.config) ? value.config.map(normalizeConfigEntry).filter((item) => item) : [];
      const role = typeof value.role === 'string' ? value.role : '';
      const ui = normalizeUi(value.ui);
      return {
        schemaVersion: value.schemaVersion,
        capabilitiesVersion: value.capabilitiesVersion,
        image,
        role,
        config,
        ui
      };
    }

    function normalizeConfigEntry(entry) {
      if (!entry || typeof entry !== 'object') return null;
      const value = entry;
      if (typeof value.name !== 'string' || typeof value.type !== 'string') return null;
      const options = Array.isArray(value.options) && value.options.length > 0 ? [...value.options] : undefined;
      return {
        name: value.name,
        type: value.type,
        default: value.default,
        min: typeof value.min === 'number' ? value.min : undefined,
        max: typeof value.max === 'number' ? value.max : undefined,
        multiline: typeof value.multiline === 'boolean' ? value.multiline : undefined,
        ui: value.ui,
        when: typeof value.when === 'object' && value.when !== null ? value.when : undefined,
        options
      };
    }

    function normalizeUi(entry) {
      if (!entry || typeof entry !== 'object') return undefined;
      return { ...entry };
    }

    function buildManifestIndex(list) {
      const byDigest = new Map();
      const byNameAndTag = new Map();
      list.forEach((manifest) => {
        const digest = manifest.image && typeof manifest.image.digest === 'string' ? manifest.image.digest.trim().toLowerCase() : '';
        if (digest) {
          byDigest.set(digest, manifest);
        }
        const name = manifest.image && typeof manifest.image.name === 'string' ? manifest.image.name.trim().toLowerCase() : '';
        const tag = manifest.image && typeof manifest.image.tag === 'string' ? manifest.image.tag.trim() : '';
        if (name && tag) {
          byNameAndTag.set(name + ':::' + tag, manifest);
        }
      });
      return { byDigest, byNameAndTag };
    }

    function findManifestForImage(image, index) {
      const reference = parseImageReference(image);
      if (!reference) return null;
      if (reference.digest) {
        const manifest = index.byDigest.get(reference.digest);
        if (manifest) return manifest;
      }
      if (reference.name && reference.tag) {
        const directKey = reference.name + ':::' + reference.tag;
        const direct = index.byNameAndTag.get(directKey);
        if (direct) return direct;
        const lastSlash = reference.name.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < reference.name.length - 1) {
          const simpleName = reference.name.slice(lastSlash + 1);
          const simpleKey = simpleName + ':::' + reference.tag;
          const simple = index.byNameAndTag.get(simpleKey);
          if (simple) return simple;
        }
      }
      return null;
    }

    function parseImageReference(image) {
      if (!image || typeof image !== 'string') return null;
      const trimmed = image.trim();
      if (!trimmed) return null;
      let digest = null;
      let remainder = trimmed;
      const digestIndex = trimmed.indexOf('@');
      if (digestIndex >= 0) {
        digest = trimmed.slice(digestIndex + 1).trim().toLowerCase();
        remainder = trimmed.slice(0, digestIndex);
      }
      remainder = remainder.trim();
      let namePart = remainder;
      let tag = null;
      if (remainder) {
        const lastColon = remainder.lastIndexOf(':');
        const lastSlash = remainder.lastIndexOf('/');
        if (lastColon > lastSlash) {
          tag = remainder.slice(lastColon + 1).trim() || null;
          namePart = remainder.slice(0, lastColon);
        }
      }
      const name = namePart ? namePart.trim().toLowerCase() : null;
      return { name, tag, digest };
    }

    function formatCapabilityValue(value) {
      if (value === null || value === undefined) return '';
      if (typeof value === 'string') return value;
      if (typeof value === 'number' || typeof value === 'boolean') return String(value);
      try {
        return JSON.stringify(value, null, 2);
      } catch {
        return '';
      }
    }

    function inferCapabilityInputType(type) {
      const normalized = (type || '').trim().toLowerCase();
      if (normalized === 'int' || normalized === 'integer' || normalized === 'number' || normalized === 'double' || normalized === 'float') {
        return 'number';
      }
      return 'text';
    }

    function capabilityEntryUiString(entry, key) {
      const ui = entry.ui;
      if (!ui || typeof ui !== 'object') return undefined;
      const value = ui[key];
      if (typeof value !== 'string') return undefined;
      const trimmed = value.trim();
      return trimmed ? trimmed : undefined;
    }

    function groupCapabilityConfigEntries(entries) {
      const groups = new Map();
      entries.forEach((entry) => {
        const group = capabilityEntryUiString(entry, 'group') || 'General';
        const id = group.trim() || 'General';
        const existing = groups.get(id);
        if (existing) {
          existing.entries.push(entry);
        } else {
          groups.set(id, { id, label: id, entries: [entry] });
        }
      });
      return Array.from(groups.values());
    }

    function matchesCapabilityWhen(when, resolveValue) {
      if (!when || typeof when !== 'object') {
        return true;
      }
      for (const [path, expected] of Object.entries(when)) {
        const actual = resolveValue(path);
        if (!matchesExpected(actual, expected)) {
          return false;
        }
      }
      return true;
    }

    function matchesExpected(actual, expected) {
      if (Array.isArray(expected)) {
        return expected.some((value) => matchesExpected(actual, value));
      }
      if (actual === undefined || actual === null) {
        return false;
      }
      if (typeof expected === 'string') {
        const expectedText = expected.trim();
        if (!expectedText) {
          return false;
        }
        if (typeof actual === 'string') {
          return actual.trim().toLowerCase() === expectedText.toLowerCase();
        }
        return String(actual).trim().toLowerCase() === expectedText.toLowerCase();
      }
      if (typeof expected === 'boolean') {
        if (typeof actual === 'boolean') return actual === expected;
        if (typeof actual === 'string') {
          const normalized = actual.trim().toLowerCase();
          if (normalized === 'true') return expected === true;
          if (normalized === 'false') return expected === false;
        }
        return false;
      }
      if (typeof expected === 'number') {
        if (typeof actual === 'number') return actual === expected;
        if (typeof actual === 'string') {
          const parsed = Number(actual);
          return Number.isFinite(parsed) && parsed === expected;
        }
        return false;
      }
      return Object.is(actual, expected);
    }

    function getValueForPath(obj, path) {
      if (!obj || typeof obj !== 'object') return undefined;
      const parts = String(path || '').split('.').filter(Boolean);
      let current = obj;
      for (const part of parts) {
        if (!current || typeof current !== 'object' || Array.isArray(current)) {
          return undefined;
        }
        current = current[part];
      }
      return current;
    }

    function inferIoTypeFromConfig(config) {
      if (!config || typeof config !== 'object' || Array.isArray(config)) {
        return undefined;
      }
      const inputs = config.inputs && typeof config.inputs === 'object' && !Array.isArray(config.inputs) ? config.inputs : undefined;
      if (!inputs) {
        return undefined;
      }
      const inputType = typeof inputs.type === 'string' ? inputs.type.trim().toUpperCase() : undefined;
      if (inputType === 'SCHEDULER' || inputType === 'REDIS_DATASET') {
        return inputType;
      }
      const hasScheduler = inputs.scheduler && typeof inputs.scheduler === 'object' && !Array.isArray(inputs.scheduler);
      const hasRedis = inputs.redis && typeof inputs.redis === 'object' && !Array.isArray(inputs.redis);
      if (hasScheduler) return 'SCHEDULER';
      if (hasRedis) return 'REDIS_DATASET';
      return undefined;
    }

    function buildConfigEntriesForComponent(manifest, componentConfig, manifests) {
      const baseEntries = Array.isArray(manifest.config) ? manifest.config : [];
      const ioType = inferIoTypeFromConfig(componentConfig);
      if (!ioType) {
        return baseEntries;
      }
      const allEntries = [...baseEntries];
      manifests.forEach((m) => {
        const ui = m.ui && typeof m.ui === 'object' ? m.ui : undefined;
        const ioTypeRaw = ui && typeof ui.ioType === 'string' ? ui.ioType : undefined;
        const manifestIoType = ioTypeRaw ? ioTypeRaw.trim().toUpperCase() : undefined;
        if (manifestIoType && manifestIoType === ioType && Array.isArray(m.config)) {
          allEntries.push(...m.config);
        }
      });
      const byName = new Map();
      allEntries.forEach((entry) => {
        if (!byName.has(entry.name)) {
          byName.set(entry.name, entry);
        }
      });
      return Array.from(byName.values());
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
