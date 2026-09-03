/**
 * Responsibility: Render bounded runtime-debug evidence in the PocketHive companion webview.
 * Must not: Call MCP tools, own companion state, or reinterpret owner-service outcomes.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */
interface PocketHiveDebugEvidenceView {
  readonly el: (tag: string, className?: string, children?: Array<Node | undefined>) => HTMLElement;
  readonly text: (tag: string, value: string, className?: string, role?: string) => HTMLElement;
  readonly titled: (tag: string, value: string, className?: string) => HTMLElement;
  readonly icon: (name: string, extraClass?: string) => HTMLElement;
  readonly iconButton: (
    label: string,
    iconName: string,
    action: () => void,
    className: string,
  ) => HTMLButtonElement;
  readonly iconText: (name: string, label: string, className?: string) => HTMLElement;
  readonly statusPill: (status: string) => HTMLElement;
  readonly dataRows: (rows: string[][]) => HTMLElement;
  readonly technicalDetails: (value: unknown, key?: string) => HTMLElement;
  readonly resultCard: (value: unknown) => HTMLElement;
  readonly ownerDataError: (value: unknown, expected: string) => HTMLElement;
  readonly objectValue: (value: unknown) => Record<string, any> | undefined;
  readonly stringField: (value: Record<string, any>, field: string) => string | undefined;
  readonly displayValue: (value: unknown) => string;
  readonly send: (message: unknown) => void;
}

interface PocketHiveDebugEvidenceApi {
  render(
    value: unknown,
    id: string,
    action: string,
    view: PocketHiveDebugEvidenceView,
  ): HTMLElement;
}

