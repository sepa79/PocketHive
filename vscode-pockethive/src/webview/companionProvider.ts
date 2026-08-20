import { randomUUID } from 'node:crypto';
import * as vscode from 'vscode';

import { ActiveMcpConnection } from '../mcp/activeConnection';
import { McpHttpClient } from '../mcp/httpClient';
import { ConnectionAttempt } from '../connection/connectionAttempt';
import {
  ConnectionAttemptView,
  ConnectionContractError,
  ConnectionEvidence,
  McpConnectionProfile,
  OAuthSessionStore,
} from '../connection/contracts';
import { PocketHiveEndpointValidator } from '../connection/endpointValidator';
import { LoopbackBrowserAuthorization } from '../connection/loopbackBrowser';
import { PocketHiveOAuthAuthentication } from '../connection/oauthAuthentication';
import { AuthorizedMcpSession } from '../connection/authorizedMcpSession';
import { createConnectionProfile } from '../connection/profile';
import { debugToolCall, DEBUG_ACTIONS } from '../debug/actions';
import { ScopedMcpToolRunner } from '../operations/scopedMcpToolRunner';
import {
  lifecycleToolCall,
  primaryActionsForSwarms,
  swarmIdsForOperation,
  SwarmOperation,
  SWARM_OPERATIONS,
} from '../operations/swarmOperations';
import { openJsonPreview, openPreviewDocument } from '../preview';
import { McpConnectionProfileRepository } from '../storage/profileRepository';
import { GitBundlePackager } from '../scenarios/gitBundlePackager';
import {
  PendingBundlePublication,
  PublicationMode,
  ScenarioBundleCoordinator,
} from '../scenarios/scenarioBundleCoordinator';
import {
  previewLanguageForPath,
  scenarioReadText,
  scenarioReadToolCall,
  SCENARIO_ASSETS,
  ScenarioAsset,
} from '../scenarios/scenarioReads';
import { CompanionTab, decodeWebviewCommand, ScenarioSection } from './messages';
import { CurrentView } from './currentView';
import { boundCompanionViewModel } from './viewModelBoundary';
import { SIDEBAR_EVENT_LIMIT, workspaceToolCall } from './workspaceTool';
import {
  SESSION_ACTIVITIES,
  SessionActivity,
  SessionPresentation,
  sessionPresentation,
} from './sessionPresentation';

type LiveStatus = 'Connected' | 'Connecting' | 'Needs sign-in' | 'Unavailable' | 'Not connected';

interface ProfileRow extends McpConnectionProfile {
  readonly status: LiveStatus;
  readonly principalLabel?: string;
}

interface CreateSwarmFormState {
  readonly templates: unknown;
  readonly selectedTemplateId?: string;
  readonly selectedScenarioId?: string;
  readonly sutIds?: readonly string[];
}

interface CompanionViewModel {
  readonly page: 'environments' | 'add' | 'workspace';
  readonly profiles: ProfileRow[];
  readonly draft?: McpConnectionProfile;
  readonly attempt?: ConnectionAttemptView;
  readonly activeProfile?: ProfileRow;
  readonly activeTab: CompanionTab;
  readonly workspaceData?: unknown;
  readonly swarmOperations: typeof SWARM_OPERATIONS;
  readonly swarmPrimaryActions: Readonly<Record<string, SwarmOperation>>;
  readonly createSwarmForm?: CreateSwarmFormState;
  readonly journalSwarmId?: string;
  readonly journalRunId?: string;
  readonly journalResult?: unknown;
  readonly swarmHistorySwarmId?: string;
  readonly swarmHistoryResult?: unknown;
  readonly swarmOperationResult?: unknown;
  readonly debugSwarmId?: string;
  readonly debugRuntimeId?: string;
  readonly debugResult?: unknown;
  readonly scenarioFocusScenarioId?: string;
  readonly scenarioFocusBundleKey?: string;
  readonly scenarioFocusSection?: ScenarioSection;
  readonly scenarioFocusTree?: unknown;
  readonly scenarioFocusInputs?: unknown;
  readonly pendingBundle?: unknown;
  readonly bundleResult?: unknown;
  readonly debugActions: typeof DEBUG_ACTIONS;
  readonly session: SessionPresentation;
  readonly busy: boolean;
}

export class PocketHiveCompanionProvider implements vscode.WebviewViewProvider, vscode.Disposable {
  static readonly viewType = 'pockethive.companion';

