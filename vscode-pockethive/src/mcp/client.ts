import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

export class McpClient {
  private client: Client;
  private transport: StdioClientTransport;

  /**
   * @param serverPath  Absolute path to server.mjs
   * @param env         Environment variables to inject at spawn time
   */
  constructor(serverPath: string, env: NodeJS.ProcessEnv) {
    this.transport = new StdioClientTransport({
      command: 'node',
      args: [serverPath],
      env: env as Record<string, string>,
    });
    this.client = new Client(
      { name: 'pockethive-vscode', version: '1.0.0' },
      { capabilities: {} }
    );
  }

  async connect(): Promise<void> {
    await this.client.connect(this.transport);
  }

  async callTool(name: string, args: Record<string, unknown> = {}): Promise<unknown> {
    const result = await this.client.callTool({ name, arguments: args });
    if (result.isError) {
      throw new Error(String((result.content as Array<{ text?: string }>)?.[0]?.text ?? 'MCP tool error'));
    }
    const text = (result.content as Array<{ type: string; text?: string }>)?.[0]?.text;
    if (typeof text === 'string') {
      try { return JSON.parse(text); } catch { return text; }
    }
    return result.content;
  }

  async close(): Promise<void> {
    await this.client.close().catch(() => {});
  }
}
