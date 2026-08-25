import {
  ConnectionEvidence,
  McpConnectionProfile,
  McpConnectionTestPort,
  OAuthSession,
} from '../connection/contracts';
import { McpHttpClient } from './httpClient';

export class ActiveMcpConnection implements McpConnectionTestPort {
  private client?: McpHttpClient;

  constructor(private readonly clients: () => McpHttpClient) {}

  async test(
    profile: McpConnectionProfile,
    session: OAuthSession,
    signal: AbortSignal,
  ): Promise<ConnectionEvidence> {
    const candidate = this.clients();
    let evidence: ConnectionEvidence;
    try {
      evidence = await candidate.connect(profile.mcpUrl, session.accessToken, signal);
    } catch (error) {
      await candidate.close().catch(() => undefined);
      throw error;
    }
    const replaced = this.client;
    this.client = candidate;
    if (replaced) await replaced.close().catch(() => undefined);
    return evidence;
  }

  async callTool(name: string, args: Record<string, unknown> = {}): Promise<unknown> {
    if (!this.client) throw new Error('MCP_NOT_CONNECTED');
    return this.client.callTool(name, args);
  }

  async readResource(uri: string): Promise<Record<string, unknown>> {
    if (!this.client) throw new Error('MCP_NOT_CONNECTED');
    return this.client.readResource(uri);
  }

  async uploadArchive(uploadUrl: string, archive: Uint8Array, signal?: AbortSignal): Promise<unknown> {
    if (!this.client) throw new Error('MCP_NOT_CONNECTED');
    return this.client.uploadArchive(uploadUrl, archive, signal);
  }

  async close(): Promise<void> {
    const current = this.client;
    this.client = undefined;
    if (current) await current.close();
  }
}