  private readonly repository: McpConnectionProfileRepository;
  private readonly endpoints = new PocketHiveEndpointValidator();
  private readonly activeConnection: ActiveMcpConnection;
  private readonly authentication: PocketHiveOAuthAuthentication;
  private readonly authorizedSession: AuthorizedMcpSession;
  private readonly bundles: ScenarioBundleCoordinator;
  private readonly scopedTools: ScopedMcpToolRunner;
  private readonly live = new Map<string, { status: LiveStatus; evidence?: ConnectionEvidence }>();
  private readonly currentView = new CurrentView<vscode.WebviewView>();
  private page: CompanionViewModel['page'] = 'environments';
  private activeTab: CompanionTab = 'Hive';
  private draft?: McpConnectionProfile;
  private attempt?: ConnectionAttempt;
  private attemptView?: ConnectionAttemptView;
  private workspaceData?: unknown;
  private createSwarmForm?: CreateSwarmFormState;
  private journalSwarmId?: string;
  private journalRunId?: string;
  private journalResult?: unknown;
  private swarmHistorySwarmId?: string;
  private swarmHistoryResult?: unknown;
  private swarmOperationResult?: unknown;
  private debugSwarmId?: string;
  private debugRuntimeId?: string;
  private debugResult?: unknown;
  private scenarioFocusScenarioId?: string;
  private scenarioFocusBundleKey?: string;
  private scenarioFocusSection: ScenarioSection = 'OVERVIEW';
  private scenarioFocusTree?: unknown;
  private scenarioFocusInputs?: unknown;
  private pendingBundle?: PendingBundlePublication;
  private bundleResult?: unknown;
  private sessionActivity: SessionActivity = SESSION_ACTIVITIES.NEEDS_SIGN_IN;
  private busy = false;
  private disposed = false;

  constructor(private readonly context: vscode.ExtensionContext) {
    const version = extensionVersion(context.extension.packageJSON);
    const clients = () => new McpHttpClient(version);
    this.activeConnection = new ActiveMcpConnection(clients);
    const secrets: OAuthSessionStore = {
      get: key => context.secrets.get(key),
      store: (key, value) => context.secrets.store(key, value),
      delete: key => context.secrets.delete(key),
    };
    this.repository = new McpConnectionProfileRepository(
      context.globalState, context.workspaceState, secrets,
    );
    const browser = new LoopbackBrowserAuthorization(url =>
      vscode.env.openExternal(vscode.Uri.parse(url)));
    this.authentication = new PocketHiveOAuthAuthentication(fetch, browser, secrets);
    this.authorizedSession = new AuthorizedMcpSession(
      this.endpoints, this.authentication, this.activeConnection,
    );
    this.bundles = new ScenarioBundleCoordinator(
      new GitBundlePackager(), this.endpoints, this.authentication, clients,
    );
    this.scopedTools = new ScopedMcpToolRunner(this.endpoints, this.authentication, clients);
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    this.currentView.attach(view);
    view.webview.options = {
      enableScripts: true,
      localResourceRoots: [
        vscode.Uri.joinPath(this.context.extensionUri, 'resources'),
        vscode.Uri.joinPath(this.context.extensionUri, 'media'),
        vscode.Uri.joinPath(this.context.extensionUri, 'out', 'webview'),
      ],
    };
    view.webview.html = html(view.webview, this.context.extensionUri);
    this.context.subscriptions.push(
      view.webview.onDidReceiveMessage(value => this.receive(value)),
      view.onDidDispose(() => this.currentView.detach(view)),
    );
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    const view = this.currentView.value();
    if (view) this.currentView.detach(view);
    await this.discardPendingBundle();
    await this.activeConnection.close().catch(() => undefined);
  }

