import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
import { randomUUID } from 'crypto';
import { extname, isAbsolute, resolve } from 'path';
import * as vscode from 'vscode';

export type EvidenceSessionStatus = 'collecting_evidence' | 'need_more_evidence' | 'ready_to_generate';

export type EvidenceAnalysis = {
  status: EvidenceSessionStatus;
  knownFacts: string[];
  missingEvidence: string[];
  blockingQuestions: string[];
  recommendedPattern: string;
  rationale: string;
};

export type EvidenceSession = {
  sessionId: string;
  workspacePath: string;
  goal: string;
  artifactPaths: string[];
  notes: string[];
  createdAt: string;
  updatedAt: string;
  status: EvidenceSessionStatus;
  lastAnalysis: EvidenceAnalysis | null;
};

type EventPayload = {
  type: string;
  [key: string]: unknown;
};

const SESSION_ROOT = ['.pockethive-mcp', 'evidence-sessions'];

export async function startEvidenceSession(
  workspacePath: string,
  goal: string,
  artifactPaths: string[]
): Promise<EvidenceSession> {
  assertAbsolutePath('workspacePath', workspacePath);
  const sessionId = `evidence-${randomUUID()}`;
  const now = new Date().toISOString();
  const session: EvidenceSession = {
    sessionId,
    workspacePath,
    goal,
    artifactPaths: dedupePaths(artifactPaths),
    notes: [],
    createdAt: now,
    updatedAt: now,
    status: 'collecting_evidence',
    lastAnalysis: null
  };

  saveSession(session);
  writeActiveSessionPointer(workspacePath, sessionId);
  appendEvent(session, {
    type: 'session_started',
    goal,
    artifactPaths: session.artifactPaths
  });
  return session;
}

export function getActiveEvidenceSession(workspacePath: string): EvidenceSession | null {
  const pointerPath = getActiveSessionPointerPath(workspacePath);
  if (!existsSync(pointerPath)) {
    return null;
  }

  const sessionId = readFileSync(pointerPath, 'utf8').trim();
  if (!sessionId) {
    return null;
  }

  const sessionPath = getSessionFilePath(workspacePath, sessionId);
  if (!existsSync(sessionPath)) {
    return null;
  }

  return JSON.parse(readFileSync(sessionPath, 'utf8')) as EvidenceSession;
}

export function addArtifacts(session: EvidenceSession, artifactPaths: string[]): EvidenceSession {
  const next = {
    ...session,
    artifactPaths: dedupePaths([...session.artifactPaths, ...artifactPaths]),
    updatedAt: new Date().toISOString()
  };
  saveSession(next);
  appendEvent(next, {
    type: 'artifacts_added',
    artifactPaths
  });
  return next;
}

export function addNote(session: EvidenceSession, note: string): EvidenceSession {
  const next = {
    ...session,
    notes: [...session.notes, note],
    updatedAt: new Date().toISOString()
  };
  saveSession(next);
  appendEvent(next, {
    type: 'note_added',
    note
  });
  return next;
}

export async function analyzeEvidenceSession(
  session: EvidenceSession,
  model: vscode.LanguageModelChat,
  token: vscode.CancellationToken
): Promise<EvidenceSession> {
  const prompt = buildEvidencePrompt(session);
  const response = await model.sendRequest([vscode.LanguageModelChatMessage.User(prompt)], {
    justification: 'Analyze provided PocketHive scenario evidence and identify missing information before generation.'
  }, token);

  let text = '';
  for await (const chunk of response.text) {
    text += chunk;
  }

  const analysis = parseEvidenceAnalysis(text);
  const next: EvidenceSession = {
    ...session,
    status: analysis.status,
    lastAnalysis: analysis,
    updatedAt: new Date().toISOString()
  };
  saveSession(next);
  appendEvent(next, {
    type: 'analysis_completed',
    analysis
  });
  return next;
}

