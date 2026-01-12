import * as vscode from 'vscode';

let outputChannel: vscode.OutputChannel | undefined;

export function initOutputChannel(): vscode.OutputChannel {
  outputChannel = vscode.window.createOutputChannel('PocketHive');
  return outputChannel;
}

export function getOutputChannel(): vscode.OutputChannel {
  if (!outputChannel) {
    throw new Error('PocketHive output channel is not initialized.');
  }
  return outputChannel;
}

export function disposeOutputChannel(): void {
  if (outputChannel) {
    outputChannel.dispose();
    outputChannel = undefined;
  }
}
