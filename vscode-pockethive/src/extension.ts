import * as vscode from 'vscode';

import { initPreviewProvider, PREVIEW_SCHEME } from './preview';
import { PocketHiveCompanionProvider } from './webview/companionProvider';

export function activate(context: vscode.ExtensionContext): void {
  const previews = initPreviewProvider();
  const provider = new PocketHiveCompanionProvider(context);
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(PREVIEW_SCHEME, previews),
    provider,
    vscode.window.registerWebviewViewProvider(
      PocketHiveCompanionProvider.viewType,
      provider,
      { webviewOptions: { retainContextWhenHidden: false } },
    ),
  );
}

export function deactivate(): void {
  // VS Code disposes the registered provider and its active MCP transport.
}