export function getSessionFileReferences(session: EvidenceSession): {
  sessionUri: vscode.Uri;
  eventsUri: vscode.Uri;
} {
  return {
    sessionUri: vscode.Uri.file(getSessionFilePath(session.workspacePath, session.sessionId)),
    eventsUri: vscode.Uri.file(getEventsFilePath(session.workspacePath, session.sessionId))
  };
}

function buildEvidencePrompt(session: EvidenceSession): string {
  const artifacts = session.artifactPaths
    .map((artifactPath) => renderArtifactSnippet(session.workspacePath, artifactPath))
    .join('\n\n');

  return [
    'You are analyzing evidence for a PocketHive scenario authoring session.',
    'PocketHive scenarios must stay aligned with the canonical scenario contract.',
    'Do not generate final YAML here.',
    'Your job is to decide whether the evidence is sufficient to draft a scenario and, if not, ask only blocking questions.',
    '',
    'Return JSON only with this shape:',
    '{',
    '  "status": "need_more_evidence" | "ready_to_generate",',
    '  "knownFacts": ["..."],',
    '  "missingEvidence": ["..."],',
    '  "blockingQuestions": ["..."],',
    '  "recommendedPattern": "rest-basic | request-builder | http-sequence | unknown",',
    '  "rationale": "..."',
    '}',
    '',
    'Rules:',
    '- Be conservative. If evidence is incomplete, return need_more_evidence.',
    '- Ask only the minimum blocking questions.',
    '- Distinguish real API journey steps from setup, assertions, or test harness noise.',
    '- Prefer PocketHive pattern names that already exist in the repo.',
    '',
    `Goal:\n${session.goal}`,
    '',
    session.notes.length > 0 ? `User notes:\n${session.notes.map((note, index) => `${index + 1}. ${note}`).join('\n')}` : 'User notes:\n(none)',
    '',
    `Artifacts (${session.artifactPaths.length}):`,
    artifacts || '(none)'
  ].join('\n');
}

function renderArtifactSnippet(workspacePath: string, artifactPath: string): string {
  const resolvedPath = resolveArtifactPath(workspacePath, artifactPath);
  if (!existsSync(resolvedPath)) {
    return renderArtifactStatus(artifactPath, 'missing', 'File not found. Ask the user to re-attach it or provide a text excerpt.');
  }

  const extension = extname(resolvedPath).toLowerCase();
  if (extension === '.pdf') {
    return renderArtifactStatus(
      artifactPath,
      'binary-pdf',
      'PDF content is not inlined in this POC. Ask the user for the relevant text excerpt or a converted text/markdown file.'
    );
  }

  try {
    const content = readFileSync(resolvedPath);
    if (looksBinary(content)) {
      return renderArtifactStatus(
        artifactPath,
        'binary',
        'Binary file content is not inlined in this POC. Ask the user for a text excerpt or an equivalent text file.'
      );
    }

    return [
      `Artifact: ${artifactPath}`,
      '```',
      content.toString('utf8').slice(0, 12000),
      '```'
    ].join('\n');
  } catch (error) {
    return renderArtifactStatus(
      artifactPath,
      'unreadable',
      error instanceof Error ? error.message : 'Unknown read error.'
    );
  }
}

function parseEvidenceAnalysis(text: string): EvidenceAnalysis {
  const trimmed = text.trim();
  const normalized = trimmed.startsWith('```')
    ? trimmed.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '')
    : trimmed;

  const parsed = JSON.parse(normalized) as Partial<EvidenceAnalysis>;
  return {
    status: parsed.status === 'ready_to_generate' ? 'ready_to_generate' : 'need_more_evidence',
    knownFacts: Array.isArray(parsed.knownFacts) ? parsed.knownFacts.map(String) : [],
    missingEvidence: Array.isArray(parsed.missingEvidence) ? parsed.missingEvidence.map(String) : [],
    blockingQuestions: Array.isArray(parsed.blockingQuestions) ? parsed.blockingQuestions.map(String) : [],
    recommendedPattern: typeof parsed.recommendedPattern === 'string' ? parsed.recommendedPattern : 'unknown',
    rationale: typeof parsed.rationale === 'string' ? parsed.rationale : 'No rationale provided.'
  };
}

