import { existsSync } from 'fs';
import { resolve } from 'path';
import * as vscode from 'vscode';

import { getActiveEvidenceSession, getSessionFileReferences } from './evidenceSession';

export async function openActiveEvidenceSession(): Promise<void> {
  const workspaceFolder = pickWorkspaceFolder();
  if (!workspaceFolder) {
    vscode.window.showErrorMessage('PocketHive: open a workspace folder before opening the active evidence session.');
    return;
  }

  const session = getActiveEvidenceSession(workspaceFolder.uri.fsPath);
  if (!session) {
    vscode.window.showInformationMessage('PocketHive: no active evidence session in this workspace.');
    return;
  }

  const { sessionUri } = getSessionFileReferences(session);
  if (!existsSync(sessionUri.fsPath)) {
    vscode.window.showErrorMessage(`PocketHive: session file not found at ${sessionUri.fsPath}.`);
    return;
  }

  await vscode.commands.executeCommand('vscode.open', sessionUri);
}

export async function openChatWizardArchitectureDoc(): Promise<void> {
  const workspaceFolder = pickWorkspaceFolder();
  if (!workspaceFolder) {
    return;
  }

  const docUri = vscode.Uri.file(resolve(workspaceFolder.uri.fsPath, 'docs', 'concepts', 'pockethive-chat-wizard-architecture.md'));
  if (!existsSync(docUri.fsPath)) {
    vscode.window.showErrorMessage(`PocketHive: architecture doc not found at ${docUri.fsPath}.`);
    return;
  }

  await vscode.commands.executeCommand('vscode.open', docUri);
}

function pickWorkspaceFolder(): vscode.WorkspaceFolder | undefined {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    return undefined;
  }
  return folders[0];
}
