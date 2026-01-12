export type SwarmSummary = {
  id: string;
  status?: string;
  health?: string;
  heartbeat?: string;
  templateId?: string;
};

export type ScenarioSummary = {
  id: string;
  name?: string;
};

export type ScenarioBee = {
  role?: string;
  image?: string;
  work?: {
    in?: string;
    out?: string;
  };
};

export type ScenarioDetail = {
  id?: string;
  name?: string;
  description?: string;
  template?: {
    image?: string;
    bees?: ScenarioBee[];
  };
  plan?: unknown;
};

export type JournalEntry = {
  timestamp?: string;
  swarmId?: string;
  kind?: string;
  type?: string;
  origin?: string;
  scope?: {
    role?: string;
    instance?: string;
  };
  [key: string]: unknown;
};

export type JournalPage = {
  items?: JournalEntry[];
};