function saveSession(session: EvidenceSession): void {
  const sessionDir = getSessionDir(session.workspacePath, session.sessionId);
  mkdirSync(sessionDir, { recursive: true });
  writeFileSync(getSessionFilePath(session.workspacePath, session.sessionId), JSON.stringify(session, null, 2), 'utf8');
}

function appendEvent(session: EvidenceSession, payload: EventPayload): void {
  const sessionDir = getSessionDir(session.workspacePath, session.sessionId);
  mkdirSync(sessionDir, { recursive: true });
  appendFileSync(
    getEventsFilePath(session.workspacePath, session.sessionId),
    `${JSON.stringify({ timestamp: new Date().toISOString(), ...payload })}\n`,
    'utf8'
  );
}

function writeActiveSessionPointer(workspacePath: string, sessionId: string): void {
  mkdirSync(getSessionRootDir(workspacePath), { recursive: true });
  writeFileSync(getActiveSessionPointerPath(workspacePath), `${sessionId}\n`, 'utf8');
}

function getSessionRootDir(workspacePath: string): string {
  return resolve(workspacePath, ...SESSION_ROOT);
}

function getSessionDir(workspacePath: string, sessionId: string): string {
  return resolve(getSessionRootDir(workspacePath), sessionId);
}

function getSessionFilePath(workspacePath: string, sessionId: string): string {
  return resolve(getSessionDir(workspacePath, sessionId), 'session.json');
}

function getEventsFilePath(workspacePath: string, sessionId: string): string {
  return resolve(getSessionDir(workspacePath, sessionId), 'events.jsonl');
}

function getActiveSessionPointerPath(workspacePath: string): string {
  return resolve(getSessionRootDir(workspacePath), 'active-session.txt');
}

function resolveArtifactPath(workspacePath: string, artifactPath: string): string {
  return isAbsolute(artifactPath) ? artifactPath : resolve(workspacePath, artifactPath);
}

function dedupePaths(paths: string[]): string[] {
  return [...new Set(paths.map((path) => path.trim()).filter(Boolean))];
}

function assertAbsolutePath(name: string, value: string): void {
  if (!value || !isAbsolute(value)) {
    throw new Error(`${name} must be an absolute path.`);
  }
}

export async function pickEvidenceArtifacts(workspaceFolder: vscode.WorkspaceFolder): Promise<string[] | undefined> {
  const uris = await vscode.window.showOpenDialog({
    title: 'Select evidence files',
    defaultUri: workspaceFolder.uri,
    canSelectMany: true,
    canSelectFiles: true,
    canSelectFolders: false,
    openLabel: 'Use as evidence'
  });

  if (!uris || uris.length === 0) {
    return undefined;
  }

  return uris.map((uri) => makeWorkspaceRelativePath(workspaceFolder.uri.fsPath, uri.fsPath));
}

function makeWorkspaceRelativePath(workspacePath: string, filePath: string): string {
  const normalizedWorkspace = workspacePath.endsWith('/') ? workspacePath : `${workspacePath}/`;
  return filePath.startsWith(normalizedWorkspace) ? filePath.slice(normalizedWorkspace.length) : filePath;
}

function renderArtifactStatus(artifactPath: string, status: string, note: string): string {
  return [
    `Artifact: ${artifactPath}`,
    `Status: ${status}`,
    `Note: ${note}`
  ].join('\n');
}

function looksBinary(content: Buffer): boolean {
  const length = Math.min(content.length, 4096);
  for (let index = 0; index < length; index += 1) {
    if (content[index] === 0) {
      return true;
    }
  }
  return false;
}
