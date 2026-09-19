import { ConnectionContractError } from '../connection/contracts';

export interface ValidatedScenarioIdentity {
  readonly scenarioId: string;
  readonly scenarioName: string;
}

export type RepositoryDeploymentPlan =
  | ({ readonly kind: 'CREATE' } & ValidatedScenarioIdentity)
  | ({ readonly kind: 'CONFLICT' } & ValidatedScenarioIdentity & {
      readonly suggestedScenarioId: string;
      readonly suggestedScenarioName: string;
    });

export function planRepositoryDeployment(
  deployedCatalogue: unknown,
  validatedIdentity: ValidatedScenarioIdentity,
): RepositoryDeploymentPlan {
  if (!Array.isArray(deployedCatalogue)) {
    throw contract('DEPLOYED_SCENARIO_CATALOGUE_INVALID');
  }
  const identity = requiredIdentity(validatedIdentity.scenarioId, validatedIdentity.scenarioName);
  const conflict = deployedCatalogue.some(entry => {
    if (!entry || Array.isArray(entry)) return false;
    const id = (entry as Record<string, unknown>).id;
    return typeof id === 'string' && id.trim() === identity.scenarioId;
  });
  if (!conflict) return Object.freeze({ kind: 'CREATE' as const, ...identity });
  const suggested = suggestedRepositoryIdentity(identity.scenarioId, identity.scenarioName);
  return Object.freeze({
    kind: 'CONFLICT' as const,
    ...identity,
    suggestedScenarioId: suggested.scenarioId,
    suggestedScenarioName: suggested.scenarioName,
  });
}

export function suggestedRepositoryIdentity(scenarioId: string, scenarioName: string): ValidatedScenarioIdentity {
  const identity = requiredIdentity(scenarioId, scenarioName);
  return Object.freeze({
    scenarioId: `${identity.scenarioId}-01`,
    scenarioName: `${identity.scenarioName}-01`,
  });
}

function requiredIdentity(scenarioId: string, scenarioName: string): ValidatedScenarioIdentity {
  const normalizedId = scenarioId.trim();
  const normalizedName = scenarioName.trim();
  if (!normalizedId || !normalizedName) throw contract('REPOSITORY_SCENARIO_IDENTITY_INVALID');
  return Object.freeze({ scenarioId: normalizedId, scenarioName: normalizedName });
}

function contract(code: string): ConnectionContractError {
  return new ConnectionContractError(code, code);
}