(() => {
  type DebugModel = Record<string, any>;

  function render(
    value: unknown,
    id: string,
    action: string,
    view: PocketHiveDebugEvidenceView,
  ): HTMLElement {
    let evidence: HTMLElement;
    if (action === 'Logs') evidence = logs(value, view);
    else if (action === 'Inspect') evidence = inspect(value, view);
    else if (action === 'Version') evidence = version(value, view);
    else if (action === 'Runtime assessment') evidence = assessment(value, view);
    else if (action === 'Cleanup plan') evidence = cleanupPlan(value, view);
    else evidence = generic(value, action, view);
    evidence.id = id;
    evidence.setAttribute('role', 'tabpanel');
    return evidence;
  }

  function generic(value: unknown, action: string, view: PocketHiveDebugEvidenceView): HTMLElement {
    return view.el('section', 'debug-evidence', [
      view.el('div', 'debug-evidence__heading', [
        view.text('h4', 'Bounded MCP evidence'),
        view.text('span', action, 'muted'),
      ]),
      view.resultCard(value),
    ]);
  }

  function assessment(value: unknown, view: PocketHiveDebugEvidenceView): HTMLElement {
    const result = view.objectValue(value);
    const overall = result && view.stringField(result, 'overall');
    const checks = result && Array.isArray(result.checks) ? result.checks : undefined;
    if (!result || !overall || checks === undefined) {
      return view.ownerDataError(value, 'runtime assessment');
    }
    const evidence = view.el('section', 'debug-evidence debug-assessment', [
      view.el('div', 'debug-evidence__heading', [
        view.text('h4', 'Runtime assessment'),
        view.statusPill(overall),
      ]),
    ]);
    const list = view.el('div', 'debug-assessment__checks');
    for (const checkValue of checks.slice(0, 20)) {
      const check = view.objectValue(checkValue);
      const name = check && view.stringField(check, 'check');
      const state = check && view.stringField(check, 'state');
      if (!check || !name || !state) {
        list.append(view.ownerDataError(checkValue, 'runtime assessment check'));
        continue;
      }
      list.append(view.el('article', 'debug-assessment__check', [
        view.icon(state === 'CONSISTENT' ? 'pass' : state === 'DRIFTED' ? 'warning' : 'question'),
        view.el('div', 'debug-assessment__copy', [
          view.titled('strong', name.replaceAll('_', ' '), 'truncate'),
          view.text('span', view.displayValue(check.summary), 'muted'),
        ]),
        view.statusPill(state),
      ]));
    }
    evidence.append(list, view.technicalDetails(result, 'debug:assessment:technical'));
    return evidence;
  }

  function logs(value: unknown, view: PocketHiveDebugEvidenceView): HTMLElement {
    const result = view.objectValue(value);
    const target = result && view.objectValue(result.target);
    const logText = result && typeof result.logs === 'string' ? result.logs : undefined;
    const tailLines = result && typeof result.tailLines === 'number' ? result.tailLines : undefined;
    if (!result || !target || logText === undefined || tailLines === undefined) {
      return view.ownerDataError(value, 'runtime log evidence');
    }
    const pre = document.createElement('pre');
    pre.tabIndex = 0;
    pre.textContent = logText;
    return view.el('section', 'debug-evidence debug-evidence--logs', [
      view.el('div', 'debug-evidence__heading', [
        view.text('h4', 'Container logs'),
        view.text('span', runtimeTargetLabel(target, view), 'muted truncate'),
      ]),
      view.el('article', 'debug-log-output', [pre]),
      view.el('div', 'debug-evidence__provenance', [
        view.icon('output'),
        view.text('span', `Docker stdout/stderr · tail ${tailLines}`),
        view.text('span', result.redacted === true ? 'Redacted' : 'Not redacted', 'muted'),
      ]),
    ]);
  }

  function inspect(value: unknown, view: PocketHiveDebugEvidenceView): HTMLElement {
    const result = view.objectValue(value);
    const target = result && view.objectValue(result.target);
    const source = result && view.objectValue(result.source);
    const state = result && view.objectValue(result.state);
    const mounts = result && Array.isArray(result.mounts) ? result.mounts : undefined;
    const networks = result && Array.isArray(result.networks) ? result.networks : undefined;
    if (!result || !target || !source || !state || !mounts || !networks) {
      return view.ownerDataError(value, 'runtime inspect evidence');
    }
    const projection = {
      state,
      createdAt: result.createdAt ?? null,
      restartCount: result.restartCount ?? null,
      restartPolicy: result.restartPolicy ?? null,
      mounts,
      networks,
    };
    const pre = document.createElement('pre');
    pre.tabIndex = 0;
    pre.textContent = JSON.stringify(projection, null, 2);
    return view.el('section', 'debug-evidence debug-evidence--inspect', [
      view.el('div', 'debug-evidence__heading', [
        view.text('h4', 'Container inspect'),
        view.text('span', runtimeTargetLabel(target, view), 'muted truncate'),
      ]),
      view.el('article', 'debug-inspect-output', [pre]),
      view.el('div', 'debug-evidence__provenance', [
        view.icon('json'),
        view.text('span', 'Orchestrator inspect projection'),
        view.text('span', source.available === true ? 'Available' : 'Unavailable', 'muted'),
      ]),
    ]);
  }

  function version(value: unknown, view: PocketHiveDebugEvidenceView): HTMLElement {
    const result = view.objectValue(value);
    const target = result && view.objectValue(result.target);
    if (!result || !target) return view.ownerDataError(value, 'runtime version evidence');
    return view.el('section', 'debug-evidence debug-evidence--version', [
      view.el('div', 'debug-evidence__heading', [
        view.text('h4', 'Deployed version'),
        view.text('span', runtimeTargetLabel(target, view), 'muted truncate'),
      ]),
      view.dataRows([
        ['Version', view.displayValue(result.reportedVersion)],
        ['Source', view.displayValue(result.reportedVersionSource)],
        ['Declared', view.displayValue(result.declaredVersion)],
        ['Image', view.displayValue(result.image)],
        ['Tag', view.displayValue(result.imageTag)],
        ['Digest', view.displayValue(result.imageDigest)],
      ]),
    ]);
  }

  function cleanupPlan(value: unknown, view: PocketHiveDebugEvidenceView): HTMLElement {
    const result = view.objectValue(value);
    const candidateSetHash = result && view.stringField(result, 'candidateSetHash');
    const candidates = result && Array.isArray(result.candidates) ? result.candidates : undefined;
    const blocked = result && Array.isArray(result.blocked) ? result.blocked : [];
    if (!result || !candidateSetHash || candidates === undefined) {
      return view.ownerDataError(value, 'runtime cleanup plan');
    }
    const count = candidates.length;
    const evidence = view.el('section', 'debug-evidence debug-cleanup-plan', [
      view.el('div', 'debug-evidence__heading', [
        view.text('h4', `${count} cleanup ${count === 1 ? 'candidate' : 'candidates'}`),
        view.statusPill(String(result.executionRisk ?? 'UNKNOWN')),
      ]),
      view.dataRows([
        ['Candidate set', candidateSetHash],
        ['Blocked', String(blocked.length)],
      ]),
    ]);
    const list = view.el('div', 'debug-cleanup-candidates');
    for (const candidateValue of candidates.slice(0, 1000)) {
      const candidate = view.objectValue(candidateValue);
      const candidateId = candidate && view.stringField(candidate, 'candidateId');
      if (!candidate || !candidateId) {
        list.append(view.ownerDataError(candidateValue, 'cleanup candidate'));
        continue;
      }
      list.append(view.el('article', 'debug-cleanup-candidate', [
        view.icon('trash'),
        view.el('div', 'debug-cleanup-candidate__copy', [
          view.titled('strong', candidateId, 'mono truncate'),
          view.titled('span', view.displayValue(candidate.reason), 'muted truncate'),
        ]),
      ]));
    }
    if (count > 0) evidence.append(list);
    const generate = view.iconButton('Generate new plan', 'refresh', () =>
      view.send({ type: 'runDebug', action: 'Cleanup plan' }), 'secondary compact');
    const execute = view.iconButton('Execute cleanup', 'lock', () => undefined, 'secondary compact');
    execute.disabled = true;
    execute.title = 'Cleanup execution requires HiveGate approval.';
    evidence.append(view.el('div', 'debug-cleanup-actions', [
      generate,
      execute,
      view.iconText('lock', 'Requires HiveGate approval', 'debug-cleanup-lock muted'),
    ]));
    return evidence;
  }

  function runtimeTargetLabel(target: DebugModel, view: PocketHiveDebugEvidenceView): string {
    return view.stringField(target, 'runtimeId')
      ?? view.stringField(target, 'name')
      ?? view.stringField(target, 'instance')
      ?? 'Exact runtime target';
  }

  const api: PocketHiveDebugEvidenceApi = Object.freeze({ render });
  (globalThis as typeof globalThis & { PocketHiveDebugEvidence: PocketHiveDebugEvidenceApi })
    .PocketHiveDebugEvidence = api;
})();
