export class McpClient {
  private client: import('@modelcontextprotocol/sdk/client/index.js').Client | null = null;
  private transport: import('@modelcontextprotocol/sdk/client/stdio.js').StdioClientTransport | null = null;

  constructor(private readonly serverPath: string, private readonly env: NodeJS.ProcessEnv) {}

  async connect(): Promise<void> {
    const { Client } = await import('@modelcontextprotocol/sdk/client/index.js');
    const { StdioClientTransport } = await import('@modelcontextprotocol/sdk/client/stdio.js');
    this.transport = new StdioClientTransport({
      command: 'node',
      args: [this.serverPath],
      env: this.env as Record<string, string>,
    });
    this.client = new Client(
      { name: 'pockethive-vscode', version: '1.0.0' },
      { capabilities: {} }
    );
    await this.client.connect(this.transport);
  }

  async callTool(name: string, args: Record<string, unknown> = {}): Promise<unknown> {
    if (!this.client) throw new Error('MCP client not connected');
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
    await this.client?.close().catch(() => {});
  }
}
