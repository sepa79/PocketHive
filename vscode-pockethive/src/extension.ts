import * as vscode from 'vscode';

import { PocketHiveCompanionProvider } from './webview/companionProvider';

export function activate(context: vscode.ExtensionContext): void {
  const provider = new PocketHiveCompanionProvider(context);
  context.subscriptions.push(
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
