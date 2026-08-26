/**
 * Responsibility: Adapt VS Code companion commands and lifecycle events to bounded MCP client ports.
 * Must not: Contact PocketHive owner services directly or reimplement their domain outcomes.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */
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
  PocketHiveMcpScope,
  POCKETHIVE_MCP_SCOPES,
} from '../connection/contracts';
import { PocketHiveEndpointValidator } from '../connection/endpointValidator';
import { LoopbackBrowserAuthorization } from '../connection/loopbackBrowser';
import { PocketHiveOAuthAuthentication } from '../connection/oauthAuthentication';
import { AuthorizedMcpSession } from '../connection/authorizedMcpSession';
import { createConnectionProfile } from '../connection/profile';
import { debugToolCall, exactWorkerRuntimeId, DEBUG_ACTION_LABELS, DEBUG_ACTIONS } from '../debug/actions';
import {
  primaryActionsForSwarms,
  SwarmOperation,
  SWARM_OPERATIONS,
} from '../operations/swarmOperations';
import { openJsonPreview, openPreviewDocument } from '../preview';
import { McpConnectionProfileRepository } from '../storage/profileRepository';
import { CommittedBundleReference, GitBundlePackager } from '../scenarios/gitBundlePackager';
import { GitScenarioBundleDiscovery } from '../scenarios/gitScenarioBundleDiscovery';
import { planRepositoryDeployment, RepositoryDeploymentPlan } from '../scenarios/repositoryDeployment';
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
import {
  CompanionTab,
  decodeWebviewCommand,
  ScenarioSection,
  SwarmNetworkMode,
  WorkerDebugAction,
} from './messages';
import { CurrentView } from './currentView';
import { EventPagePresentation } from './eventPresentation';
import { WEBVIEW_SCRIPT_FILES } from './scriptManifest';
import {
  RepositoryScenarioCandidateRegistry,
  assertRepositoryScenarioFileWritable,
  RepositoryScenarioView,
  resolveRepositoryScenarioCandidate,
  resolveRepositoryScenarioFile,
  scanRepositoryScenarios,
} from './repositoryScenarios';
import { boundCompanionViewModel, redactSensitiveValues } from './viewModelBoundary';
import { VisibleAutoRefresh } from './visibleAutoRefresh';
import { resolvePocketHiveWebUiUrl, WebUiDestination } from './webUiNavigation';
import { SIDEBAR_EVENT_LIMIT, workspaceToolCall } from './workspaceTool';
import {
  SESSION_ACTIVITIES,
  SessionActivity,
  SessionPresentation,
  sessionPresentation,
} from './sessionPresentation';
import { CreateSwarmFormState, SwarmWorkspaceService } from './swarmWorkspaceService';

type LiveStatus = 'Connected' | 'Connecting' | 'Needs sign-in' | 'Unavailable' | 'Not connected';
const ENVIRONMENT_HEALTH_RESOURCE = 'pockethive://environment/health';
const REAUTHENTICATION_REQUIRED_CODES = new Set([
  'OAUTH_REFRESH_REJECTED',
  'OAUTH_REFRESH_TOKEN_NOT_ROTATED',
  'OAUTH_REFRESH_SCOPE_WIDENED',
  'OAUTH_SCOPE_NOT_GRANTED',
  'OAUTH_SESSION_INVALID',
  'OAUTH_SESSION_NOT_RENEWABLE',
  'OAUTH_TOKEN_RESPONSE_INVALID',
]);
const TAB_REFRESH_MODES = Object.freeze({
  FOREGROUND: 'FOREGROUND',
  BACKGROUND: 'BACKGROUND',
} as const);
type TabRefreshMode = typeof TAB_REFRESH_MODES[keyof typeof TAB_REFRESH_MODES];

interface ActiveTabRead {
  readonly tab: CompanionTab;
  readonly journalSwarmId?: string;
  readonly journalRunId?: string;
}

type JournalRead =
  | { readonly state: 'NOT_REQUESTED' }
  | { readonly state: 'SUCCESS'; readonly ownerData: unknown }
  | { readonly state: 'FAILED'; readonly error: unknown };

interface ActiveTabSnapshot {
  readonly ownerData: unknown;
  readonly environmentHealth: unknown;
  readonly journal: JournalRead;
}

