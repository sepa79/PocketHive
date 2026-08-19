import {
  ConnectionEvidence,
  EndpointValidationPort,
  McpConnectionProfile,
  POCKETHIVE_MCP_SCOPES,
  ScopedAuthenticationPort,
} from '../connection/contracts';

export interface ScopedMcpClient {
  connect(endpoint: string, accessToken: string, signal?: AbortSignal): Promise<ConnectionEvidence>;
  callTool(name: string, args: Record<string, unknown>): Promise<unknown>;
  close(): Promise<void>;
}

type ScopedMcpClientFactory = () => ScopedMcpClient;

export class ScopedMcpToolRunner {
  constructor(
    private readonly endpoints: EndpointValidationPort,
    private readonly authentication: ScopedAuthenticationPort,
    private readonly clients: ScopedMcpClientFactory,
  ) {}

  async call(
    profile: McpConnectionProfile,
    tool: string,
    args: Record<string, unknown>,
    signal = new AbortController().signal,
  ): Promise<unknown> {
    const endpoint = await this.endpoints.validate(profile);
    const session = await this.authentication.authenticateForScopes(profile, endpoint, [
      POCKETHIVE_MCP_SCOPES.DISCOVER,
      POCKETHIVE_MCP_SCOPES.READ,
      POCKETHIVE_MCP_SCOPES.OPERATE,
    ], signal);
    const client = this.clients();
    try {
      await client.connect(profile.mcpUrl, session.accessToken, signal);
      return await client.callTool(tool, args);
    } finally {
      await client.close();
    }
  }
}
