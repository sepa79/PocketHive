import * as vscode from 'vscode';

import { SCENARIO_FILE_NAME, SCENARIO_FILE_NAMES, SCENARIO_SCHEME } from '../constants';
import { ScenarioSummary } from '../types';
import { scenarioList, scenarioRawRead, scenarioRawWrite } from '../mcp/tools';

export class ScenarioFileSystemProvider implements vscode.FileSystemProvider {
  private readonly emitter = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
  readonly onDidChangeFile = this.emitter.event;
  private readonly stats = new Map<string, vscode.FileStat>();

  watch(): vscode.Disposable {
    return new vscode.Disposable(() => undefined);
  }

  async stat(uri: vscode.Uri): Promise<vscode.FileStat> {
    const parsed = parseScenarioPath(uri);
    if (!parsed) {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    const existing = this.stats.get(uri.toString());
    if (existing) {
      return existing;
    }

    const now = Date.now();
    if (parsed.kind === 'root' || parsed.kind === 'scenario') {
      return { type: vscode.FileType.Directory, ctime: now, mtime: now, size: 0 };
    }

    return { type: vscode.FileType.File, ctime: now, mtime: now, size: 0 };
  }

  async readDirectory(uri: vscode.Uri): Promise<[string, vscode.FileType][]> {
    const parsed = parseScenarioPath(uri);
    if (!parsed) {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    if (parsed.kind === 'root') {
      const scenarios = await scenarioList() as ScenarioSummary[];
      return scenarios.map((scenario) => [scenario.id, vscode.FileType.Directory]);
    }

    if (parsed.kind === 'scenario') {
      return [[SCENARIO_FILE_NAME, vscode.FileType.File]];
    }

    return [];
  }

  async readFile(uri: vscode.Uri): Promise<Uint8Array> {
    const parsed = parseScenarioPath(uri);
    if (!parsed || parsed.kind !== 'file') {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    const raw = (await scenarioRawRead(parsed.scenarioId)).content;
    const content = Buffer.from(raw, 'utf8');
    this.stats.set(uri.toString(), {
      type: vscode.FileType.File,
      ctime: Date.now(),
      mtime: Date.now(),
      size: content.length
    });
    return content;
  }

  async writeFile(uri: vscode.Uri, content: Uint8Array): Promise<void> {
    const parsed = parseScenarioPath(uri);
    if (!parsed || parsed.kind !== 'file') {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    await scenarioRawWrite(parsed.scenarioId, Buffer.from(content).toString('utf8'));

    this.stats.set(uri.toString(), {
      type: vscode.FileType.File,
      ctime: Date.now(),
      mtime: Date.now(),
      size: content.length
    });
    this.emitter.fire([{ type: vscode.FileChangeType.Changed, uri }]);
  }

  createDirectory(): void {
    throw vscode.FileSystemError.NoPermissions('Scenario filesystem is read/write per file only.');
  }

  delete(): void {
    throw vscode.FileSystemError.NoPermissions('Scenario filesystem deletion is not supported.');
  }

  rename(): void {
    throw vscode.FileSystemError.NoPermissions('Scenario filesystem rename is not supported.');
  }
}

export function scenarioUri(scenarioId: string): vscode.Uri {
  return vscode.Uri.from({ scheme: SCENARIO_SCHEME, path: `/${scenarioId}/${SCENARIO_FILE_NAME}` });
}

type ParsedScenarioPath =
  | { kind: 'root' }
  | { kind: 'scenario'; scenarioId: string }
  | { kind: 'file'; scenarioId: string };

function parseScenarioPath(uri: vscode.Uri): ParsedScenarioPath | null {
  const segments = uri.path.split('/').filter(Boolean);
  if (segments.length === 0) {
    return { kind: 'root' };
  }
  if (segments.length === 1) {
    return { kind: 'scenario', scenarioId: segments[0] };
  }
  if (segments.length === 2 && SCENARIO_FILE_NAMES.has(segments[1])) {
    return { kind: 'file', scenarioId: segments[0] };
  }
  return null;
}
