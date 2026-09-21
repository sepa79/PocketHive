import { randomUUID } from 'node:crypto';
import * as vscode from 'vscode';

export const PREVIEW_SCHEME = 'pockethive-preview';

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
    const id = new URLSearchParams(uri.query).get('id');
    return id ? this.documents.get(id) ?? '' : '';
  }
}

let provider: PreviewProvider | undefined;

export function initPreviewProvider(): PreviewProvider {
  provider = new PreviewProvider();
  return provider;
}

export async function openPreviewDocument(title: string, content: string, language?: string): Promise<void> {
  if (!provider) throw new Error('Preview provider not initialized.');
  const uri = provider.createUri(title, content);
  const document = await vscode.workspace.openTextDocument(uri);
  if (language) await vscode.languages.setTextDocumentLanguage(document, language);
  await vscode.window.showTextDocument(document, { preview: true });
}

export async function openJsonPreview(title: string, data: unknown): Promise<void> {
  await openPreviewDocument(title, JSON.stringify(data, null, 2), 'json');
}
