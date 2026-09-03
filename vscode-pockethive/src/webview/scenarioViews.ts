/**
 * Responsibility: Render deployed and repository Scenario Bundle views and their exact user commands.
 * Must not: Call MCP services, own repository discovery, or reinterpret Scenario Manager outcomes.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */

interface PocketHiveScenarioViewPort {
  readonly brandMark: (className: string) => HTMLImageElement;
  readonly button: (label: string, action: () => void, className: string) => HTMLButtonElement;
  readonly dataRows: (rows: string[][]) => HTMLElement;
  readonly displayValue: (value: unknown) => string;
  readonly el: (tag: string, className?: string, children?: Array<Node | undefined>) => HTMLElement;
  readonly emptyState: (message: string) => HTMLElement;
  readonly errorFrom: (value: unknown) => string | undefined;
  readonly errorState: (message: string) => HTMLElement;
  readonly icon: (name: string, extraClass?: string) => HTMLElement;
  readonly iconButton: (
    label: string, iconName: string, action: () => void, className: string,
  ) => HTMLButtonElement;
  readonly iconSummary: (label: string, iconName: string) => HTMLElement;
  readonly input: (
    label: string, id: string, value: string, placeholder: string, visuallyHiddenLabel?: boolean,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly objectValue: (value: unknown) => Model | undefined;
  readonly ownerDataError: (value: unknown, expected: string) => HTMLElement;
  readonly publicationAttemptId: (value: unknown) => string | undefined;
  readonly rerender: () => void;
  readonly refreshStableDetails: (key: string, className: string, openByDefault?: boolean) => HTMLDetailsElement;
  readonly resultCard: (value: unknown) => HTMLElement;
  readonly searchInput: (
    label: string, id: string, value: string, placeholder: string, visuallyHiddenLabel?: boolean,
  ) => { wrapper: HTMLElement; control: HTMLInputElement };
  readonly select: (
    label: string, id: string, options: string[][], value: string,
  ) => { wrapper: HTMLElement; control: HTMLSelectElement };
  readonly send: (message: unknown, whileBusy?: boolean) => void;
  readonly shortHash: (value: string) => string;
  readonly statusPill: (status: string) => HTMLElement;
  readonly stringField: (value: Model, field: string) => string | undefined;
  readonly stringList: (value: unknown) => string[];
  readonly technicalDetails: (value: unknown, key?: string) => HTMLElement;
  readonly text: (tag: string, value: string, className?: string, role?: string) => HTMLElement;
  readonly titled: (tag: string, value: string, className?: string) => HTMLElement;
  readonly topLevelRecords: (value: unknown) => Model[] | undefined;
}

interface PocketHiveScenarioViewsApi {
  render(model: Model, view: PocketHiveScenarioViewPort): HTMLElement;
}

(() => {
type ScenarioSection = 'OVERVIEW' | 'FILES' | 'INPUTS';
type ScenarioSource = 'DEPLOYED' | 'REPOSITORY';
type ScenarioTreeNodeType = 'directory' | 'file';
interface ScenarioTreeEntry {
  readonly node: Model;
  readonly path: string;
  readonly name: string;
  readonly nodeType: ScenarioTreeNodeType;
  readonly children: ScenarioTreeEntry[];
}

let model: Model;
let expandedScenarioId: string | undefined;
let scenarioSearch = '';
let scenarioFolder = 'ALL';
let scenarioSource: ScenarioSource = 'DEPLOYED';
let expandedRepositoryCandidateId: string | null = null;
let repositoryScenarioSection: ScenarioSection = 'FILES';
let repositorySearch = '';
let repositoryWorkspace = 'ALL';
let brandMark: PocketHiveScenarioViewPort['brandMark'];
let button: PocketHiveScenarioViewPort['button'];
let dataRows: PocketHiveScenarioViewPort['dataRows'];
let displayValue: PocketHiveScenarioViewPort['displayValue'];
let el: PocketHiveScenarioViewPort['el'];
let emptyState: PocketHiveScenarioViewPort['emptyState'];
let errorFrom: PocketHiveScenarioViewPort['errorFrom'];
let errorState: PocketHiveScenarioViewPort['errorState'];
let icon: PocketHiveScenarioViewPort['icon'];
let iconButton: PocketHiveScenarioViewPort['iconButton'];
let iconSummary: PocketHiveScenarioViewPort['iconSummary'];
let input: PocketHiveScenarioViewPort['input'];
let objectValue: PocketHiveScenarioViewPort['objectValue'];
let ownerDataError: PocketHiveScenarioViewPort['ownerDataError'];
let publicationAttemptId: PocketHiveScenarioViewPort['publicationAttemptId'];
let refreshStableDetails: PocketHiveScenarioViewPort['refreshStableDetails'];
let rerender: PocketHiveScenarioViewPort['rerender'];
let resultCard: PocketHiveScenarioViewPort['resultCard'];
let searchInput: PocketHiveScenarioViewPort['searchInput'];
let select: PocketHiveScenarioViewPort['select'];
let send: PocketHiveScenarioViewPort['send'];
let shortHash: PocketHiveScenarioViewPort['shortHash'];
let statusPill: PocketHiveScenarioViewPort['statusPill'];
let stringField: PocketHiveScenarioViewPort['stringField'];
let stringList: PocketHiveScenarioViewPort['stringList'];
let technicalDetails: PocketHiveScenarioViewPort['technicalDetails'];
let text: PocketHiveScenarioViewPort['text'];
let titled: PocketHiveScenarioViewPort['titled'];
let topLevelRecords: PocketHiveScenarioViewPort['topLevelRecords'];

function renderView(nextModel: Model, view: PocketHiveScenarioViewPort): HTMLElement {
  model = nextModel;
  ({
    brandMark, button, dataRows, displayValue, el, emptyState, errorFrom, errorState, icon, iconButton,
    iconSummary, input, objectValue, ownerDataError, publicationAttemptId, refreshStableDetails, rerender,
    resultCard, searchInput, select, send, shortHash, statusPill, stringField, stringList,
    technicalDetails, text, titled, topLevelRecords,
  } = view);
  return scenariosView();
}

function scenarioBundleView(): HTMLElement {
  const pending = model.pendingBundle;
  const attemptId = publicationAttemptId(model.bundleResult);
  const result = el('section', `scenario-upload${pending ? ' card' : ''}`);
  if (!pending) {
    const actions = el('div', 'form-actions', [
      iconButton('Choose committed folder', 'folder-opened', () =>
        send({ type: 'validateCommittedBundle' }), 'secondary'),
    ]);
    if (attemptId) {
      actions.append(iconButton('Reconcile attempt', 'sync', () =>
        send({ type: 'reconcilePublicationAttempt', attemptId }), 'guarded'));
    }
    result.append(actions);
  } else {
    result.append(
      text('h3', 'Committed bundle'),
      text('p', 'PocketHive validates the retained committed ZIP before explicit publication.', 'muted'),
    );
    const source = pending.source ?? {};
    result.append(
      titled('p', String(source.bundlePath ?? ''), 'mono truncate'),
      text('p', `${String(pending.fileCount ?? 0)} files · commit ${shortHash(String(source.commit ?? ''))}`, 'muted'),
    );
    const replaceId = input('Scenario ID for REPLACE', 'publicationScenarioId', '', 'db-query-postgres-smoke');
    const actions = el('div', 'form-actions', [
      button('Publish CREATE', () => send({ type: 'publishCommittedBundle', mode: 'CREATE' }), 'primary'),
      button('Publish REPLACE', () => {
        if (replaceId.control.value.trim()) send({
          type: 'publishCommittedBundle', mode: 'REPLACE', scenarioId: replaceId.control.value.trim(),
        });
      }, 'guarded'),
      button('Discard', () => send({ type: 'discardPendingBundle' }), 'quiet'),
    ]);
    result.append(replaceId.wrapper, actions);
  }
  if (model.bundleResult !== undefined) result.append(resultCard(model.bundleResult));
  return result;
}

function scenariosView(): HTMLElement {
  const result = el('div', 'scenario-workspace');
  const deployedCount = topLevelRecords(model.workspaceData)?.length ?? 0;
  const repositoryCount = repositoryCandidateCount(model.repositoryScenarios);
  const sourceSwitch = el('div', 'scenario-source-switch');
  sourceSwitch.setAttribute('role', 'group');
  sourceSwitch.setAttribute('aria-label', 'Scenario source');
  for (const source of ['DEPLOYED', 'REPOSITORY'] as const) {
    const label = source === 'DEPLOYED' ? 'Deployed' : 'Repository';
    const count = source === 'DEPLOYED' ? deployedCount : repositoryCount;
    const control = button(label, () => {
      scenarioSource = source;
      rerender();
    }, `scenario-source-switch__button${scenarioSource === source ? ' active' : ''}`);
    control.setAttribute('aria-pressed', String(scenarioSource === source));
    control.append(text('span', String(count), 'count-badge'));
    sourceSwitch.append(control);
  }
  result.append(sourceSwitch);
  if (scenarioSource === 'DEPLOYED') result.append(scenarioListView(model.workspaceData));
  else result.append(repositoryScenarioView());
  return result;
}

function repositoryScenarioView(): HTMLElement {
  const value = objectValue(model.repositoryScenarios);
  if (!value) {
    const state = model.busy
      ? emptyState('Scanning committed Scenario Bundles…')
      : ownerDataError(model.repositoryScenarios, 'repository scenarios');
    if (!model.repositoryResultCandidateId && publicationAttemptId(model.bundleResult)) {
      return el('section', 'repository-scenarios', [scenarioBundleView(), state]);
    }
    return state;
  }
  const state = stringField(value, 'state');
  if (state === 'NO_WORKSPACE') {
    return emptyState('Open a Git repository as a VS Code workspace to discover committed Scenario Bundles.');
  }
  if (state === 'UNTRUSTED') {
    return emptyState('Trust this workspace before PocketHive runs read-only Git discovery.');
  }
  if (state !== 'SCANNED') return ownerDataError(value, 'repository scenarios');

  const result = el('section', 'repository-scenarios');
  if (model.pendingBundle && !model.repositoryPendingCandidateId) {
    result.append(scenarioBundleView());
  } else if (!model.repositoryResultCandidateId && publicationAttemptId(model.bundleResult)) {
    result.append(scenarioBundleView());
  }
  result.append(el('div', 'repository-scenarios__notice', [
    el('div', 'repository-scenarios__notice-copy', [
      icon('git-commit'),
      text('p', 'Committed HEAD only. Edit, commit, then refresh before validation or deployment.', 'muted'),
    ]),
    iconButton('Choose committed folder', 'folder-opened', () =>
      send({ type: 'validateCommittedBundle' }), 'quiet compact icon-only-at-narrow'),
  ]));
  const repositories = Array.isArray(value.repositories) ? value.repositories as Model[] : [];
  const failures = Array.isArray(value.failures) ? value.failures as Model[] : [];
  if (repositories.length === 0 && failures.length === 0) {
    result.append(emptyState('No canonical scenarios/**/scenario.yaml bundles were found at HEAD.'));
  }
  const candidates: Array<{ repository: Model; candidate: Model }> = [];
  for (const repository of repositories) {
    if (!stringField(repository, 'workspaceName') || !stringField(repository, 'commit')
      || !Array.isArray(repository.candidates)) {
      result.append(ownerDataError(repository, 'Git repository'));
      continue;
    }
    for (const candidate of repository.candidates as Model[]) candidates.push({ repository, candidate });
  }
  const workspaceNames = [...new Set(candidates
    .map(item => stringField(item.repository, 'workspaceName'))
    .filter((name): name is string => Boolean(name)))].sort();
  if (repositoryWorkspace !== 'ALL' && !workspaceNames.includes(repositoryWorkspace)) {
    repositoryWorkspace = 'ALL';
  }
  const search = searchInput(
    'Search repository scenarios', 'repositorySearch', repositorySearch, 'Find a scenario', true,
  );
  search.control.required = false;
  const workspace = select('Workspace', 'repositoryWorkspace', [
    ['ALL', 'All workspaces'], ...workspaceNames.map(name => [name, name]),
  ], repositoryWorkspace);
  const advanced = refreshStableDetails(
    'scenarios:repository:filters',
    'advanced-filters repository-advanced-filters',
  );
  const advancedSummary = iconSummary('Repository filters', 'filter');
  const workspaceBadge = text('span', repositoryWorkspace === 'ALL' ? '' : '1', 'filter-count');
  workspaceBadge.hidden = repositoryWorkspace === 'ALL';
  advancedSummary.append(workspaceBadge);
  advanced.append(advancedSummary, el('div', 'advanced-filters__panel', [workspace.wrapper]));
  const filters = el('div', 'event-search repository-filters', [search.wrapper, advanced]);
  const list = el('div', 'repository-scenario-list');
  const availableIds = new Set(candidates.map(item => stringField(item.candidate, 'candidateId')).filter(Boolean));
  if (expandedRepositoryCandidateId && !availableIds.has(expandedRepositoryCandidateId)) {
    expandedRepositoryCandidateId = null;
  }
  const apply = () => {
    repositorySearch = search.control.value;
    repositoryWorkspace = workspace.control.value;
    workspaceBadge.textContent = repositoryWorkspace === 'ALL' ? '' : '1';
    workspaceBadge.hidden = repositoryWorkspace === 'ALL';
    const query = repositorySearch.trim().toLocaleLowerCase();
    const matches = candidates.filter(item => {
      const workspaceName = stringField(item.repository, 'workspaceName') ?? '';
      const bundlePath = stringField(item.candidate, 'bundlePath') ?? '';
      return (repositoryWorkspace === 'ALL' || workspaceName === repositoryWorkspace)
        && (!query || `${workspaceName} ${bundlePath}`.toLocaleLowerCase().includes(query));
    });
    const focusedVisible = matches.some(item => stringField(item.candidate, 'candidateId')
      === expandedRepositoryCandidateId);
    if (expandedRepositoryCandidateId !== null && !focusedVisible) {
      expandedRepositoryCandidateId = null;
    }
    list.replaceChildren(...matches.map(item => repositoryScenarioCard(item.repository, item.candidate)));
    if (matches.length === 0) list.append(emptyState('No Repository scenarios match these filters.'));
  };
  search.control.addEventListener('input', apply);
  workspace.control.addEventListener('change', apply);
  result.append(filters, list);
  apply();
  for (const failure of failures) {
    result.append(el('article', 'callout repository-scenarios__failure', [
      el('div', 'repository-scenarios__identity', [
        icon('warning'),
        titled('strong', displayValue(failure.workspaceName), 'truncate'),
      ]),
      text('p', displayValue(failure.code), 'muted mono'),
    ]));
  }
  const conflict = objectValue(model.repositoryDeploymentConflict);
  if (conflict) result.append(repositoryDeploymentDialog(conflict));
  return result;
}

function repositoryScenarioCard(repository: Model, candidate: Model): HTMLElement {
  const workspaceName = stringField(repository, 'workspaceName');
  const commit = stringField(repository, 'commit');
  const candidateId = stringField(candidate, 'candidateId');
  const bundlePath = stringField(candidate, 'bundlePath');
  const files = Array.isArray(candidate.files)
    ? candidate.files.filter((path): path is string => typeof path === 'string' && Boolean(path.trim()))
    : undefined;
  if (!workspaceName || !commit || !candidateId || !bundlePath || !files) {
    return ownerDataError(candidate, 'repository scenario candidate');
  }
  const pending = model.repositoryPendingCandidateId === candidateId ? objectValue(model.pendingBundle) : undefined;
  const receipt = objectValue(pending?.validationReceipt);
  const title = (receipt ? stringField(receipt, 'scenarioName') : undefined) ?? bundlePath.split('/').at(-1)!;
  const subtitle = (receipt ? stringField(receipt, 'scenarioId') : undefined) ?? bundlePath;
  const focused = expandedRepositoryCandidateId === candidateId;
  const details = el('details', 'scenario-row repository-scenario');
  if (focused) details.setAttribute('open', '');
  const summary = el('summary', '', [
    el('div', 'scenario-row__identity', [
      brandMark('scenario-mark'),
      el('div', 'scenario-row__copy', [
        titled('strong', title, 'truncate'),
        titled('span', subtitle, 'mono muted truncate'),
      ]),
    ]),
    el('div', 'scenario-row__status', [
      statusPill(receipt ? 'Valid' : 'Repository'),
      icon('chevron-right', 'disclosure-chevron'),
    ]),
  ]);
  summary.addEventListener('click', event => {
    event.preventDefault();
    expandedRepositoryCandidateId = focused ? null : candidateId;
    rerender();
  });
  details.append(summary);
  const body = el('div', 'scenario-row__body repository-scenario__body');
  const actions = el('div', 'repository-scenario__actions', [
    iconButton('Edit', 'edit', () => send({
      type: 'openRepositoryBundleFile', candidateId, path: 'scenario.yaml',
    }), 'quiet'),
    iconButton('Validate', 'pass-filled', () =>
      send({ type: 'validateRepositoryBundle', candidateId }), 'quiet'),
    iconButton('Deploy', 'cloud-upload', () =>
      send({ type: 'deployRepositoryBundle', candidateId }), 'quiet'),
  ]);
  for (const control of Array.from(actions.children) as HTMLButtonElement[]) {
    control.disabled = Boolean(model.busy);
  }
  body.append(actions, el('div', 'compact-tabs scenario-section-tabs repository-scenario__tabs', [
    repositorySectionButton('Overview', 'OVERVIEW'),
    repositorySectionButton('Files', 'FILES'),
    repositorySectionButton('Inputs', 'INPUTS'),
  ]));
  if (repositoryScenarioSection === 'OVERVIEW') {
    body.append(repositoryOverview(repository, bundlePath, receipt));
  } else if (repositoryScenarioSection === 'FILES') {
    body.append(repositoryFiles(candidateId, files));
  } else {
    body.append(repositoryInputs(candidateId, files));
  }
  if (receipt) body.append(repositoryValidation(receipt, files.length));
  details.append(body);
  return details;
}

function repositorySectionButton(label: string, section: ScenarioSection): HTMLButtonElement {
  const control = button(label, () => {
    repositoryScenarioSection = section;
    rerender();
  }, 'compact-tab scenario-section-tab');
  control.append(icon(section === 'OVERVIEW' ? 'preview' : section === 'FILES' ? 'list-tree' : 'symbol-variable'));
  control.setAttribute('aria-pressed', String(repositoryScenarioSection === section));
  return control;
}

function repositoryOverview(repository: Model, bundlePath: string, receipt: Model | undefined): HTMLElement {
  const overview = el('div', 'scenario-detail-grid scenario-overview');
  overview.append(
    scenarioInfoCard('Scenario', receipt
      ? `${displayValue(receipt.scenarioName)} · ${displayValue(receipt.scenarioId)}`
      : 'Validate to load the exact scenario.yaml identity.', '', 'scenario-info-card--full'),
    scenarioInfoCard('Source', bundlePath, 'mono', 'scenario-info-card--full'),
    scenarioInfoCard('Commit', `${displayValue(repository.workspaceName)} · ${shortHash(displayValue(repository.commit))}`,
      'mono', 'scenario-info-card--full'),
  );
  return overview;
}

function repositoryFiles(candidateId: string, files: readonly string[]): HTMLElement {
  const hierarchy = scenarioTreeHierarchy(repositoryTreeNodes(files));
  if (!hierarchy) return ownerDataError(files, 'repository scenario file hierarchy');
  const tree = el('div', 'scenario-tree repository-scenario__tree');
  for (const entry of hierarchy) tree.append(repositoryFileNode(candidateId, entry));
  return tree;
}

function repositoryTreeNodes(files: readonly string[]): Model[] {
  const directories = new Set<string>();
  for (const path of files) {
    const segments = path.split('/');
    for (let index = 1; index < segments.length; index++) {
      directories.add(segments.slice(0, index).join('/'));
    }
  }
  return [
    ...[...directories].sort().map(path => ({
      path, name: path.split('/').at(-1), nodeType: 'directory',
    })),
    ...files.map(path => ({ path, name: path.split('/').at(-1), nodeType: 'file' })),
  ];
}

function repositoryFileNode(candidateId: string, entry: ScenarioTreeEntry): HTMLElement {
  if (entry.nodeType === 'directory') {
    const branch = refreshStableDetails(
      `scenarios:repository:${candidateId}:directory:${entry.path}`,
      'scenario-tree__branch',
      true,
    );
    branch.append(el('summary', 'scenario-tree__row scenario-tree__row--directory', [
      icon('chevron-right', 'scenario-tree__twistie'),
      icon('folder', 'scenario-tree__icon'),
      titled('strong', entry.name, 'truncate'),
    ]));
    const children = el('div', 'scenario-tree__children');
    for (const child of entry.children) children.append(repositoryFileNode(candidateId, child));
    branch.append(children);
    return branch;
  }
  return el('article', 'scenario-tree__row scenario-tree__row--file', [
    el('div', 'scenario-tree__meta', [
      icon('file-code', 'scenario-tree__icon'),
      titled('strong', entry.name, 'truncate'),
    ]),
    el('div', 'scenario-tree__actions', [
      iconButton('Edit', 'edit', () => send({
        type: 'openRepositoryBundleFile', candidateId, path: entry.path,
      }), 'secondary compact'),
    ]),
  ]);
}

function repositoryInputs(candidateId: string, files: readonly string[]): HTMLElement {
  const inputPaths = files.filter(path => path === 'variables.yaml' || path === 'authProfiles.yaml'
    || /^sut\/[^/]+\/sut\.yaml$/.test(path));
  if (inputPaths.length === 0) return emptyState('No variables, auth profiles, or SUT descriptors are committed.');
  return el('div', 'repository-scenario__inputs', inputPaths.map(path => el('div', 'repository-scenario__input', [
    el('div', 'repository-scenarios__identity', [icon('symbol-variable'), titled('span', path, 'mono truncate')]),
    iconButton('Edit', 'edit', () => send({
      type: 'openRepositoryBundleFile', candidateId, path,
    }), 'secondary compact'),
  ])));
}

function repositoryValidation(receipt: Model, fileCount: number): HTMLElement {
  return el('div', 'repository-scenario__validation', [
    icon('pass-filled'),
    text('strong', 'Valid'),
    text('span', `${fileCount} ${fileCount === 1 ? 'file' : 'files'} checked`, 'muted'),
    titled('span', displayValue(receipt.scenarioId), 'mono truncate'),
  ]);
}

function repositoryDeploymentDialog(conflict: Model): HTMLElement {
  const candidateId = stringField(conflict, 'candidateId');
  const scenarioId = stringField(conflict, 'scenarioId');
  const scenarioName = stringField(conflict, 'scenarioName');
  const suggestedId = stringField(conflict, 'suggestedScenarioId');
  const suggestedName = stringField(conflict, 'suggestedScenarioName');
  if (!candidateId || !scenarioId || !scenarioName || !suggestedId || !suggestedName) {
    return ownerDataError(conflict, 'repository deployment conflict');
  }
  const renameId = input('New scenario ID', 'repositoryRenameScenarioId', suggestedId, suggestedId);
  const renameName = input('New scenario name', 'repositoryRenameScenarioName', suggestedName, suggestedName);
  const dialog = el('section', 'repository-deployment-dialog', [
    el('div', 'repository-deployment-dialog__panel', [
      el('div', 'repository-deployment-dialog__heading', [
        icon('warning'),
        el('div', '', [text('h2', 'Scenario already deployed'),
          text('p', `${scenarioName} (${scenarioId}) already exists. Choose one explicit path.`, 'muted')]),
      ]),
      el('div', 'repository-deployment-dialog__choice', [
        text('h3', 'Replace existing'),
        text('p', 'Publish the exact validated committed bytes over the existing scenario.', 'muted'),
        iconButton('Replace existing', 'replace-all', () =>
          send({ type: 'replaceRepositoryBundle', candidateId }), 'guarded'),
      ]),
      el('div', 'repository-deployment-dialog__choice', [
        text('h3', 'Rename source'),
        text('p', 'PocketHive opens local scenario.yaml. Apply these values, commit, refresh, validate, and deploy again.', 'muted'),
        renameId.wrapper,
        renameName.wrapper,
        iconButton('Open scenario.yaml', 'go-to-file', () => send({
          type: 'openRepositoryRename', candidateId,
          scenarioId: renameId.control.value,
          scenarioName: renameName.control.value,
        }), 'primary'),
      ]),
      button('Cancel', () => send({ type: 'discardPendingBundle' }), 'quiet'),
    ]),
  ]);
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.setAttribute('aria-label', 'Scenario deployment conflict');
  return dialog;
}

function repositoryCandidateCount(value: unknown): number {
  const repositoryView = objectValue(value);
  if (!repositoryView || !Array.isArray(repositoryView.repositories)) return 0;
  return (repositoryView.repositories as Model[]).reduce((count, repository) =>
    count + (Array.isArray(repository.candidates) ? repository.candidates.length : 0), 0);
}

function scenarioListView(value: unknown): HTMLElement {
  const scenarios = topLevelRecords(value);
  if (scenarios === undefined) return ownerDataError(value, 'scenario list');
  if (scenarios.length === 0) return emptyState('No deployed Scenario Bundles are visible.');
  const result = el('section', 'scenario-catalogue');
  const folders = [...new Set(scenarios.map(item => stringField(item, 'folderPath')).filter((item): item is string => Boolean(item)))].sort();
  const search = searchInput('Search bundles', 'scenarioSearch', scenarioSearch, 'Find a bundle');
  search.control.required = false;
  const folder = select('Folder', 'scenarioFolder', [['ALL', 'All folders'], ...folders.map(item => [item, item])], scenarioFolder);
  const advanced = refreshStableDetails(
    'scenarios:deployed:filters',
    'advanced-filters scenario-advanced-filters',
  );
  const advancedSummary = iconSummary('Scenario filters', 'filter');
  const folderBadge = text('span', scenarioFolder === 'ALL' ? 'All' : '1', 'filter-count');
  advancedSummary.append(folderBadge);
  advanced.append(advancedSummary, el('div', 'advanced-filters__panel', [folder.wrapper]));
  const filters = el('div', 'filter-bar scenario-filters', [search.wrapper, advanced]);
  const list = el('div', 'scenario-list');
  const apply = () => {
    scenarioSearch = search.control.value;
    scenarioFolder = folder.control.value;
    folderBadge.textContent = scenarioFolder === 'ALL' ? 'All' : '1';
    const query = scenarioSearch.trim().toLocaleLowerCase();
    const filtered = scenarios.filter(item => {
      const exactFolder = stringField(item, 'folderPath') ?? '';
      const searchable = [
        stringField(item, 'id'),
        stringField(item, 'name'),
        stringField(item, 'bundleKey'),
        stringField(item, 'description'),
        exactFolder,
      ]
        .filter(Boolean).join(' ').toLocaleLowerCase();
      return (scenarioFolder === 'ALL' || exactFolder === scenarioFolder) && (!query || searchable.includes(query));
    });
    list.replaceChildren(...filtered.map(scenarioRow));
    if (filtered.length === 0) list.append(emptyState('No Scenario Bundles match these filters.'));
  };
  search.control.addEventListener('input', apply);
  folder.control.addEventListener('change', apply);
  result.append(filters, list);
  apply();
  return result;
}

function scenarioRow(scenario: Model): HTMLElement {
  const bundleKey = stringField(scenario, 'bundleKey');
  const scenarioId = stringField(scenario, 'id');
  const name = stringField(scenario, 'name') ?? bundleKey ?? scenarioId;
  if (!bundleKey || !name) return ownerDataError(scenario, 'scenario record');
  const rowId = scenarioRowId(scenarioId, bundleKey);
  const focused = model.scenarioFocusScenarioId === scenarioId && model.scenarioFocusBundleKey === bundleKey;
  const section = focused ? model.scenarioFocusSection as ScenarioSection ?? 'OVERVIEW' : undefined;
  const defunct = scenario.defunct === true;
  const details = el('details', 'scenario-row');
  if (expandedScenarioId === rowId || focused) details.setAttribute('open', '');
  const summaryMeta = scenarioId ? scenarioId : bundleKey;
  details.append(el('summary', '', [
    el('div', 'scenario-row__identity', [
      brandMark('scenario-mark'),
      el('div', 'scenario-row__copy', [
        titled('strong', name, 'truncate'),
        titled('span', summaryMeta, 'mono muted truncate'),
      ]),
    ]),
    el('div', 'scenario-row__status', [
      statusPill(defunct ? 'Defunct' : 'Deployed'),
      icon('chevron-right', 'disclosure-chevron'),
    ]),
  ]));
  const body = el('div', 'scenario-row__body');
  body.append(dataRows([
    ['Scenario ID', scenarioId ?? 'Unavailable'],
    ['Bundle key', bundleKey],
    ['Folder', displayValue(scenario.folderPath)],
    ['Bundle path', displayValue(scenario.bundlePath)],
  ]));
  if (scenarioId) {
    const sectionTabs = el('div', 'compact-tabs scenario-section-tabs', [
      scenarioSectionButton('Overview', 'OVERVIEW', scenarioId, bundleKey, section, rowId),
      scenarioSectionButton('Files', 'FILES', scenarioId, bundleKey, section, rowId),
      scenarioSectionButton('Inputs', 'INPUTS', scenarioId, bundleKey, section, rowId),
    ]);
    body.append(sectionTabs);
    if (section === 'OVERVIEW') body.append(scenarioOverviewSection(scenario));
    if (section === 'FILES') body.append(scenarioFilesSection(bundleKey));
    if (section === 'INPUTS') body.append(scenarioInputsSection(bundleKey));
  } else {
    body.append(text('p', 'This bundle is defunct or missing a canonical scenario ID, so bundle drill-down actions are unavailable.', 'muted callout'));
  }
  body.append(technicalDetails(scenario, `scenarios:deployed:${rowId}:technical`));
  details.append(body);
  return details;
}

function scenarioSectionButton(
  label: string,
  section: ScenarioSection,
  scenarioId: string,
  bundleKey: string,
  activeSection: ScenarioSection | undefined,
  rowId: string,
): HTMLButtonElement {
  const control = button(label, () => {
    expandedScenarioId = rowId;
    send({ type: 'selectScenarioSection', scenarioId, bundleKey, section });
  }, 'compact-tab scenario-section-tab');
  control.append(icon(section === 'OVERVIEW' ? 'preview' : section === 'FILES' ? 'list-tree' : 'symbol-variable'));
  control.setAttribute('aria-pressed', String(activeSection === section));
  return control;
}

function scenarioOverviewSection(scenario: Model): HTMLElement {
  const cards = el('div', 'scenario-detail-grid scenario-overview');
  if (stringField(scenario, 'description')) {
    cards.append(scenarioInfoCard('Description', String(scenario.description), '', 'scenario-info-card--full'));
  }
  if (stringField(scenario, 'controllerImage')) {
    cards.append(scenarioInfoCard('Controller', String(scenario.controllerImage), 'mono', 'scenario-info-card--full'));
  }
  const bees = Array.isArray(scenario.bees) ? scenario.bees as Model[] : [];
  if (bees.length > 0) {
    cards.append(el('article', 'card scenario-info-card scenario-info-card--full', [
      text('span', 'Bees', 'eyebrow'),
      el('div', 'scenario-bees', bees.map(bee => text(
        'span',
        [stringField(bee, 'role') ?? 'worker', stringField(bee, 'image')].filter(Boolean).join(' · '),
        'scenario-chip',
      ))),
    ]));
  }
  return cards.children.length > 0 ? cards : emptyState('No additional overview metadata was reported for this bundle.');
}

function scenarioInfoCard(label: string, value: string, valueClass = '', cardClass = ''): HTMLElement {
  return el('article', `card scenario-info-card${cardClass ? ` ${cardClass}` : ''}`, [
    text('span', label, 'eyebrow'),
    titled('p', value, `scenario-info-card__value${valueClass ? ` ${valueClass}` : ''}`),
  ]);
}

function scenarioFilesSection(bundleKey: string): HTMLElement {
  if (model.scenarioFocusBundleKey !== bundleKey || model.scenarioFocusTree === undefined) {
    return emptyState(model.busy ? 'Loading deployed bundle tree…' : 'Choose Files to inspect the deployed bundle tree.');
  }
  const tree = objectValue(model.scenarioFocusTree);
  if (errorFrom(model.scenarioFocusTree)) return errorState(String(errorFrom(model.scenarioFocusTree)));
  const nodes = Array.isArray(tree?.nodes) ? tree.nodes.filter(item => objectValue(item)) as Model[] : undefined;
  if (!nodes) return ownerDataError(model.scenarioFocusTree, 'scenario bundle tree');
  if (nodes.length === 0) return emptyState('No deployed files were reported for this bundle.');
  const roots = scenarioTreeHierarchy(nodes);
  if (!roots) return ownerDataError(model.scenarioFocusTree, 'scenario bundle tree hierarchy');
  const list = el('div', 'scenario-tree');
  for (const entry of roots) list.append(scenarioFileNode(bundleKey, entry));
  return list;
}

function scenarioTreeHierarchy(nodes: Model[]): ScenarioTreeEntry[] | undefined {
  const entries = new Map<string, ScenarioTreeEntry>();
  for (const node of nodes) {
    const path = stringField(node, 'path');
    const name = stringField(node, 'name');
    const rawNodeType = stringField(node, 'nodeType');
    if (!path || !name || (rawNodeType !== 'directory' && rawNodeType !== 'file')
      || path.split('/').at(-1) !== name || entries.has(path)) return undefined;
    entries.set(path, { node, path, name, nodeType: rawNodeType, children: [] });
  }

  const roots: ScenarioTreeEntry[] = [];
  for (const entry of entries.values()) {
    const separator = entry.path.lastIndexOf('/');
    if (separator < 0) {
      roots.push(entry);
      continue;
    }
    const parent = entries.get(entry.path.slice(0, separator));
    if (!parent || parent.nodeType !== 'directory') return undefined;
    parent.children.push(entry);
  }
  return roots;
}

function scenarioFileNode(bundleKey: string, entry: ScenarioTreeEntry): HTMLElement {
  if (entry.nodeType === 'directory') {
    const branch = refreshStableDetails(
      `scenarios:deployed:${bundleKey}:directory:${entry.path}`,
      'scenario-tree__branch',
      true,
    );
    branch.append(el('summary', 'scenario-tree__row scenario-tree__row--directory', [
      icon('chevron-right', 'scenario-tree__twistie'),
      icon('folder', 'scenario-tree__icon'),
      titled('strong', entry.name, 'truncate'),
    ]));
    const children = el('div', 'scenario-tree__children');
    for (const child of entry.children) children.append(scenarioFileNode(bundleKey, child));
    branch.append(children);
    return branch;
  }

  const row = el('article', 'scenario-tree__row scenario-tree__row--file');
  const meta = el('div', 'scenario-tree__meta', [
    icon('file-code', 'scenario-tree__icon'),
    titled('strong', entry.name, 'truncate'),
  ]);
  row.append(meta);
  const editorKind = stringField(entry.node, 'editorKind');
  const label = editorKind === 'unsupported' ? 'Metadata' : 'Preview';
  row.append(el('div', 'scenario-tree__actions', [
    text('span', displayValue(entry.node.size), 'muted mono'),
    iconButton(label, editorKind === 'unsupported' ? 'info' : 'preview', () =>
      send({ type: 'openScenarioBundleFile', bundleKey, path: entry.path }), 'secondary compact'),
  ]));
  return row;
}

function scenarioInputsSection(bundleKey: string): HTMLElement {
  if (model.scenarioFocusBundleKey !== bundleKey || model.scenarioFocusInputs === undefined) {
    return emptyState(model.busy ? 'Loading scenario inputs…' : 'Choose Inputs to inspect SUTs and supporting files.');
  }
  const inputs = objectValue(model.scenarioFocusInputs);
  if (errorFrom(model.scenarioFocusInputs)) return errorState(String(errorFrom(model.scenarioFocusInputs)));
  if (!inputs) return ownerDataError(model.scenarioFocusInputs, 'scenario inputs');
  const result = el('div', 'scenario-inputs');
  result.append(el('div', 'scenario-detail-grid', [
    scenarioFilePresenceCard('Variables', inputs.variablesPath, bundleKey),
    scenarioFilePresenceCard('Auth profiles', inputs.authProfilesPath, bundleKey),
  ]));
  const suts = Array.isArray(inputs.suts) ? inputs.suts.filter(item => objectValue(item)) as Model[] : [];
  if (suts.length === 0) {
    result.append(emptyState('No bundle-local SUT descriptors were reported for this bundle.'));
    return result;
  }
  const list = el('div', 'data-list');
  for (const sut of suts) list.append(scenarioSutCard(sut));
  result.append(list);
  return result;
}

function scenarioFilePresenceCard(label: string, path: unknown, bundleKey: string): HTMLElement {
  const exactPath = typeof path === 'string' && path.trim() ? path.trim() : undefined;
  const card = el('article', 'card scenario-info-card', [
    text('span', label, 'eyebrow'),
    titled('p', exactPath ?? 'Not present in deployed bundle', `${exactPath ? 'mono truncate' : 'muted'}`),
  ]);
  if (exactPath) {
    card.append(el('div', 'actions', [
      iconButton('Preview', 'preview', () =>
        send({ type: 'openScenarioBundleFile', bundleKey, path: exactPath }), 'secondary compact'),
    ]));
  }
  return card;
}

function scenarioSutCard(value: Model): HTMLElement {
  const sutId = stringField(value, 'sutId') ?? 'SUT';
  const error = errorFrom(value.error);
  const card = el('article', 'card data-card');
  card.append(el('div', 'data-heading', [
    el('div', '', [text('span', 'Bundle-local SUT', 'eyebrow'), titled('h3', sutId, 'truncate')]),
  ]));
  if (error) {
    card.append(errorState(error));
    return card;
  }
  const descriptor = objectValue(value.descriptor);
  if (!descriptor) return ownerDataError(value, 'bundle-local SUT');
  const endpoints = objectValue(descriptor.endpoints) ?? {};
  const endpointRows = Object.entries(endpoints).map(([endpointId, endpointValue]) => {
    const endpoint = objectValue(endpointValue) ?? {};
    return [
      endpointId,
      typeof endpoint.baseUrl === 'string' && endpoint.baseUrl.trim()
        ? endpoint.baseUrl.trim()
        : JSON.stringify(endpoint),
    ] as string[];
  });
  card.append(dataRows([
    ['Name', displayValue(descriptor.name)],
    ['Endpoint count', String(endpointRows.length)],
  ]));
  if (endpointRows.length > 0) {
    card.append(el('div', 'scenario-endpoints', [
      text('span', 'Endpoints', 'eyebrow'),
      dataRows(endpointRows),
    ]));
  }
  return card;
}

function scenarioRowId(scenarioId: string | undefined, bundleKey: string): string {
  return `${scenarioId ?? 'bundle'}::${bundleKey}`;
}

const api: PocketHiveScenarioViewsApi = Object.freeze({ render: renderView });
(globalThis as typeof globalThis & { PocketHiveScenarioViews: PocketHiveScenarioViewsApi })
  .PocketHiveScenarioViews = api;
})();
