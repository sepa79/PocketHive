export type Role =
  | 'Seeder'
  | 'Gatekeeper'
  | 'Worker'
  | 'Postprocessor'
  | 'Trigger'
  | 'LogAggregator';

export const BeeTitle: Record<Role, string> = {
  Seeder: 'Seeder',
  Gatekeeper: 'Gatekeeper',
  Worker: 'Worker',
  Postprocessor: 'BeeCounter',
  Trigger: 'Activator',
  LogAggregator: 'Scribe',
};