interface ProfileRow extends McpConnectionProfile {
  readonly status: LiveStatus;
  readonly principalLabel?: string;
}

interface CompanionViewModel {
  readonly page: 'environments' | 'add' | 'workspace';
  readonly profiles: ProfileRow[];
  readonly draft?: McpConnectionProfile;
  readonly attempt?: ConnectionAttemptView;
  readonly activeProfile?: ProfileRow;
  readonly activeTab: CompanionTab;
  readonly workspaceData?: unknown;
  readonly environmentHealth?: unknown;
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
  readonly debugWorkersResult?: unknown;
  readonly debugAction?: string;
  readonly debugResult?: unknown;
  readonly scenarioFocusScenarioId?: string;
  readonly scenarioFocusBundleKey?: string;
  readonly scenarioFocusSection?: ScenarioSection;
  readonly scenarioFocusTree?: unknown;
  readonly scenarioFocusInputs?: unknown;
  readonly repositoryScenarios?: RepositoryScenarioView;
  readonly repositoryPendingCandidateId?: string;
  readonly repositoryResultCandidateId?: string;
  readonly repositoryDeploymentConflict?: RepositoryDeploymentPlan & { readonly candidateId: string };
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
  private readonly swarms: SwarmWorkspaceService;
  private readonly authentication: PocketHiveOAuthAuthentication;
  private readonly authorizedSession: AuthorizedMcpSession;
  private readonly bundles: ScenarioBundleCoordinator;
  private readonly scenarioDiscovery = new GitScenarioBundleDiscovery();
  private readonly scenarioCandidates = new RepositoryScenarioCandidateRegistry();
  private readonly live = new Map<string, { status: LiveStatus; evidence?: ConnectionEvidence }>();
  private readonly currentView = new CurrentView<vscode.WebviewView>();
  private readonly eventDetails = new EventPagePresentation();
  private readonly tabAutoRefresh: VisibleAutoRefresh;
  private page: CompanionViewModel['page'] = 'environments';
  private activeTab: CompanionTab = 'Hive';
  private draft?: McpConnectionProfile;
  private attempt?: ConnectionAttempt;
  private attemptView?: ConnectionAttemptView;
  private workspaceData?: unknown;
  private environmentHealth?: unknown;
  private createSwarmForm?: CreateSwarmFormState;
  private journalSwarmId?: string;
  private journalRunId?: string;
  private journalResult?: unknown;
  private swarmHistorySwarmId?: string;
  private swarmHistoryResult?: unknown;
  private swarmOperationResult?: unknown;
  private debugSwarmId?: string;
  private debugRuntimeId?: string;
  private debugWorkersResult?: unknown;
  private debugAction?: string;
  private debugResult?: unknown;
  private scenarioFocusScenarioId?: string;
  private scenarioFocusBundleKey?: string;
  private scenarioFocusSection: ScenarioSection = 'OVERVIEW';
  private scenarioFocusTree?: unknown;
  private scenarioFocusInputs?: unknown;
  private repositoryScenarios?: RepositoryScenarioView;
  private repositoryPendingCandidateId?: string;
  private repositoryResultCandidateId?: string;
  private repositoryDeploymentConflict?: RepositoryDeploymentPlan & { readonly candidateId: string };
  private pendingBundle?: PendingBundlePublication;
  private bundleResult?: unknown;
  private sessionActivity: SessionActivity = SESSION_ACTIVITIES.NEEDS_SIGN_IN;
  private busy = false;
  private disposed = false;
  private backgroundRefreshRevision = 0;

