export type SwarmSummary = {
  id: string;
  runId: string;
  runtimeIntent: string;
  workloadIntent: string;
  controllerState: string;
  workloadState: string;
  health: string;
  runtimeResourceState: string;
  observedAt?: string;
  observationStale: boolean;
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