  private async receive(value: unknown): Promise<void> {
    try {
      const command = decodeWebviewCommand(value);
      if (this.busy && command.type !== 'ready' && command.type !== 'cancelConnection') {
        throw new ConnectionContractError('COMPANION_BUSY', 'Wait for the current operation to finish');
      }
      switch (command.type) {
        case 'ready':
          await this.postView();
          break;
        case 'addEnvironment':
          this.page = 'add';
          this.draft = undefined;
          this.attempt = undefined;
          this.attemptView = undefined;
          await this.postView();
          break;
        case 'connect':
          await this.connect(command.displayName, command.mcpUrl, command.endpointSecurityMode);
          break;
        case 'retryTest':
          await this.runAttempt(() => this.requireAttempt().retryTest());
          break;
        case 'signInAgain':
          await this.runAttempt(() => this.requireAttempt().signInAgain());
          break;
        case 'cancelConnection':
          this.attemptView = this.requireAttempt().cancel();
          await this.postView();
          break;
        case 'saveOpen':
          await this.saveAndOpen();
          break;
        case 'openEnvironment':
          await this.open(command.profileId);
          break;
        case 'removeEnvironment':
          await this.repository.remove(command.profileId);
          this.live.delete(command.profileId);
          await this.postView();
          break;
        case 'backToEnvironments':
          await this.closeWorkspace();
          break;
        case 'reauthorizeEnvironment':
          await this.reauthorizeEnvironment();
          break;
        case 'signOut':
          await this.signOut();
          break;
        case 'selectTab':
          this.activeTab = command.tab;
          this.debugResult = undefined;
          await this.loadTab();
          break;
        case 'refresh':
          await this.loadTab();
          break;
        case 'openCreateSwarm':
          await this.openCreateSwarm();
          break;
        case 'cancelCreateSwarm':
          this.createSwarmForm = undefined;
          await this.postView();
          break;
        case 'selectCreateSwarmTemplate':
          await this.selectCreateSwarmTemplate(command.templateId, command.scenarioId);
          break;
        case 'submitCreateSwarm':
          await this.submitCreateSwarm(command.swarmId, command.templateId, command.scenarioId,
            command.sutId, command.variablesProfileId);
          break;
        case 'selectJournalSwarm':
          this.journalSwarmId = command.swarmId;
          this.journalRunId = undefined;
          this.journalResult = undefined;
          await this.loadJournal();
          break;
        case 'loadSwarmHistory':
          await this.loadSwarmHistory(command.swarmId);
          break;
        case 'openSwarmDetails':
          await this.openSwarmDetails(command.swarmId);
          break;
        case 'openJournalRun':
          this.activeTab = 'Journal';
          this.journalSwarmId = command.swarmId;
          this.journalRunId = command.runId;
          this.journalResult = undefined;
          await this.loadTab();
          break;
        case 'runSwarmOperation':
          await this.runSwarmOperation(command.action, command.swarmId);
          break;
        case 'runSwarmBatchOperation':
          await this.runSwarmBatchOperation(command.action);
          break;
        case 'openDebugForSwarm':
          this.activeTab = 'Debug';
          this.debugSwarmId = command.swarmId;
          this.debugRuntimeId = undefined;
          this.debugResult = undefined;
          await this.loadTab();
          break;
        case 'selectDebugSwarm':
          this.debugSwarmId = command.swarmId;
          this.debugRuntimeId = undefined;
          this.debugResult = undefined;
          await this.postView();
          break;
        case 'selectDebugWorker':
          this.debugRuntimeId = command.runtimeId;
          this.debugResult = undefined;
          await this.postView();
          break;
        case 'runDebug':
          await this.runDebug(command.action, command.tailLines);
          break;
        case 'openScenarioDetails':
          await this.openScenarioDetails(command.scenarioId);
          break;
        case 'openScenarioRaw':
          await this.openScenarioAsset(command.scenarioId, SCENARIO_ASSETS.RAW);
          break;
        case 'openScenarioSchema':
          await this.openScenarioAsset(command.scenarioId, SCENARIO_ASSETS.SCHEMA);
          break;
        case 'openScenarioTemplate':
          await this.openScenarioAsset(command.scenarioId, SCENARIO_ASSETS.TEMPLATE);
          break;
        case 'selectScenarioSection':
          await this.selectScenarioSection(command.scenarioId, command.bundleKey, command.section);
          break;
        case 'openScenarioBundleFile':
          await this.openScenarioBundleFile(command.bundleKey, command.path);
          break;
        case 'validateCommittedBundle':
          await this.validateCommittedBundle();
          break;
        case 'publishCommittedBundle':
          await this.publishCommittedBundle(command.mode, command.scenarioId);
          break;
        case 'reconcilePublicationAttempt':
          await this.reconcilePublicationAttempt(command.attemptId);
          break;
        case 'discardPendingBundle':
          await this.discardPendingBundle();
          await this.postView();
          break;
      }
    } catch (error) {
      await this.post({ type: 'error', error: safeError(error) });
    }
  }

  private async connect(
    displayName: string,
    mcpUrl: string,
    endpointSecurityMode: 'REMOTE_HTTPS' | 'LOCAL_LOOPBACK_HTTP',
  ): Promise<void> {
    const id = randomUUID();
    this.draft = createConnectionProfile({
      id,
      displayName,
      mcpUrl,
      endpointSecurityMode,
      secretKey: `pockethive.oauth.${id}`,
    });
    this.attempt = this.newAttempt(this.draft);
    await this.runAttempt(() => this.requireAttempt().connect());
  }

  private async open(profileId: string): Promise<void> {
    const profile = this.repository.list().find(item => item.id === profileId);
    if (!profile) throw new ConnectionContractError('PROFILE_NOT_FOUND', profileId);
    await this.activeConnection.close();
    await this.clearEnvironmentState();
    this.draft = profile;
    this.page = 'workspace';
    this.sessionActivity = SESSION_ACTIVITIES.RESTORING;
    this.live.set(profile.id, { status: 'Connecting' });
    this.busy = true;
    await this.postView();
    try {
      const evidence = await this.authorizedSession.ensure(profile);
      if (!evidence) {
        throw new ConnectionContractError('MCP_CONNECTION_EVIDENCE_MISSING', 'MCP_CONNECTION_EVIDENCE_MISSING');
      }
      await this.repository.select(profile.id);
      this.sessionActivity = SESSION_ACTIVITIES.ACTIVE;
      this.live.set(profile.id, { status: 'Connected', evidence });
    } catch (error) {
      this.markSessionFailure(profile, error);
    } finally {
      this.busy = false;
      await this.postView();
    }
    if (this.sessionActivity === SESSION_ACTIVITIES.ACTIVE) await this.loadTab();
  }

