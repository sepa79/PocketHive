import { openPreviewDocument } from './preview';

type HelpTopic = 'settings' | 'hive' | 'buzz' | 'journal' | 'scenario';

type HelpContent = {
  title: string;
  body: string;
};

const HELP_CONTENT: Record<HelpTopic, HelpContent> = {
  settings: {
    title: 'PocketHive: Settings panel',
    body: `# Settings panel\n\nManage Hive base URLs for PocketHive.\n\n- Add Hive URL\n- Click an entry to set it active\n- Delete unused instances from the context menu\n\nThe extension derives Orchestrator and Scenario Manager endpoints from the active Hive URL.\n`
  },
  hive: {
    title: 'PocketHive: Hive panel',
    body: `# Hive panel\n\nShows the current swarms known to the Orchestrator.\n\n- Toolbar actions start/stop all swarms and open the UI.\n- Each swarm row has inline start/stop/remove actions (remove asks for confirmation).\n- Click a swarm to open details (raw JSON).\n\nUse this panel to manage swarm lifecycle at a glance.\n`
  },
  buzz: {
    title: 'PocketHive: Buzz panel',
    body: `# Buzz panel\n\nShows the Hive journal (system-level timeline) from the Orchestrator.\n\n- Entries are ordered newest-first.\n- Expand the filter row to choose a time window.\n- Click an entry to open raw JSON.\n\nUse this panel for fast, global signal triage.\n`
  },
  journal: {
    title: 'PocketHive: Journal panel',
    body: `# Journal panel\n\nShows per-swarm journal entries.\n\n- First level lists swarms.\n- Expanding a swarm shows recent entries.\n- Expand the filter row to choose a time window.\n- Click an entry to open raw JSON.\n\nUse this panel to debug a specific swarm.\n`
  },
  scenario: {
    title: 'PocketHive: Scenario panel',
    body: `# Scenario panel\n\nLists scenarios from Scenario Manager.\n\n- Expand a scenario to browse its files (scenario.yaml, schemas, http-templates).\n- Click a file to open it (raw YAML for scenario, read-only preview for others).\n- Use Preview to see a quick summary of bees and images.\n\nUse this panel to browse and edit scenarios directly from VS Code.\n`
  }
};

export async function openHelp(topic: HelpTopic): Promise<void> {
  const content = HELP_CONTENT[topic];
  await openPreviewDocument(content.title, content.body, 'markdown');
}
