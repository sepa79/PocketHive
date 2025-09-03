import type { Role } from '../role';

export const roleShape: Record<Role, string> = {
  Seeder: 'hexagon',
  Gatekeeper: 'shield',
  Worker: 'roundedRect',
  Postprocessor: 'cylinder',
  Trigger: 'triangleRight',
  LogAggregator: 'documentFolded',
};
