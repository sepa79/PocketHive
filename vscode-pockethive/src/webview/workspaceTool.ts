import { CompanionTab } from './messages';

export const SIDEBAR_EVENT_LIMIT = 10;

export function workspaceToolCall(tab: CompanionTab): { name: string; arguments: Record<string, unknown> } {
  switch (tab) {
    case 'Hive': return { name: 'swarm_list', arguments: {} };
    case 'Buzz': return { name: 'debug_hive_journal', arguments: { limit: SIDEBAR_EVENT_LIMIT } };
    case 'Journal': return { name: 'swarm_list', arguments: {} };
    case 'Scenarios': return { name: 'scenario_list', arguments: {} };
    case 'Debug': return { name: 'swarm_list', arguments: {} };
  }
}
