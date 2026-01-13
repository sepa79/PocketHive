import * as vscode from 'vscode';
import { randomUUID } from 'crypto';

import { PREVIEW_SCHEME } from './constants';
import { getOutputChannel } from './output';

class PreviewProvider implements vscode.TextDocumentContentProvider {
  private readonly emitter = new vscode.EventEmitter<vscode.Uri>();
  readonly onDidChange = this.emitter.event;
  private readonly documents = new Map<string, string>();

  createUri(title: string, content: string): vscode.Uri {
    const id = randomUUID();
    this.documents.set(id, content);
    const safeTitle = encodeURIComponent(title).replace(/%2F/g, '_');
    return vscode.Uri.from({ scheme: PREVIEW_SCHEME, path: `/${safeTitle}`, query: `id=${id}` });
  }

  provideTextDocumentContent(uri: vscode.Uri): string {
    const params = new URLSearchParams(uri.query);
    const id = params.get('id');
    if (!id) {
      return '';
    }
    return this.documents.get(id) ?? '';
  }
}

let provider: PreviewProvider | undefined;

export function initPreviewProvider(): PreviewProvider {
  provider = new PreviewProvider();
  return provider;
}

export async function openPreviewDocument(
  title: string,
  content: string,
  language?: string,
  preview = true
): Promise<void> {
  if (!provider) {
    throw new Error('Preview provider not initialized.');
  }
  const uri = provider.createUri(title, content);
  const document = await vscode.workspace.openTextDocument(uri);
  if (language) {
    await vscode.languages.setTextDocumentLanguage(document, language);
  }
  await vscode.window.showTextDocument(document, { preview });
  const outputChannel = getOutputChannel();
  outputChannel.appendLine(`[${new Date().toISOString()}] OPEN ${title}`);
  outputChannel.show(true);
}

export async function openJsonPreview(title: string, data: unknown, preview = true): Promise<void> {
  const content = JSON.stringify(data, null, 2);
  await openPreviewDocument(title, content, 'json', preview);
}