  private async saveAndOpen(): Promise<void> {
    const profile = this.requireDraft();
    const attempt = this.requireAttempt();
    if (attempt.view().state !== 'READY_TO_SAVE') {
      throw new ConnectionContractError('CONNECTION_NOT_READY_TO_SAVE', profile.id);
    }
    await this.repository.save(profile);
    await this.repository.select(profile.id);
    this.attemptView = attempt.save();
    this.live.set(profile.id, { status: 'Connected', evidence: this.attemptView.evidence });
    const session = await this.authentication.session(profile);
    if (!session) throw new ConnectionContractError('OAUTH_SESSION_MISSING', 'OAUTH_SESSION_MISSING');
    this.authorizedSession.bind(profile, session);
    this.sessionActivity = SESSION_ACTIVITIES.ACTIVE;
    this.page = 'workspace';
    await this.loadTab();
  }

  private async runAttempt(action: () => Promise<ConnectionAttemptView>): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      this.attemptView = await action();
      if (this.draft) {
        const status: LiveStatus = this.attemptView.state === 'READY_TO_SAVE'
          ? 'Connected'
          : this.attemptView.state === 'AUTHENTICATION_FAILED' || this.attemptView.state === 'CANCELLED'
            ? 'Needs sign-in'
            : this.attemptView.state === 'CONNECTION_TEST_FAILED'
              ? 'Unavailable'
              : 'Not connected';
        this.live.set(this.draft.id, { status, evidence: this.attemptView.evidence });
      }
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private newAttempt(profile: McpConnectionProfile): ConnectionAttempt {
    return new ConnectionAttempt(
      profile,
      this.endpoints,
      this.authentication,
      this.activeConnection,
      {
        changed: view => {
          this.attemptView = view;
          void this.postView();
        },
      },
    );
  }

