import {
  CHANGE_KIND,
  IMPACT_DEPTH,
  PROPAGATION_DECISION
} from "./constants.mjs";

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function impactDepthForHopCount(hopCount) {
  if (hopCount === 0) {
    return IMPACT_DEPTH.SELF;
  }
  if (hopCount === 1) {
    return IMPACT_DEPTH.DIRECT;
  }
  return IMPACT_DEPTH.TRANSITIVE;
}

function compareRouteCandidate(left, right) {
  return left.hopCount - right.hopCount
    || compareText(left.viaEdgeIds.join("\u0000"), right.viaEdgeIds.join("\u0000"))
    || compareText(left.impactNodeId, right.impactNodeId);
}

export function resolveImpactRoutes(policy, originNodeId, changeKind) {
  if (!Object.hasOwn(CHANGE_KIND, changeKind)) {
    throw new Error(`Unsupported impact route change kind ${JSON.stringify(changeKind)}`);
  }
  const nodeById = new Map(policy.impactNodes.map((node) => [node.id, node]));
  const origin = nodeById.get(originNodeId);
  if (!origin) {
    throw new Error(`Unknown impact route origin node ${originNodeId}`);
  }
  if (origin.noDocumentationChangeKinds.includes(changeKind)) {
    return [];
  }
  if (!origin.evaluateChangeKinds.includes(changeKind)) {
    throw new Error(
      `Impact node ${originNodeId} has no explicit documentation decision for ${changeKind}`
    );
  }

  const outgoing = new Map(policy.impactNodes.map((node) => [node.id, []]));
  for (const edge of policy.impactEdges) {
    outgoing.get(edge.fromNodeId)?.push(edge);
  }
  for (const edges of outgoing.values()) {
    edges.sort((left, right) =>
      compareText(left.id, right.id)
      || compareText(left.toNodeId, right.toNodeId)
    );
  }

  const initial = {
    impactNodeId: originNodeId,
    hopCount: 0,
    viaEdgeIds: []
  };
  const bestReachByNodeId = new Map([[originNodeId, initial]]);
  const bestExpandableByNodeId = new Map([[originNodeId, initial]]);
  const queue = [initial];
  while (queue.length > 0) {
    queue.sort(compareRouteCandidate);
    const current = queue.shift();
    if (bestExpandableByNodeId.get(current.impactNodeId) !== current) {
      continue;
    }
    for (const edge of outgoing.get(current.impactNodeId) ?? []) {
      const decisions = edge.decisions.filter((decision) =>
        decision.changeKinds.includes(changeKind)
      );
      if (decisions.length !== 1) {
        throw new Error(
          `Impact edge ${edge.id} must have exactly one decision for ${changeKind}`
        );
      }
      const propagation = decisions[0].propagation;
      if (![PROPAGATION_DECISION.STOP, PROPAGATION_DECISION.CONTINUE].includes(propagation)) {
        throw new Error(`Impact edge ${edge.id} has unsupported propagation ${propagation}`);
      }
      const candidate = {
        impactNodeId: edge.toNodeId,
        hopCount: current.hopCount + 1,
        viaEdgeIds: [...current.viaEdgeIds, edge.id]
      };
      const existingReach = bestReachByNodeId.get(candidate.impactNodeId);
      if (!existingReach || compareRouteCandidate(candidate, existingReach) < 0) {
        bestReachByNodeId.set(candidate.impactNodeId, candidate);
      }
      if (propagation === PROPAGATION_DECISION.CONTINUE) {
        const existingExpandable = bestExpandableByNodeId.get(candidate.impactNodeId);
        if (!existingExpandable || compareRouteCandidate(candidate, existingExpandable) < 0) {
          bestExpandableByNodeId.set(candidate.impactNodeId, candidate);
          queue.push(candidate);
        }
      }
    }
  }

  return [...bestReachByNodeId.values()]
    .sort(compareRouteCandidate)
    .map((route) => ({
      impactNodeId: route.impactNodeId,
      componentId: nodeById.get(route.impactNodeId).componentId,
      impactDepth: impactDepthForHopCount(route.hopCount),
      viaEdgeIds: route.viaEdgeIds
    }));
}
