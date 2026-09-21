import { ConnectionContractError, McpConnectionProfile, OAuthSessionStore } from '../connection/contracts';
import { createConnectionProfile } from '../connection/profile';

const PROFILES_KEY = 'pockethive.mcpConnectionProfiles';
const ACTIVE_PROFILE_KEY = 'pockethive.activeMcpConnectionProfile';

export interface KeyValueStore {
  get<T>(key: string): T | undefined;
  update(key: string, value: unknown): PromiseLike<void>;
}

export class McpConnectionProfileRepository {
  constructor(
    private readonly global: KeyValueStore,
    private readonly workspace: KeyValueStore,
    private readonly secrets: OAuthSessionStore,
  ) {}

  list(): McpConnectionProfile[] {
    const stored = this.global.get<unknown>(PROFILES_KEY);
    if (stored === undefined) return [];
    if (!Array.isArray(stored)) {
      throw new ConnectionContractError('PROFILE_STORE_CORRUPT', 'PROFILE_STORE_CORRUPT: profiles must be an array');
    }
    return stored.map(item => decodeProfile(item));
  }

  async save(profile: McpConnectionProfile): Promise<void> {
    const profiles = this.list();
    const updated = [...profiles.filter(existing => existing.id !== profile.id), profile]
      .sort((left, right) => left.displayName.localeCompare(right.displayName));
    await this.global.update(PROFILES_KEY, updated);
  }

  async remove(profileId: string): Promise<void> {
    const profiles = this.list();
    const removed = profiles.find(profile => profile.id === profileId);
    if (!removed) {
      throw new ConnectionContractError('PROFILE_NOT_FOUND', profileId);
    }
    await this.global.update(PROFILES_KEY, profiles.filter(profile => profile.id !== profileId));
    await this.secrets.delete(removed.secretKey);
    if (this.activeProfileId() === profileId) {
      await this.workspace.update(ACTIVE_PROFILE_KEY, undefined);
    }
  }

  activeProfileId(): string | undefined {
    const value = this.workspace.get<unknown>(ACTIVE_PROFILE_KEY);
    if (value === undefined) return undefined;
    if (typeof value !== 'string' || !value.trim()) {
      throw new ConnectionContractError('ACTIVE_PROFILE_STORE_CORRUPT', 'ACTIVE_PROFILE_STORE_CORRUPT');
    }
    return value;
  }

  async select(profileId: string): Promise<void> {
    if (!this.list().some(profile => profile.id === profileId)) {
      throw new ConnectionContractError('PROFILE_NOT_FOUND', profileId);
    }
    await this.workspace.update(ACTIVE_PROFILE_KEY, profileId);
  }
}

function decodeProfile(value: unknown): McpConnectionProfile {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new ConnectionContractError('PROFILE_STORE_CORRUPT', 'PROFILE_STORE_CORRUPT: profile must be an object');
  }
  const profile = value as Record<string, unknown>;
  const expectedKeys = [
    'authenticationMode', 'displayName', 'endpointSecurityMode', 'id', 'mcpUrl', 'secretKey',
  ];
  if (Object.keys(profile).sort().join('|') !== expectedKeys.join('|')
      || profile.authenticationMode !== 'OAUTH_AUTHORIZATION_CODE_PKCE'
      || (profile.endpointSecurityMode !== 'REMOTE_HTTPS'
          && profile.endpointSecurityMode !== 'LOCAL_LOOPBACK_HTTP')) {
    throw new ConnectionContractError('PROFILE_STORE_CORRUPT', 'PROFILE_STORE_CORRUPT: profile contract mismatch');
  }
  if (typeof profile.id !== 'string' || typeof profile.displayName !== 'string'
      || typeof profile.mcpUrl !== 'string' || typeof profile.secretKey !== 'string') {
    throw new ConnectionContractError('PROFILE_STORE_CORRUPT', 'PROFILE_STORE_CORRUPT: profile fields invalid');
  }
  return createConnectionProfile({
    id: profile.id,
    displayName: profile.displayName,
    mcpUrl: profile.mcpUrl,
    endpointSecurityMode: profile.endpointSecurityMode,
    secretKey: profile.secretKey,
  });
}