  private async loadTab(): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      const call = workspaceToolCall(this.activeTab);
      this.workspaceData = await this.activeConnection.callTool(call.name, call.arguments);
      if (this.activeTab === 'Scenarios') {
        await this.refreshScenarioFocusData();
      }
      if (this.activeTab === 'Journal' && this.journalSwarmId) {
        try {
          this.journalResult = await this.activeConnection.callTool('debug_journal', {
            swarmId: this.journalSwarmId,
            limit: SIDEBAR_EVENT_LIMIT,
            ...(this.journalRunId ? { runId: this.journalRunId } : {}),
          });
        } catch (error) {
          this.journalResult = { error: safeError(error), observedAt: new Date().toISOString() };
        }
      }
    } catch (error) {
      this.workspaceData = { error: safeError(error), observedAt: new Date().toISOString() };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async loadJournal(): Promise<void> {
    if (!this.journalSwarmId) {
      throw new ConnectionContractError('JOURNAL_SWARM_REQUIRED', 'Select an exact swarm');
    }
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.journalResult = await this.activeConnection.callTool('debug_journal', {
        swarmId: this.journalSwarmId,
        limit: SIDEBAR_EVENT_LIMIT,
        ...(this.journalRunId ? { runId: this.journalRunId } : {}),
      });
    } catch (error) {
      this.journalResult = { error: safeError(error), observedAt: new Date().toISOString() };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async loadSwarmHistory(swarmId: string): Promise<void> {
    this.swarmHistorySwarmId = swarmId;
    this.swarmHistoryResult = undefined;
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.swarmHistoryResult = await this.activeConnection.callTool('debug_journal_runs', { swarmId });
    } catch (error) {
      this.swarmHistoryResult = { error: safeError(error), observedAt: new Date().toISOString() };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async openSwarmDetails(swarmId: string): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      await openJsonPreview(`Swarm ${swarmId}`, await this.activeConnection.callTool('swarm_get', { swarmId }));
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async runSwarmOperation(action: SwarmOperation, swarmId: string): Promise<void> {
    const call = lifecycleToolCall(action, swarmId, randomUUID());
    this.busy = true;
    this.swarmOperationResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      if (action === SWARM_OPERATIONS.REMOVE) {
        const approved = await vscode.window.showWarningMessage(
          `Remove swarm “${swarmId}”? This targets the exact selected swarm.`,
          { modal: true },
          'Remove swarm',
        );
        if (approved !== 'Remove swarm') return;
      }
      this.swarmOperationResult = await this.scopedTools.call(
        this.requireDraft(), call.name, call.arguments,
      );
      this.workspaceData = await this.activeConnection.callTool('swarm_list');
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async runSwarmBatchOperation(action: Exclude<SwarmOperation, 'REMOVE'>): Promise<void> {
    const targets = swarmIdsForOperation(this.workspaceData, action);
    if (targets.length === 0) {
      throw new ConnectionContractError('SWARM_BATCH_TARGETS_MISSING', `No swarms are eligible for ${action.toLowerCase()}`);
    }
    this.busy = true;
    this.swarmOperationResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      const profile = this.requireDraft();
      const succeeded: string[] = [];
      const failed: Array<{ swarmId: string; error: { code: string; message: string } }> = [];
      for (const swarmId of targets) {
        try {
          const call = lifecycleToolCall(action, swarmId, randomUUID());
          await this.scopedTools.call(profile, call.name, call.arguments);
          succeeded.push(swarmId);
        } catch (error) {
          failed.push({ swarmId, error: safeError(error) });
        }
      }
      this.swarmOperationResult = { batchOperation: action, requested: targets.length, succeeded, failed };
      this.workspaceData = await this.activeConnection.callTool('swarm_list');
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async openCreateSwarm(): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      const templates = await this.activeConnection.callTool('scenario_templates_catalog');
      const selected = firstCreatableTemplate(templates);
      const sutIds = selected ? await this.readScenarioSutIds(selected.scenarioId) : [];
      this.createSwarmForm = {
        templates,
        selectedTemplateId: selected?.templateId,
        selectedScenarioId: selected?.scenarioId,
        sutIds,
      };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async selectCreateSwarmTemplate(templateId: string, scenarioId: string): Promise<void> {
    const current = this.createSwarmForm;
    if (!current) {
      throw new ConnectionContractError('CREATE_SWARM_FORM_MISSING', 'Open Create swarm first');
    }
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.createSwarmForm = {
        ...current,
        selectedTemplateId: templateId,
        selectedScenarioId: scenarioId,
        sutIds: await this.readScenarioSutIds(scenarioId),
      };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async submitCreateSwarm(
    swarmId: string,
    templateId: string,
    scenarioId: string,
    sutId?: string,
    variablesProfileId?: string,
  ): Promise<void> {
    const form = this.createSwarmForm;
    if (!form) {
      throw new ConnectionContractError('CREATE_SWARM_FORM_MISSING', 'Open Create swarm first');
    }
    requireCreateSwarmSelection(form.templates, templateId, scenarioId);
    const arguments_: Record<string, unknown> = {
      swarmId,
      templateId,
      idempotencyKey: randomUUID(),
    };
    if (sutId) arguments_.sutId = sutId;
    if (variablesProfileId) arguments_.variablesProfileId = variablesProfileId;
    this.busy = true;
    this.swarmOperationResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.swarmOperationResult = await this.scopedTools.call(this.requireDraft(), 'swarm_create', arguments_);
      this.createSwarmForm = undefined;
      this.workspaceData = await this.activeConnection.callTool('swarm_list');
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async runDebug(action: string, tailLines?: number): Promise<void> {
    const call = debugToolCall(action, this.debugSwarmId, this.debugRuntimeId, tailLines);
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.debugResult = await this.activeConnection.callTool(call.name, call.arguments);
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async openScenarioDetails(scenarioId: string): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      await openJsonPreview(`Scenario ${scenarioId}`, await this.activeConnection.callTool('scenario_get', { scenarioId }));
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async openScenarioAsset(scenarioId: string, asset: ScenarioAsset): Promise<void> {
    const path = asset === SCENARIO_ASSETS.RAW ? undefined : await this.promptScenarioAssetPath(asset, scenarioId);
    if (asset !== SCENARIO_ASSETS.RAW && !path) return;
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      const call = scenarioReadToolCall(asset, scenarioId, path);
      const value = await this.activeConnection.callTool(call.name, call.arguments);
      await openPreviewDocument(
        path ? `${scenarioId}/${path}` : `${scenarioId}/scenario.yaml`,
        scenarioReadText(value),
        previewLanguageForPath(path),
      );
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async selectScenarioSection(
    scenarioId: string,
    bundleKey: string,
    section: ScenarioSection,
  ): Promise<void> {
    const sameScenario = this.scenarioFocusScenarioId === scenarioId
      && this.scenarioFocusBundleKey === bundleKey;
    if (!sameScenario) {
      this.scenarioFocusTree = undefined;
      this.scenarioFocusInputs = undefined;
    }
    this.scenarioFocusScenarioId = scenarioId;
    this.scenarioFocusBundleKey = bundleKey;
    this.scenarioFocusSection = section;
    if (section === 'OVERVIEW'
        || (section === 'FILES' && this.scenarioFocusTree !== undefined)
        || (section === 'INPUTS' && this.scenarioFocusInputs !== undefined)) {
      await this.postView();
      return;
    }
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      await this.refreshScenarioFocusData();
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async openScenarioBundleFile(bundleKey: string, path: string): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      const value = await this.activeConnection.callTool('scenario_bundle_file_read', { bundleKey, path });
      const record = value && typeof value === 'object' && !Array.isArray(value)
        ? value as Record<string, unknown>
        : undefined;
      const content = typeof record?.content === 'string' ? record.content : undefined;
      if (content !== undefined) {
        await openPreviewDocument(`${bundleKey}/${path}`, content, previewLanguageForPath(path));
      } else {
        await openJsonPreview(`${bundleKey}/${path}`, value);
      }
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async refreshScenarioFocusData(): Promise<void> {
    if (this.activeTab !== 'Scenarios'
        || !this.scenarioFocusScenarioId
        || !this.scenarioFocusBundleKey) {
      return;
    }
    if (this.scenarioFocusSection === 'FILES' || this.scenarioFocusSection === 'INPUTS') {
      try {
        this.scenarioFocusTree = await this.activeConnection.callTool('scenario_bundle_tree_read', {
          bundleKey: this.scenarioFocusBundleKey,
        });
      } catch (error) {
        this.scenarioFocusTree = { error: safeError(error), observedAt: new Date().toISOString() };
      }
    }
    if (this.scenarioFocusSection === 'INPUTS') {
      try {
        this.scenarioFocusInputs = await this.readScenarioInputs(
          this.scenarioFocusScenarioId,
          this.scenarioFocusBundleKey,
          this.scenarioFocusTree,
        );
      } catch (error) {
        this.scenarioFocusInputs = { error: safeError(error), observedAt: new Date().toISOString() };
      }
    }
  }

  private async readScenarioInputs(
    scenarioId: string,
    bundleKey: string,
    tree: unknown,
  ): Promise<unknown> {
    const sutIds = await this.readScenarioSutIds(scenarioId);
    const suts: Array<Record<string, unknown>> = [];
    for (const sutId of sutIds) {
      try {
        suts.push({
          sutId,
          descriptor: await this.activeConnection.callTool('scenario_sut_get', { scenarioId, sutId }),
        });
      } catch (error) {
        suts.push({ sutId, error: safeError(error) });
      }
    }
    return {
      bundleKey,
      variablesPath: findScenarioFilePath(tree, ['variables.yaml', 'variables.yml']),
      authProfilesPath: findScenarioFilePath(tree, ['authProfiles.yaml', 'authProfiles.yml']),
      suts,
    };
  }

  private async readScenarioSutIds(scenarioId: string): Promise<string[]> {
    const sutIdsValue = await this.activeConnection.callTool('scenario_suts_list', { scenarioId });
    return Array.isArray(sutIdsValue)
      ? sutIdsValue.filter((value): value is string => typeof value === 'string' && value.trim().length > 0)
      : [];
  }

  private async validateCommittedBundle(): Promise<void> {
    if (this.pendingBundle) {
      throw new ConnectionContractError('BUNDLE_PUBLICATION_PENDING', 'Publish or discard the validated bundle first');
    }
    const selected = await vscode.window.showOpenDialog({
      canSelectFiles: false,
      canSelectFolders: true,
      canSelectMany: false,
      openLabel: 'Select committed bundle',
      title: 'Select a committed PocketHive Scenario Bundle directory',
    });
    if (!selected?.[0]) return;
    if (!vscode.workspace.getWorkspaceFolder(selected[0])) {
      throw new ConnectionContractError('BUNDLE_WORKSPACE_REQUIRED', 'Select a directory in the current Git workspace');
    }
    const profile = this.requireDraft();
    this.busy = true;
    this.bundleResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.pendingBundle = await this.bundles.validate(profile, selected[0].fsPath);
      this.bundleResult = { validationReceipt: this.pendingBundle.receipt };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async publishCommittedBundle(mode: PublicationMode, scenarioId?: string): Promise<void> {
    const pending = this.pendingBundle;
    if (!pending) throw new ConnectionContractError('BUNDLE_PUBLICATION_MISSING', 'Validate a committed bundle first');
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.bundleResult = { publicationAttempt: await this.bundles.publish(
        this.requireDraft(), pending, mode, scenarioId,
      ) };
    } catch (error) {
      this.bundleResult = publicationErrorResult(error);
      throw error;
    } finally {
      this.pendingBundle = undefined;
      this.busy = false;
      await this.postView();
    }
  }

  private async reconcilePublicationAttempt(attemptId: string): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.bundleResult = {
        publicationAttempt: await this.bundles.reconcile(this.requireDraft(), attemptId),
      };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async discardPendingBundle(): Promise<void> {
    const pending = this.pendingBundle;
    this.pendingBundle = undefined;
    this.bundleResult = undefined;
    if (pending) await pending.bundle.dispose();
  }

  private async closeWorkspace(): Promise<void> {
    const profile = this.draft;
    this.authorizedSession.unbind();
    await this.activeConnection.close();
    await this.clearEnvironmentState();
    if (profile) this.live.set(profile.id, { status: 'Not connected' });
    this.page = 'environments';
    await this.postView();
  }

  private async clearEnvironmentState(): Promise<void> {
    await this.discardPendingBundle();
    this.activeTab = 'Hive';
    this.workspaceData = undefined;
    this.createSwarmForm = undefined;
    this.journalSwarmId = undefined;
    this.journalRunId = undefined;
    this.journalResult = undefined;
    this.swarmHistorySwarmId = undefined;
    this.swarmHistoryResult = undefined;
    this.swarmOperationResult = undefined;
    this.debugSwarmId = undefined;
    this.debugRuntimeId = undefined;
    this.debugResult = undefined;
    this.scenarioFocusScenarioId = undefined;
    this.scenarioFocusBundleKey = undefined;
    this.scenarioFocusSection = 'OVERVIEW';
    this.scenarioFocusTree = undefined;
    this.scenarioFocusInputs = undefined;
    this.draft = undefined;
    this.attempt = undefined;
    this.attemptView = undefined;
    this.sessionActivity = SESSION_ACTIVITIES.NEEDS_SIGN_IN;
  }

  private async ensureAuthorizedSession(): Promise<void> {
    const profile = this.requireDraft();
    try {
      const evidence = await this.authorizedSession.ensure(profile);
      if (evidence) this.live.set(profile.id, { status: 'Connected', evidence });
      this.sessionActivity = SESSION_ACTIVITIES.ACTIVE;
    } catch (error) {
      this.markSessionFailure(profile, error);
      throw error;
    }
  }

  private async reauthorizeEnvironment(): Promise<void> {
    const profile = this.requireDraft();
    this.sessionActivity = SESSION_ACTIVITIES.SIGNING_IN;
    this.live.set(profile.id, { status: 'Connecting', evidence: this.live.get(profile.id)?.evidence });
    this.busy = true;
    await this.postView();
    try {
      const evidence = await this.authorizedSession.signIn(profile);
      this.live.set(profile.id, { status: 'Connected', evidence });
      this.sessionActivity = SESSION_ACTIVITIES.ACTIVE;
    } catch (error) {
      this.markSessionFailure(profile, error);
      throw error;
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async signOut(): Promise<void> {
    const profile = this.requireDraft();
    this.sessionActivity = SESSION_ACTIVITIES.SIGNING_OUT;
    this.live.set(profile.id, { status: 'Connecting', evidence: this.live.get(profile.id)?.evidence });
    this.busy = true;
    await this.postView();
    let result;
    try {
      result = await this.authorizedSession.signOut(profile);
      await this.clearEnvironmentState();
      this.live.set(profile.id, { status: 'Not connected' });
      this.page = 'environments';
    } catch (error) {
      this.sessionActivity = SESSION_ACTIVITIES.UNAVAILABLE;
      this.live.set(profile.id, { status: 'Unavailable', evidence: this.live.get(profile.id)?.evidence });
      throw error;
    } finally {
      this.busy = false;
      await this.postView();
    }
    if (result.remoteRevocation === 'UNCONFIRMED' || result.transportClosure === 'UNCONFIRMED') {
      await vscode.window.showWarningMessage(
        'Signed out locally. PocketHive could not confirm every remote session cleanup step.',
      );
    }
  }

  private markSessionFailure(profile: McpConnectionProfile, error: unknown): void {
    const authenticationFailure = error instanceof Error
      && (error.name === 'AuthenticationExpiredError'
        || (error instanceof ConnectionContractError && error.code.startsWith('OAUTH_')));
    this.sessionActivity = authenticationFailure ? SESSION_ACTIVITIES.NEEDS_SIGN_IN : SESSION_ACTIVITIES.UNAVAILABLE;
    this.live.set(profile.id, {
      status: authenticationFailure ? 'Needs sign-in' : 'Unavailable',
      evidence: this.live.get(profile.id)?.evidence,
    });
  }

  private requireAttempt(): ConnectionAttempt {
    if (!this.attempt) throw new ConnectionContractError('CONNECTION_ATTEMPT_MISSING', 'No connection attempt');
    return this.attempt;
  }

  private requireDraft(): McpConnectionProfile {
    if (!this.draft) throw new ConnectionContractError('PROFILE_DRAFT_MISSING', 'No profile draft');
    return this.draft;
  }

  private async postView(): Promise<void> {
    const profiles = this.repository.list().map(profile => row(profile, this.live.get(profile.id)));
    const active = this.draft ? row(this.draft, this.live.get(this.draft.id)) : undefined;
    await this.post({
      type: 'viewModel',
      model: boundCompanionViewModel({
        page: this.page,
        profiles,
        draft: this.draft,
        attempt: this.attemptView,
        activeProfile: active,
        activeTab: this.activeTab,
        workspaceData: this.workspaceData,
        swarmOperations: SWARM_OPERATIONS,
        swarmPrimaryActions: primaryActionsForSwarms(this.workspaceData),
        createSwarmForm: this.createSwarmForm,
        journalSwarmId: this.journalSwarmId,
        journalRunId: this.journalRunId,
        journalResult: this.journalResult,
        swarmHistorySwarmId: this.swarmHistorySwarmId,
        swarmHistoryResult: this.swarmHistoryResult,
        swarmOperationResult: this.swarmOperationResult,
        debugSwarmId: this.debugSwarmId,
        debugRuntimeId: this.debugRuntimeId,
        debugResult: this.debugResult,
        scenarioFocusScenarioId: this.scenarioFocusScenarioId,
        scenarioFocusBundleKey: this.scenarioFocusBundleKey,
        scenarioFocusSection: this.scenarioFocusSection,
        scenarioFocusTree: this.scenarioFocusTree,
        scenarioFocusInputs: this.scenarioFocusInputs,
        pendingBundle: this.pendingBundle ? {
          source: this.pendingBundle.bundle.source,
          fileCount: this.pendingBundle.bundle.fileManifest.length,
          validationReceipt: this.pendingBundle.receipt,
        } : undefined,
        bundleResult: this.bundleResult,
        debugActions: DEBUG_ACTIONS,
        session: sessionPresentation(this.sessionActivity),
        busy: this.busy,
      } satisfies CompanionViewModel),
    });
  }

  private async post(message: unknown): Promise<void> {
    const view = this.currentView.value();
    if (!this.disposed && view) await view.webview.postMessage(message);
  }

  private async promptScenarioAssetPath(
    asset: Exclude<ScenarioAsset, 'RAW'>,
    scenarioId: string,
  ): Promise<string | undefined> {
    const label = asset === SCENARIO_ASSETS.SCHEMA ? 'schema' : 'template';
    const placeHolder = asset === SCENARIO_ASSETS.SCHEMA
      ? 'schemas/body.schema.json'
      : 'templates/http/request.yaml';
    return vscode.window.showInputBox({
      prompt: `Exact deployed ${label} path for ${scenarioId}`,
      placeHolder,
      ignoreFocusOut: true,
      validateInput: value => value.trim() ? null : `An exact ${label} path is required.`,
    });
  }
}

function row(
  profile: McpConnectionProfile,
  live?: { status: LiveStatus; evidence?: ConnectionEvidence },
): ProfileRow {
  return {
    ...profile,
    status: live?.status ?? 'Not connected',
    principalLabel: live?.evidence?.principalLabel,
  };
}

function firstCreatableTemplate(value: unknown): { templateId: string; scenarioId: string } | undefined {
  return creatableTemplates(value)[0];
}

function requireCreateSwarmSelection(value: unknown, templateId: string, scenarioId: string): void {
  const match = creatableTemplates(value).some(template =>
    template.templateId === templateId && template.scenarioId === scenarioId);
  if (!match) {
    throw new ConnectionContractError('CREATE_SWARM_TEMPLATE_INVALID',
      'Select one exact non-defunct Scenario Manager template');
  }
}

function creatableTemplates(value: unknown): Array<{ templateId: string; scenarioId: string }> {
  if (!Array.isArray(value)) return [];
  const result: Array<{ templateId: string; scenarioId: string }> = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    const templateId = typeof record.id === 'string' && record.id.trim() ? record.id.trim() : undefined;
    if (!templateId || record.defunct === true) continue;
    result.push({ templateId, scenarioId: templateId });
  }
  return result;
}

function extensionVersion(packageJson: unknown): string {
  if (!packageJson || typeof packageJson !== 'object' || Array.isArray(packageJson)) {
    throw new ConnectionContractError('EXTENSION_VERSION_INVALID', 'PocketHive extension manifest is unavailable');
  }
  const version = (packageJson as Record<string, unknown>).version;
  if (typeof version !== 'string' || !version.trim()) {
    throw new ConnectionContractError('EXTENSION_VERSION_INVALID', 'PocketHive extension version is unavailable');
  }
  return version.trim();
}

function safeError(error: unknown): { code: string; message: string } {
  if (error instanceof ConnectionContractError) return { code: error.code, message: error.message };
  return { code: error instanceof Error ? error.name : 'COMPANION_ERROR', message: error instanceof Error ? error.message : String(error) };
}

function publicationErrorResult(error: unknown): unknown {
  const safe = safeError(error);
  const attemptId = publicationAttemptId(error);
  return {
    publicationError: {
      ...safe,
      ...(attemptId ? { attemptId } : {}),
    },
  };
}

function publicationAttemptId(error: unknown): string | undefined {
  if (!(error instanceof ConnectionContractError)) return undefined;
  const attemptId = error.details?.attemptId;
  return typeof attemptId === 'string' && attemptId.trim() ? attemptId.trim() : undefined;
}

function findScenarioFilePath(tree: unknown, fileNames: string[]): string | undefined {
  const targets = new Set(fileNames.map(item => item.toLocaleLowerCase()));
  const root = tree && typeof tree === 'object' && !Array.isArray(tree)
    ? tree as Record<string, unknown>
    : undefined;
  const nodes = Array.isArray(root?.nodes) ? root.nodes : [];
  for (const item of nodes) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    const path = typeof record.path === 'string' ? record.path.trim() : '';
    if (!path) continue;
    const normalized = path.toLocaleLowerCase();
    const fileName = normalized.split('/').at(-1);
    if (fileName && targets.has(fileName)) return path;
  }
  return undefined;
}

function html(webview: vscode.Webview, extensionUri: vscode.Uri): string {
  const nonce = randomUUID().replaceAll('-', '');
  const brandTokens = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'resources', 'brand-tokens.css'));
  const style = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'media', 'companion.css'));
  const script = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'out', 'webview', 'main.js'));
  const eventFilters = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'out', 'webview', 'eventFilters.js'));
  const logo = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'resources', 'logo-mark.svg'));
  return `<!doctype html>
<html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${webview.cspSource}; style-src ${webview.cspSource}; script-src 'nonce-${nonce}';">
<link rel="stylesheet" href="${brandTokens}"><link rel="stylesheet" href="${style}"><title>PocketHive</title></head>
<body><main id="app" data-logo="${logo}"></main><div id="announcer" class="sr-only" aria-live="polite"></div>
<script nonce="${nonce}" src="${eventFilters}"></script><script nonce="${nonce}" src="${script}"></script></body></html>`;
}