  constructor(private readonly context: vscode.ExtensionContext) {
    const version = extensionVersion(context.extension.packageJSON);
    const clients = () => new McpHttpClient(version);
    this.activeConnection = new ActiveMcpConnection(clients);
    this.swarms = new SwarmWorkspaceService(this.activeConnection);
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
      this.endpoints, this.authentication, this.activeConnection, undefined, undefined,
      {
        renewed: (profile, evidence) => {
          this.sessionActivity = SESSION_ACTIVITIES.ACTIVE;
          this.live.set(profile.id, { status: 'Connected', evidence });
          void this.postView();
        },
        unavailable: (profile, error) => {
          this.markSessionFailure(profile, error);
          void this.postView();
        },
      },
    );
    this.bundles = new ScenarioBundleCoordinator(
      new GitBundlePackager(), this.activeConnection,
    );
    this.tabAutoRefresh = new VisibleAutoRefresh(() => this.refreshTabInBackground());
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    this.currentView.attach(view);
    this.tabAutoRefresh.setVisible(view.visible);
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
      view.onDidChangeVisibility(() => {
        if (this.currentView.value() !== view) return;
        this.backgroundRefreshRevision += 1;
        this.tabAutoRefresh.setVisible(view.visible);
      }),
      view.onDidDispose(() => {
        const wasCurrent = this.currentView.value() === view;
        this.currentView.detach(view);
        if (wasCurrent) {
          this.backgroundRefreshRevision += 1;
          this.tabAutoRefresh.setVisible(false);
        }
      }),
    );
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    this.backgroundRefreshRevision += 1;
    this.tabAutoRefresh.dispose();
    this.eventDetails.clear();
    const view = this.currentView.value();
    if (view) this.currentView.detach(view);
    await this.discardPendingBundle();
    await this.activeConnection.close().catch(() => undefined);
  }

  private async receive(value: unknown): Promise<void> {
    try {
      const command = decodeWebviewCommand(value);
      if (command.type !== 'ready') this.backgroundRefreshRevision += 1;
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
          this.debugAction = undefined;
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
            command.autoPullImages, command.sutId, command.variablesProfileId,
            command.networkMode, command.networkProfileId);
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
        case 'openWebUi':
          await this.openWebUi(command);
          break;
        case 'openJournalRun':
          this.activeTab = 'Journal';
          this.journalSwarmId = command.swarmId;
          this.journalRunId = command.runId;
          this.journalResult = undefined;
          await this.loadTab();
          break;
        case 'openEventDetails':
          await this.openEventDetails(command.detailId);
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
          this.debugWorkersResult = undefined;
          this.debugAction = undefined;
          this.debugResult = undefined;
          await this.loadTab();
          break;
        case 'openDebugForWorker':
          await this.openDebugForWorker(command.swarmId, command.instance, command.action);
          break;
        case 'selectDebugSwarm':
          this.debugSwarmId = command.swarmId;
          this.debugRuntimeId = undefined;
          this.debugWorkersResult = undefined;
          this.debugAction = undefined;
          this.debugResult = undefined;
          await this.postView();
          break;
        case 'selectDebugWorker':
          this.debugRuntimeId = command.runtimeId;
          this.debugAction = undefined;
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
        case 'validateRepositoryBundle':
          await this.validateRepositoryBundle(command.candidateId);
          break;
        case 'openRepositoryBundleFile':
          await this.openRepositoryBundleFile(command.candidateId, command.path);
          break;
        case 'deployRepositoryBundle':
          await this.deployRepositoryBundle(command.candidateId);
          break;
        case 'replaceRepositoryBundle':
          await this.replaceRepositoryBundle(command.candidateId);
          break;
        case 'openRepositoryRename':
          await this.openRepositoryRename(
            command.candidateId, command.scenarioId, command.scenarioName,
          );
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
    this.eventDetails.clear();
    this.busy = true;
    await this.postView();
    try {
      if (this.activeTab === 'Scenarios') {
        if (this.repositoryPendingCandidateId) await this.discardPendingBundle();
        this.repositoryScenarios = await scanRepositoryScenarios(
          vscode.workspace.isTrusted,
          (vscode.workspace.workspaceFolders ?? []).map(folder => ({
            name: folder.name,
            directory: folder.uri.fsPath,
          })),
          this.scenarioDiscovery,
          this.scenarioCandidates,
        );
      }
      const read = this.activeTabRead();
      const snapshot = await this.readActiveTab(read);
      this.applyActiveTabSnapshot(read, snapshot, TAB_REFRESH_MODES.FOREGROUND);
      if (this.activeTab === 'Scenarios') {
        await this.refreshScenarioFocusData();
      }
    } catch (error) {
      this.workspaceData = { error: safeError(error), observedAt: new Date().toISOString() };
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async refreshTabInBackground(): Promise<void> {
    if (this.disposed || this.busy || this.page !== 'workspace'
      || this.sessionActivity !== SESSION_ACTIVITIES.ACTIVE) return;
    const revision = this.backgroundRefreshRevision;
    const read = this.activeTabRead();
    try {
      const snapshot = await this.readActiveTab(read);
      if (!this.backgroundRefreshIsCurrent(revision, read.tab)) return;
      this.applyActiveTabSnapshot(read, snapshot, TAB_REFRESH_MODES.BACKGROUND);
      await this.postView();
    } catch {
      if (this.backgroundRefreshIsCurrent(revision, read.tab)
        && this.sessionActivity !== SESSION_ACTIVITIES.ACTIVE) await this.postView();
    }
  }

  private activeTabRead(): ActiveTabRead {
    return {
      tab: this.activeTab,
      journalSwarmId: this.journalSwarmId,
      journalRunId: this.journalRunId,
    };
  }

  private async readActiveTab(read: ActiveTabRead): Promise<ActiveTabSnapshot> {
    await this.ensureAuthorizedSession();
    const call = workspaceToolCall(read.tab);
    const health = this.activeConnection.readResource(ENVIRONMENT_HEALTH_RESOURCE)
      .catch(error => ({ error: safeError(error), observedAt: new Date().toISOString() }));
    const ownerData = await this.activeConnection.callTool(call.name, call.arguments);
    let journal: JournalRead = { state: 'NOT_REQUESTED' };
    if (read.tab === 'Journal' && read.journalSwarmId) {
      try {
        journal = {
          state: 'SUCCESS',
          ownerData: await this.activeConnection.callTool('debug_journal', {
            swarmId: read.journalSwarmId,
            limit: SIDEBAR_EVENT_LIMIT,
            ...(read.journalRunId ? { runId: read.journalRunId } : {}),
          }),
        };
      } catch (error) {
        journal = { state: 'FAILED', error };
      }
    }
    return { ownerData, environmentHealth: await health, journal };
  }

  private applyActiveTabSnapshot(
    read: ActiveTabRead,
    snapshot: ActiveTabSnapshot,
    mode: TabRefreshMode,
  ): void {
    this.workspaceData = read.tab === 'Buzz'
      ? this.eventDetails.replace(snapshot.ownerData)
      : snapshot.ownerData;
    this.environmentHealth = snapshot.environmentHealth;
    if (snapshot.journal.state === 'SUCCESS') {
      this.journalResult = this.eventDetails.replace(snapshot.journal.ownerData);
    } else if (snapshot.journal.state === 'FAILED' && mode === TAB_REFRESH_MODES.FOREGROUND) {
      this.journalResult = {
        error: safeError(snapshot.journal.error),
        observedAt: new Date().toISOString(),
      };
    }
  }

  private backgroundRefreshIsCurrent(revision: number, tab: CompanionTab): boolean {
    return !this.disposed
      && !this.busy
      && this.page === 'workspace'
      && this.sessionActivity === SESSION_ACTIVITIES.ACTIVE
      && this.backgroundRefreshRevision === revision
      && this.activeTab === tab;
  }

  private async loadJournal(): Promise<void> {
    if (!this.journalSwarmId) {
      throw new ConnectionContractError('JOURNAL_SWARM_REQUIRED', 'Select an exact swarm');
    }
    this.eventDetails.clear();
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      const ownerJournal = await this.activeConnection.callTool('debug_journal', {
        swarmId: this.journalSwarmId,
        limit: SIDEBAR_EVENT_LIMIT,
        ...(this.journalRunId ? { runId: this.journalRunId } : {}),
      });
      this.journalResult = this.eventDetails.replace(ownerJournal);
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
      this.swarmHistoryResult = await this.swarms.history(swarmId);
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
      await openJsonPreview(`Swarm ${swarmId}`, await this.swarms.details(swarmId));
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async openEventDetails(detailId: string): Promise<void> {
    await openJsonPreview('PocketHive event details', redactSensitiveValues(this.eventDetails.require(detailId)));
  }

  private async openWebUi(target: WebUiDestination): Promise<void> {
    const targetUrl = resolvePocketHiveWebUiUrl(this.environmentHealth, target);
    const opened = await vscode.env.openExternal(vscode.Uri.parse(targetUrl));
    if (!opened) {
      throw new ConnectionContractError('WEB_UI_OPEN_FAILED', targetUrl);
    }
  }

  private async runSwarmOperation(action: SwarmOperation, swarmId: string): Promise<void> {
    this.busy = true;
    this.swarmOperationResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession(POCKETHIVE_MCP_SCOPES.OPERATE);
      if (action === SWARM_OPERATIONS.REMOVE) {
        const approved = await vscode.window.showWarningMessage(
          `Remove swarm “${swarmId}”? This targets the exact selected swarm.`,
          { modal: true },
          'Remove swarm',
        );
        if (approved !== 'Remove swarm') return;
      }
      const result = await this.swarms.operate(action, swarmId);
      this.swarmOperationResult = result.operationResult;
      this.workspaceData = result.swarms;
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async runSwarmBatchOperation(action: Exclude<SwarmOperation, 'REMOVE'>): Promise<void> {
    this.busy = true;
    this.swarmOperationResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession(POCKETHIVE_MCP_SCOPES.OPERATE);
      const result = await this.swarms.operateBatch(action, this.workspaceData);
      this.swarmOperationResult = result.operationResult;
      this.workspaceData = result.swarms;
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
      this.createSwarmForm = await this.swarms.createForm();
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
      this.createSwarmForm = await this.swarms.selectTemplate(current, templateId, scenarioId);
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async submitCreateSwarm(
    swarmId: string,
    templateId: string,
    scenarioId: string,
    autoPullImages: boolean,
    sutId: string | null,
    variablesProfileId: string | null,
    networkMode: SwarmNetworkMode,
    networkProfileId: string | null,
  ): Promise<void> {
    const form = this.createSwarmForm;
    if (!form) {
      throw new ConnectionContractError('CREATE_SWARM_FORM_MISSING', 'Open Create swarm first');
    }
    this.busy = true;
    this.swarmOperationResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession(POCKETHIVE_MCP_SCOPES.OPERATE);
      const result = await this.swarms.create(form, {
        swarmId,
        templateId,
        scenarioId,
        autoPullImages,
        sutId,
        variablesProfileId,
        networkMode,
        networkProfileId,
      });
      this.swarmOperationResult = result.operationResult;
      this.createSwarmForm = undefined;
      this.workspaceData = result.swarms;
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async runDebug(action: string, tailLines?: number): Promise<void> {
    const call = debugToolCall(action, this.debugSwarmId, this.debugRuntimeId, tailLines);
    if (action === DEBUG_ACTION_LABELS.WORKERS) {
      this.debugRuntimeId = undefined;
      this.debugWorkersResult = undefined;
    }
    this.debugAction = action;
    this.debugResult = undefined;
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      this.debugResult = await this.activeConnection.callTool(call.name, call.arguments);
      if (action === DEBUG_ACTION_LABELS.WORKERS) this.debugWorkersResult = this.debugResult;
    } finally {
      this.busy = false;
      await this.postView();
    }
  }

  private async openDebugForWorker(
    swarmId: string,
    instance: string,
    action: WorkerDebugAction,
  ): Promise<void> {
    this.activeTab = 'Debug';
    this.debugSwarmId = swarmId;
    this.debugRuntimeId = undefined;
    this.debugWorkersResult = undefined;
    this.debugAction = action;
    this.debugResult = undefined;
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession();
      const workers = await this.activeConnection.callTool('runtime_list_workers', { swarmId });
      const runtimeId = exactWorkerRuntimeId(workers, instance);
      this.debugWorkersResult = workers;
      this.debugRuntimeId = runtimeId;
      const call = debugToolCall(action, swarmId, runtimeId, action === DEBUG_ACTION_LABELS.LOGS ? 200 : undefined);
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
    this.clearRepositoryPublicationState();
    await this.validateBundleDirectory(selected[0].fsPath);
  }

  private async validateRepositoryBundle(candidateId: string): Promise<void> {
    if (this.pendingBundle) {
      throw new ConnectionContractError('BUNDLE_PUBLICATION_PENDING', 'Publish or discard the validated bundle first');
    }
    const normalizedCandidateId = candidateId.trim();
    this.repositoryPendingCandidateId = normalizedCandidateId;
    this.repositoryResultCandidateId = normalizedCandidateId;
    this.repositoryDeploymentConflict = undefined;
    try {
      await this.validateBundleDirectory(resolveRepositoryScenarioCandidate(
        vscode.workspace.isTrusted, normalizedCandidateId, this.scenarioCandidates,
      ));
    } catch (error) {
      this.clearRepositoryPublicationState();
      throw error;
    }
  }

  private async openRepositoryBundleFile(candidateId: string, path: string): Promise<void> {
    const file = resolveRepositoryScenarioFile(
      vscode.workspace.isTrusted, candidateId, path, this.scenarioCandidates,
    );
    await assertRepositoryScenarioFileWritable(file);
    const document = await vscode.workspace.openTextDocument(vscode.Uri.file(file));
    await vscode.window.showTextDocument(document, { preview: false });
  }

  private async deployRepositoryBundle(candidateId: string): Promise<void> {
    const normalizedCandidateId = candidateId.trim();
    if (!this.pendingBundle) {
      await this.validateRepositoryBundle(normalizedCandidateId);
    } else if (this.repositoryPendingCandidateId !== normalizedCandidateId) {
      throw new ConnectionContractError(
        'BUNDLE_PUBLICATION_PENDING',
        'Publish or discard the validated Repository scenario first',
      );
    }
    const pending = this.pendingBundle;
    if (!pending) throw new ConnectionContractError('BUNDLE_PUBLICATION_MISSING', 'Validate a committed bundle first');
    const plan = planRepositoryDeployment(this.workspaceData, pending.receipt);
    if (plan.kind === 'CONFLICT') {
      this.repositoryDeploymentConflict = Object.freeze({ candidateId: normalizedCandidateId, ...plan });
      await this.postView();
      return;
    }
    await this.publishCommittedBundle('CREATE');
  }

  private async replaceRepositoryBundle(candidateId: string): Promise<void> {
    const conflict = this.repositoryDeploymentConflict;
    if (!conflict || conflict.kind !== 'CONFLICT' || conflict.candidateId !== candidateId.trim()
      || this.repositoryPendingCandidateId !== conflict.candidateId
      || this.pendingBundle?.receipt.scenarioId !== conflict.scenarioId) {
      throw new ConnectionContractError(
        'REPOSITORY_DEPLOYMENT_CONFLICT_STALE',
        'Validate and select the exact Repository scenario conflict again',
      );
    }
    this.repositoryDeploymentConflict = undefined;
    await this.publishCommittedBundle('REPLACE', conflict.scenarioId);
  }

  private async openRepositoryRename(
    candidateId: string,
    scenarioId: string,
    scenarioName: string,
  ): Promise<void> {
    const conflict = this.repositoryDeploymentConflict;
    const normalizedCandidateId = candidateId.trim();
    const normalizedScenarioId = scenarioId.trim();
    const normalizedScenarioName = scenarioName.trim();
    if (!conflict || conflict.kind !== 'CONFLICT' || conflict.candidateId !== normalizedCandidateId
      || this.repositoryPendingCandidateId !== normalizedCandidateId) {
      throw new ConnectionContractError(
        'REPOSITORY_DEPLOYMENT_CONFLICT_STALE',
        'Validate and select the exact Repository scenario conflict again',
      );
    }
    if (normalizedScenarioId === conflict.scenarioId) {
      throw new ConnectionContractError(
        'REPOSITORY_RENAME_ID_UNCHANGED',
        'Choose a different scenario ID before editing the source',
      );
    }
    await this.discardPendingBundle();
    await this.openRepositoryBundleFile(normalizedCandidateId, 'scenario.yaml');
    await vscode.window.showInformationMessage(
      `Update scenario.yaml id to “${normalizedScenarioId}” and name to “${normalizedScenarioName}”, then commit, refresh, validate, and deploy again.`,
    );
    await this.postView();
  }

  private async validateBundleDirectory(source: string | CommittedBundleReference): Promise<void> {
    const profile = this.requireDraft();
    this.busy = true;
    this.bundleResult = undefined;
    await this.postView();
    try {
      await this.ensureAuthorizedSession(POCKETHIVE_MCP_SCOPES.AUTHOR);
      this.pendingBundle = await this.bundles.validate(profile, source);
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
      await this.ensureAuthorizedSession(POCKETHIVE_MCP_SCOPES.PUBLISH);
      this.bundleResult = { publicationAttempt: await this.bundles.publish(
        this.requireDraft(), pending, mode, scenarioId,
      ) };
    } catch (error) {
      this.bundleResult = publicationErrorResult(error);
      throw error;
    } finally {
      this.pendingBundle = undefined;
      this.repositoryPendingCandidateId = undefined;
      this.repositoryDeploymentConflict = undefined;
      this.busy = false;
      await this.postView();
    }
  }

  private async reconcilePublicationAttempt(attemptId: string): Promise<void> {
    this.busy = true;
    await this.postView();
    try {
      await this.ensureAuthorizedSession(POCKETHIVE_MCP_SCOPES.PUBLISH);
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
    this.clearRepositoryPublicationState();
    if (pending) await pending.bundle.dispose();
  }

  private clearRepositoryPublicationState(): void {
    this.repositoryPendingCandidateId = undefined;
    this.repositoryResultCandidateId = undefined;
    this.repositoryDeploymentConflict = undefined;
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
    this.eventDetails.clear();
    this.activeTab = 'Hive';
    this.workspaceData = undefined;
    this.environmentHealth = undefined;
    this.createSwarmForm = undefined;
    this.journalSwarmId = undefined;
    this.journalRunId = undefined;
    this.journalResult = undefined;
    this.swarmHistorySwarmId = undefined;
    this.swarmHistoryResult = undefined;
    this.swarmOperationResult = undefined;
    this.debugSwarmId = undefined;
    this.debugRuntimeId = undefined;
    this.debugWorkersResult = undefined;
    this.debugAction = undefined;
    this.debugResult = undefined;
    this.scenarioFocusScenarioId = undefined;
    this.scenarioFocusBundleKey = undefined;
    this.scenarioFocusSection = 'OVERVIEW';
    this.scenarioFocusTree = undefined;
    this.scenarioFocusInputs = undefined;
    this.repositoryScenarios = undefined;
    this.scenarioCandidates.clear();
    this.draft = undefined;
    this.attempt = undefined;
    this.attemptView = undefined;
    this.sessionActivity = SESSION_ACTIVITIES.NEEDS_SIGN_IN;
  }

  private async ensureAuthorizedSession(
    requiredScope: PocketHiveMcpScope = POCKETHIVE_MCP_SCOPES.READ,
  ): Promise<void> {
    const profile = this.requireDraft();
    try {
      const evidence = await this.authorizedSession.ensure(profile, [requiredScope]);
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
        || (error instanceof ConnectionContractError && REAUTHENTICATION_REQUIRED_CODES.has(error.code)));
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
    this.tabAutoRefresh.setEnabled(!this.disposed
      && !this.busy
      && this.page === 'workspace'
      && this.sessionActivity === SESSION_ACTIVITIES.ACTIVE);
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
        environmentHealth: this.environmentHealth,
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
        debugWorkersResult: this.debugWorkersResult,
        debugAction: this.debugAction,
        debugResult: this.debugResult,
        scenarioFocusScenarioId: this.scenarioFocusScenarioId,
        scenarioFocusBundleKey: this.scenarioFocusBundleKey,
        scenarioFocusSection: this.scenarioFocusSection,
        scenarioFocusTree: this.scenarioFocusTree,
        scenarioFocusInputs: this.scenarioFocusInputs,
        repositoryScenarios: this.repositoryScenarios,
        repositoryPendingCandidateId: this.repositoryPendingCandidateId,
        repositoryResultCandidateId: this.repositoryResultCandidateId,
        repositoryDeploymentConflict: this.repositoryDeploymentConflict,
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
  const codicons = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'resources', 'codicon.css'));
  const style = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'media', 'companion.css'));
  const scripts = WEBVIEW_SCRIPT_FILES.map(file => {
    const uri = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'out', 'webview', file));
    return `<script nonce="${nonce}" src="${uri}"></script>`;
  }).join('');
  const logo = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'resources', 'logo-mark.svg'));
  return `<!doctype html>
<html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${webview.cspSource}; font-src ${webview.cspSource}; style-src ${webview.cspSource}; script-src 'nonce-${nonce}';">
<link rel="stylesheet" href="${brandTokens}"><link rel="stylesheet" href="${codicons}"><link rel="stylesheet" href="${style}"><title>PocketHive</title></head>
<body><main id="app" data-logo="${logo}"></main><div id="announcer" class="sr-only" aria-live="polite"></div>
${scripts}</body></html>`;
}
