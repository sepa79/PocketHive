import * as vscode from 'vscode';

import { getOutputChannel } from './output';

export async function openJsonDocument(title: string, data: unknown): Promise<void> {
  const content = JSON.stringify(data, null, 2);
  const document = await vscode.workspace.openTextDocument({ content, language: 'json' });
  await vscode.window.showTextDocument(document, { preview: false });
  const outputChannel = getOutputChannel();
  outputChannel.appendLine(`[${new Date().toISOString()}] OPEN ${title}`);
  outputChannel.show(true);
}
