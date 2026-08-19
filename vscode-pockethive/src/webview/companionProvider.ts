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
  SwarmOperation,
  SWARM_OPERATIONS,
} from '../operations/swarmOperations';
import { McpConnectionProfileRepository } from '../storage/profileRepository';
import { GitBundlePackager } from '../scenarios/gitBundlePackager';
import {
  PendingBundlePublication,
  PublicationMode,
  ScenarioBundleCoordinator,
} from '../scenarios/scenarioBundleCoordinator';
import { CompanionTab, decodeWebviewCommand } from './messages';
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
  readonly journalSwarmId?: string;
  readonly journalRunId?: string;
  readonly journalResult?: unknown;
  readonly swarmHistorySwarmId?: string;
  readonly swarmHistoryResult?: unknown;
  readonly swarmOperationResult?: unknown;
  readonly debugSwarmId?: string;
  readonly debugRuntimeId?: string;
  readonly debugResult?: unknown;
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
  private journalSwarmId?: string;
  private journalRunId?: string;
  private journalResult?: unknown;
  private swarmHistorySwarmId?: string;
  private swarmHistoryResult?: unknown;
  private swarmOperationResult?: unknown;
  private debugSwarmId?: string;
  private debugRuntimeId?: string;
  private debugResult?: unknown;
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
        case 'selectJournalSwarm':
          this.journalSwarmId = command.swarmId;
          this.journalRunId = undefined;
          this.journalResult = undefined;
          await this.loadJournal();
          break;
        case 'loadSwarmHistory':
          await this.loadSwarmHistory(command.swarmId);
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
        case 'validateCommittedBundle':
          await this.validateCommittedBundle();
          break;
        case 'publishCommittedBundle':
          await this.publishCommittedBundle(command.mode, command.scenarioId);
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
    } finally {
      this.pendingBundle = undefined;
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
    this.journalSwarmId = undefined;
    this.journalRunId = undefined;
    this.journalResult = undefined;
    this.swarmHistorySwarmId = undefined;
    this.swarmHistoryResult = undefined;
    this.swarmOperationResult = undefined;
    this.debugSwarmId = undefined;
    this.debugRuntimeId = undefined;
    this.debugResult = undefined;
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
        journalSwarmId: this.journalSwarmId,
        journalRunId: this.journalRunId,
        journalResult: this.journalResult,
        swarmHistorySwarmId: this.swarmHistorySwarmId,
        swarmHistoryResult: this.swarmHistoryResult,
        swarmOperationResult: this.swarmOperationResult,
        debugSwarmId: this.debugSwarmId,
        debugRuntimeId: this.debugRuntimeId,
        debugResult: this.debugResult,
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
