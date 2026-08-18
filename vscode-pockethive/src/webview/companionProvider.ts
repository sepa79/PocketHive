import { randomUUID } from 'node:crypto';
import * as vscode from 'vscode';

import { ActiveMcpConnection } from '../mcp/activeConnection';
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
import { createConnectionProfile } from '../connection/profile';
import { debugToolCall, DEBUG_ACTIONS } from '../debug/actions';
import { McpConnectionProfileRepository } from '../storage/profileRepository';
import { GitBundlePackager } from '../scenarios/gitBundlePackager';
import {
  PendingBundlePublication,
  PublicationMode,
  ScenarioBundleCoordinator,
} from '../scenarios/scenarioBundleCoordinator';
import { CompanionTab, decodeWebviewCommand } from './messages';

type LiveStatus = 'Connected' | 'Needs sign-in' | 'Unavailable' | 'Not connected';

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
  readonly debugSwarmId?: string;
  readonly debugRuntimeId?: string;
  readonly debugResult?: unknown;
  readonly pendingBundle?: unknown;
  readonly bundleResult?: unknown;
  readonly debugActions: typeof DEBUG_ACTIONS;
  readonly busy: boolean;
}

export class PocketHiveCompanionProvider implements vscode.WebviewViewProvider, vscode.Disposable {
  static readonly viewType = 'pockethive.companion';

  private readonly repository: McpConnectionProfileRepository;
  private readonly endpoints = new PocketHiveEndpointValidator();
  private readonly activeConnection = new ActiveMcpConnection();
  private readonly authentication: PocketHiveOAuthAuthentication;
  private readonly bundles: ScenarioBundleCoordinator;
  private readonly live = new Map<string, { status: LiveStatus; evidence?: ConnectionEvidence }>();
  private view?: vscode.WebviewView;
  private page: CompanionViewModel['page'] = 'environments';
  private activeTab: CompanionTab = 'Hive';
  private draft?: McpConnectionProfile;
  private attempt?: ConnectionAttempt;
  private attemptView?: ConnectionAttemptView;
  private workspaceData?: unknown;
  private debugSwarmId?: string;
  private debugRuntimeId?: string;
  private debugResult?: unknown;
  private pendingBundle?: PendingBundlePublication;
  private bundleResult?: unknown;
  private busy = false;
  private disposed = false;

  constructor(private readonly context: vscode.ExtensionContext) {
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
    this.bundles = new ScenarioBundleCoordinator(
      new GitBundlePackager(), this.endpoints, this.authentication,
    );
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
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
      view.onDidDispose(() => { this.view = undefined; }),
    );
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    this.view = undefined;
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
        case 'selectTab':
          this.activeTab = command.tab;
          this.debugResult = undefined;
          await this.loadTab();
          break;
        case 'refresh':
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
    this.attempt = this.newAttempt(profile);
    this.page = 'add';
    await this.runAttempt(() => this.requireAttempt().reconnect());
    if (this.attemptView?.state === 'READY_TO_SAVE') {
      await this.repository.select(profile.id);
      this.attempt.save();
      this.page = 'workspace';
      this.live.set(profile.id, { status: 'Connected', evidence: this.attemptView.evidence });
      await this.loadTab();
    }
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
      this.workspaceData = await this.activeConnection.callTool(tabTool(this.activeTab).name,
        tabTool(this.activeTab).arguments);
    } catch (error) {
      this.workspaceData = { error: safeError(error), observedAt: new Date().toISOString() };
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
      this.debugResult = bounded(await this.activeConnection.callTool(call.name, call.arguments));
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
    await this.activeConnection.close();
    await this.clearEnvironmentState();
    this.page = 'environments';
    await this.postView();
  }

  private async clearEnvironmentState(): Promise<void> {
    await this.discardPendingBundle();
    this.activeTab = 'Hive';
    this.workspaceData = undefined;
    this.debugSwarmId = undefined;
    this.debugRuntimeId = undefined;
    this.debugResult = undefined;
    this.draft = undefined;
    this.attempt = undefined;
    this.attemptView = undefined;
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
      model: bounded({
        page: this.page,
        profiles,
        draft: this.draft,
        attempt: this.attemptView,
        activeProfile: active,
        activeTab: this.activeTab,
        workspaceData: bounded(this.workspaceData),
        debugSwarmId: this.debugSwarmId,
        debugRuntimeId: this.debugRuntimeId,
        debugResult: bounded(this.debugResult),
        pendingBundle: this.pendingBundle ? bounded({
          source: this.pendingBundle.bundle.source,
          fileCount: this.pendingBundle.bundle.fileManifest.length,
          validationReceipt: this.pendingBundle.receipt,
        }) : undefined,
        bundleResult: bounded(this.bundleResult),
        debugActions: DEBUG_ACTIONS,
        busy: this.busy,
      } satisfies CompanionViewModel),
    });
  }

  private async post(message: unknown): Promise<void> {
    if (!this.disposed && this.view) await this.view.webview.postMessage(message);
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

function tabTool(tab: CompanionTab): { name: string; arguments: Record<string, unknown> } {
  switch (tab) {
    case 'Hive': return { name: 'swarm_list', arguments: {} };
    case 'Buzz': return { name: 'debug_hive_journal', arguments: { limit: 50 } };
    case 'Journal': return { name: 'swarm_list', arguments: {} };
    case 'Scenarios': return { name: 'scenario_list', arguments: {} };
    case 'Debug': return { name: 'swarm_list', arguments: {} };
  }
}

function bounded(value: unknown): unknown {
  if (value === undefined) return undefined;
  const redacted = redact(value);
  const text = JSON.stringify(redacted);
  return text.length <= 100_000
    ? redacted
    : { truncated: true, content: text.slice(0, 100_000) };
}

function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.slice(0, 1000).map(redact);
  if (!value || typeof value !== 'object') return value;
  const result: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(value as Record<string, unknown>).slice(0, 1000)) {
    result[key] = /authorization|token|secret|password/i.test(key) ? '[REDACTED]' : redact(item);
  }
  return result;
}

function safeError(error: unknown): { code: string; message: string } {
  if (error instanceof ConnectionContractError) return { code: error.code, message: error.message };
  return { code: error instanceof Error ? error.name : 'COMPANION_ERROR', message: error instanceof Error ? error.message : String(error) };
}

function html(webview: vscode.Webview, extensionUri: vscode.Uri): string {
  const nonce = randomUUID().replaceAll('-', '');
  const style = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'media', 'companion.css'));
  const script = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'out', 'webview', 'main.js'));
  const logo = webview.asWebviewUri(vscode.Uri.joinPath(extensionUri, 'resources', 'logo-mark.svg'));
  return `<!doctype html>
<html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${webview.cspSource}; style-src ${webview.cspSource}; script-src 'nonce-${nonce}';">
<link rel="stylesheet" href="${style}"><title>PocketHive</title></head>
<body><main id="app" data-logo="${logo}"></main><div id="announcer" class="sr-only" aria-live="polite"></div>
<script type="module" nonce="${nonce}" src="${script}"></script></body></html>`;
}
