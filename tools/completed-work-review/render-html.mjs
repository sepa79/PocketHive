import {
  COMPARISON_MARKERS,
  CONFIDENCE_LABELS,
  ENUM_LABELS,
  FRESHNESS_MARKERS,
  GATE_MARKERS,
  IDENTITY_MATCH_MARKERS,
  INDEPENDENT_REVIEW_MARKERS,
  READINESS_MARKERS,
  SEARCH_DISCOVERY_MARKERS,
  SCORE_STATUS_MARKERS,
  assertEvaluatorExecutionProvenance,
  assertScoreQuarantine,
  comparisonMarker,
  prepareEvidenceReceipts,
  stableDisplay,
  validatedScoreAnchors,
  valueFrom,
} from './render-markdown.mjs';

const STATUS_CLASSES = Object.freeze({
  IMPROVED: 'status-good', DECREASED: 'status-bad', UNCHANGED: 'status-neutral', UNVERIFIED: 'status-warning',
  READY: 'status-good', NOT_READY: 'status-bad', VERIFIED: 'status-good', FAILED: 'status-bad', NOT_VERIFIED: 'status-warning',
  FRESH: 'status-good', STALE: 'status-bad', MATCH: 'status-good', MISMATCH: 'status-bad',
  CHANGED_SYNCHRONIZED: 'status-good', CHANGED_ACTION_REQUIRED: 'status-bad', NO_MATERIAL_CHANGE: 'status-neutral', NOT_APPLICABLE: 'status-neutral',
});

function escapeHtml(value) {
  return stableDisplay(value)
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

function statusClass(status) {
  return valueFrom(STATUS_CLASSES, status, 'render status');
}

function score(value) {
  return value === null ? 'N/V' : value.toFixed(1);
}

function enumHtml(value, labels, kind) {
  return `${escapeHtml(valueFrom(labels, value, kind))} <code>${escapeHtml(value)}</code>`;
}

function markedEnumHtml(markerMap, value, kind) {
  return `${valueFrom(markerMap, value, kind)} <code>${escapeHtml(value)}</code>`;
}

function comparisonHtml(status, delta) {
  return `${comparisonMarker(status, delta)} <code>${escapeHtml(status)}</code>`;
}

function submittedComparisonHtml(status, delta) {
  return `<strong class="status-warning">⚠ Submitted only / not a canonical delta.</strong><br>${comparisonHtml(status, delta)}`;
}

function booleanHtml(value) {
  return `${value ? 'Yes' : 'No'} <code>${String(value)}</code>`;
}

function list(items, formatter = escapeHtml, noneText = 'None') {
  return items.length === 0
    ? `<span class="muted">${escapeHtml(noneText)}</span>`
    : `<ul class="compact">${items.map((item) => `<li>${formatter(item)}</li>`).join('')}</ul>`;
}

function identityCards(identityRefs) {
  return [
    ['Change baseline identity ID', `<code>${escapeHtml(identityRefs.baselineIdentityId)}</code>`],
    ['Candidate identity ID', `<code>${escapeHtml(identityRefs.candidateIdentityId)}</code>`],
    ['Candidate verification target', enumHtml(identityRefs.candidateVerificationTarget, ENUM_LABELS.candidateVerificationTarget, 'candidate verification target')],
    ['Approved reference evidence', list(identityRefs.approvedReferenceEvidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None supplied')],
    ['Deployment reference identity ID', `<code>${escapeHtml(identityRefs.deploymentIdentityId)}</code>`],
  ].map(([label, value]) => `<article class="card"><h3>${label}</h3>${value}</article>`).join('');
}

function dimensionContract(dimension) {
  const scoreAnchors = validatedScoreAnchors(dimension);
  return `<dl class="compact-dl">
    <div><dt>ID</dt><dd><code>${escapeHtml(dimension.id)}</code></dd></div>
    <div><dt>Kind</dt><dd>${enumHtml(dimension.kind, ENUM_LABELS.dimensionKind, 'dimension kind')}</dd></div>
    <div><dt>Direction</dt><dd>${enumHtml(dimension.direction, ENUM_LABELS.direction, 'score direction')}</dd></div>
    <div><dt>Criterion</dt><dd>${escapeHtml(dimension.criterion)}</dd></div>
    <div><dt>Score anchors</dt><dd>${list(scoreAnchors, (anchor) => `<strong>${escapeHtml(anchor.score)}</strong> — ${escapeHtml(anchor.description)}`)}</dd></div>
    <div><dt>Required</dt><dd>${booleanHtml(dimension.required)}</dd></div>
    <div><dt>Weight</dt><dd>${escapeHtml(dimension.weight)}%</dd></div>
    <div><dt>Score evidence</dt><dd class="${statusClass(dimension.scoreStatus)}">${markedEnumHtml(SCORE_STATUS_MARKERS, dimension.scoreStatus, 'score status')}</dd></div>
  </dl>`;
}

function comparisonTable(review, hasBaseline) {
  const rows = review.dimensions.map((dimension) => `<tr>
    <th scope="row">${escapeHtml(dimension.label)}</th>
    <td>${dimensionContract(dimension)}</td>
    ${hasBaseline ? `<td class="number">${score(dimension.baseline)}</td>` : ''}
    <td class="number">${score(dimension.current)}</td>
    <td class="${statusClass(dimension.comparisonStatus)}">${comparisonHtml(dimension.comparisonStatus, dimension.delta)}</td>
    <td>${list(dimension.evidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}<p><strong>Notes:</strong> ${escapeHtml(dimension.notes)}</p></td>
  </tr>`).join('');
  return `<div class="table-scroll"><table><caption>Bound dimension score comparison</caption><thead><tr>
    <th scope="col">Dimension</th><th scope="col">Contract</th>${hasBaseline ? '<th scope="col">Baseline / Production</th>' : ''}
    <th scope="col">Candidate / Current</th><th scope="col">${hasBaseline ? 'Delta' : 'Status'}</th><th scope="col">Evidence and notes</th>
  </tr></thead><tbody>${rows}</tbody></table></div>`;
}

function submittedScoresTable(review, hasBaseline) {
  const rows = review.dimensions.map((dimension) => `<tr class="${dimension.scoreStatus === 'NOT_VERIFIED' ? 'quarantined-row' : ''}">
    <th scope="row">${escapeHtml(dimension.label)}</th>
    <td class="${statusClass(dimension.scoreStatus)}">${markedEnumHtml(SCORE_STATUS_MARKERS, dimension.scoreStatus, 'score status')}</td>
    ${hasBaseline ? `<td class="number">${score(dimension.submittedBaseline)}</td>` : ''}
    <td class="number">${score(dimension.submittedCurrent)}</td>
    <td>${submittedComparisonHtml(dimension.submittedComparisonStatus, dimension.submittedDelta)}</td>
  </tr>`).join('');
  const overallState = review.dimensions.every(({ scoreStatus }) => scoreStatus === 'VERIFIED')
    ? 'All dimension submissions corroborated; the canonical Overall remains authoritative.'
    : 'One or more dimension submissions lack trusted score evidence; this aggregate is not a score.';
  return `<aside class="quarantine" aria-labelledby="submitted-scores-heading">
    <h3 id="submitted-scores-heading">Submitted score inputs — audit only</h3>
    <p><strong>Not canonical scores.</strong> These values came from the review request and are shown only for audit. They do not contribute to the canonical comparison, Overall, readiness, or verdict unless trusted score attestations verify them. Use the canonical table above for every completion claim.</p>
    <div class="table-scroll"><table><caption>Quarantined request-submitted score inputs</caption><thead><tr>
      <th scope="col">Submitted input</th><th scope="col">Score-evidence state</th>${hasBaseline ? '<th scope="col">Submitted baseline</th>' : ''}<th scope="col">Submitted candidate</th><th scope="col">Submitted comparison</th>
    </tr></thead><tbody>${rows}<tr class="overall-row quarantined-row"><th scope="row">Submitted Overall input</th><td>${escapeHtml(overallState)}</td>${hasBaseline ? `<td class="number">${score(review.submittedOverall.baseline)}</td>` : ''}<td class="number">${score(review.submittedOverall.current)}</td><td>${submittedComparisonHtml(review.submittedOverall.comparisonStatus, review.submittedOverall.delta)}</td></tr></tbody></table></div>
  </aside>`;
}

function readinessTable(review) {
  const openMaterial = review.blockers.filter((item) => item.severity === 'MATERIAL' && item.status === 'OPEN').length;
  const rows = review.gates.map((gate) => `<tr>
    <th scope="row"><code>${escapeHtml(gate.id)}</code><span class="meta">${escapeHtml(gate.summary)}</span></th>
    <td class="${statusClass(gate.status)}">${markedEnumHtml(GATE_MARKERS, gate.status, 'gate status')}</td>
    <td>${booleanHtml(gate.required)}</td>
    <td>${list(gate.evidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</td>
    <td>${list(gate.blockerRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</td>
  </tr>`).join('');
  const readiness = markedEnumHtml(READINESS_MARKERS, review.readinessVerdict, 'readiness verdict');
  return `<div class="table-scroll"><table><caption>Absolute readiness gates</caption><thead><tr>
    <th scope="col">Gate</th><th scope="col">Status</th><th scope="col">Required</th><th scope="col">Evidence</th><th scope="col">Blockers</th>
  </tr></thead><tbody>${rows}<tr class="overall-row"><th scope="row">Overall readiness</th>
    <td class="${statusClass(review.readinessVerdict)}">${readiness}</td><td>${booleanHtml(true)}</td>
    <td>${openMaterial} open material blocker${openMaterial === 1 ? '' : 's'}</td><td>See blocker drill-down</td>
  </tr></tbody></table></div>`;
}

function blockerMarker(blocker) {
  if (blocker.status === 'RESOLVED') return '🟢 Resolved';
  if (blocker.status !== 'OPEN') throw new TypeError(`Unsupported blocker status: ${String(blocker.status)}`);
  if (blocker.severity === 'MATERIAL') return '🔴 Open material blocker';
  if (blocker.severity === 'NON_MATERIAL') return '🟠 Open non-material blocker';
  throw new TypeError(`Unsupported blocker severity: ${String(blocker.severity)}`);
}

function blockerDrilldown(blockers) {
  if (blockers.length === 0) return '<p class="muted">No blockers recorded.</p>';
  return blockers.map((blocker) => `<details>
    <summary>${blockerMarker(blocker)} — <code>${escapeHtml(blocker.id)}</code> — ${escapeHtml(blocker.summary)}</summary>
    <div class="details-body"><dl>
      <div><dt>Severity</dt><dd>${enumHtml(blocker.severity, ENUM_LABELS.severity, 'blocker severity')}</dd></div>
      <div><dt>Status</dt><dd>${enumHtml(blocker.status, ENUM_LABELS.blockerStatus, 'blocker status')}</dd></div>
      <div><dt>Evidence</dt><dd>${list(blocker.evidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</dd></div>
      <div><dt>Unlock kind</dt><dd>${enumHtml(blocker.unlock.kind, ENUM_LABELS.unlockKind, 'unlock kind')}</dd></div>
      <div><dt>Unlock condition</dt><dd>${escapeHtml(blocker.unlock.description)}</dd></div>
      <div><dt>Required evidence kinds</dt><dd>${list(blocker.unlock.requiredEvidenceKinds, (kind) => enumHtml(kind, ENUM_LABELS.evidenceKind, 'evidence kind'))}</dd></div>
    </dl></div>
  </details>`).join('');
}

function overallTable(review, hasBaseline) {
  const marker = comparisonHtml(review.overall.comparisonStatus, review.overall.delta);
  return `<div class="table-scroll"><table><caption>Overall score from displayed profile scores</caption><thead><tr>
    <th scope="col">Dimension</th>${hasBaseline ? '<th scope="col">Baseline / Production</th>' : ''}<th scope="col">Candidate / Current</th><th scope="col">${hasBaseline ? 'Delta' : 'Status'}</th><th scope="col">Evidence</th>
  </tr></thead><tbody><tr class="overall-row"><th scope="row">Overall</th>${hasBaseline ? `<td class="number">${score(review.overall.baseline)}</td>` : ''}<td class="number">${score(review.overall.current)}</td>
    <td class="${statusClass(review.overall.comparisonStatus)}">${marker}</td><td><code>${escapeHtml(review.evidenceManifestDigest)}</code></td>
  </tr></tbody></table></div>`;
}

function dimensionReference(dimensionId) {
  return dimensionId === null
    ? 'Cross-cutting (not bound to one dimension)'
    : `<code>${escapeHtml(dimensionId)}</code>`;
}

function regressionList(regressions) {
  if (regressions.length === 0) return '<p class="muted">None recorded.</p>';
  return `<ul class="findings">${regressions.map((regression) => `<li>
    <strong><code>${escapeHtml(regression.id)}</code> — ${escapeHtml(regression.summary)}</strong>
    <dl>
      <div><dt>Dimension</dt><dd>${dimensionReference(regression.dimensionId)}</dd></div>
      <div><dt>Severity</dt><dd>${enumHtml(regression.severity, ENUM_LABELS.severity, 'regression severity')}</dd></div>
      <div><dt>Disposition</dt><dd>${enumHtml(regression.disposition, ENUM_LABELS.regressionDisposition, 'regression disposition')}</dd></div>
      <div><dt>Evidence</dt><dd>${list(regression.evidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</dd></div>
    </dl></li>`).join('')}</ul>`;
}

function gapList(gaps) {
  if (gaps.length === 0) return '<p class="muted">None recorded.</p>';
  return `<ul class="findings">${gaps.map((gap) => `<li>
    <strong><code>${escapeHtml(gap.id)}</code> — ${escapeHtml(gap.summary)}</strong>
    <dl>
      <div><dt>Dimension</dt><dd>${dimensionReference(gap.dimensionId)}</dd></div>
      <div><dt>Severity</dt><dd>${enumHtml(gap.severity, ENUM_LABELS.severity, 'gap severity')}</dd></div>
      <div><dt>Evidence</dt><dd>${list(gap.evidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</dd></div>
      <div><dt>Unlock kind</dt><dd>${enumHtml(gap.unlock.kind, ENUM_LABELS.unlockKind, 'unlock kind')}</dd></div>
      <div><dt>Unlock condition</dt><dd>${escapeHtml(gap.unlock.description)}</dd></div>
      <div><dt>Required evidence kinds</dt><dd>${list(gap.unlock.requiredEvidenceKinds, (kind) => enumHtml(kind, ENUM_LABELS.evidenceKind, 'evidence kind'))}</dd></div>
    </dl></li>`).join('')}</ul>`;
}

function warningPanel(review) {
  const warnings = ['Evidence freshness is valid only for the bound identities and manifest digest. Re-run validation after any bound input changes.'];
  if (review.freshness.status === 'STALE') warnings.push('The bound evidence is stale. Readiness must not be promoted from this report.');
  if (review.freshness.status === 'NOT_VERIFIED') warnings.push('Evidence freshness is not verified.');
  if (review.comparisonStatus === 'UNVERIFIED') warnings.push('The comparison is unverified; no baseline delta may be claimed.');
  if (review.dimensions.some(({ scoreStatus }) => scoreStatus === 'NOT_VERIFIED')) {
    warnings.push('Request-submitted scores are quarantined. Canonical dimension and Overall scores remain N/V until trusted score attestations verify them.');
  }
  if (review.gates.some((gate) => gate.status === 'FAILED')) warnings.push('At least one required readiness gate failed.');
  if (review.gates.some((gate) => gate.status === 'NOT_VERIFIED')) warnings.push('At least one readiness gate is not verified.');
  if (review.searchDiscovery.status === 'CHANGED_ACTION_REQUIRED') warnings.push('Search and AI discovery changed and requires action before the claimed publication boundary can advance.');
  if (review.searchDiscovery.status === 'UNVERIFIED') warnings.push('Search and AI discovery impact is unverified.');
  return `<aside class="warning" aria-labelledby="warning-heading"><h2 id="warning-heading">Evidence freshness and verification warnings</h2><ul>${warnings.map((warning) => `<li>${warning}</li>`).join('')}</ul></aside>`;
}

function receiptGateOutcomes(outcomes) {
  if (outcomes.length === 0) return '<p class="muted">None recorded.</p>';
  const rows = outcomes.map((outcome) => `<tr>
    <th scope="row"><code>${escapeHtml(outcome.gateId)}</code></th>
    <td><code>${escapeHtml(outcome.status)}</code></td>
    <td><code>${escapeHtml(outcome.checkId)}</code></td>
    <td><code>${escapeHtml(outcome.checkContractDigest)}</code></td>
    <td><code>${escapeHtml(outcome.configurationDigest)}</code></td>
  </tr>`).join('');
  return `<div class="table-scroll"><table><caption>Exact gate outcome tuples</caption><thead><tr>
    <th scope="col">Gate ID</th><th scope="col">Status</th><th scope="col">Check ID</th>
    <th scope="col">Check-contract digest</th><th scope="col">Configuration digest</th>
  </tr></thead><tbody>${rows}</tbody></table></div>`;
}

function receiptScoreAttestations(attestations) {
  if (attestations.length === 0) return '<p class="muted">None recorded.</p>';
  const rows = attestations.map((attestation) => `<tr>
    <th scope="row"><code>${escapeHtml(attestation.dimensionId)}</code></th>
    <td><code>${escapeHtml(attestation.side)}</code></td><td class="number">${attestation.score.toFixed(1)}</td>
  </tr>`).join('');
  return `<div class="table-scroll"><table><caption>Score attestations</caption><thead><tr>
    <th scope="col">Dimension ID</th><th scope="col">Side</th><th scope="col">Score</th>
  </tr></thead><tbody>${rows}</tbody></table></div>`;
}

function receiptFindingApprovals(approvals) {
  if (approvals.length === 0) return '<p class="muted">None recorded.</p>';
  const rows = approvals.map((approval) => `<tr>
    <th scope="row"><code>${escapeHtml(approval.findingId)}</code></th>
    <td><code>${escapeHtml(approval.findingDigest)}</code></td><td><code>${escapeHtml(approval.kind)}</code></td>
  </tr>`).join('');
  return `<div class="table-scroll"><table><caption>Finding approvals</caption><thead><tr>
    <th scope="col">Finding ID</th><th scope="col">Finding digest</th><th scope="col">Approval kind</th>
  </tr></thead><tbody>${rows}</tbody></table></div>`;
}

function receiptObservations(observations) {
  if (observations.length === 0) return '<p class="muted">None recorded.</p>';
  const rows = observations.map((observation) => `<tr>
    <th scope="row"><code>${escapeHtml(observation.id)}</code></th><td>${escapeHtml(observation.label)}</td>
    <td>${escapeHtml(observation.value)}</td><td>${escapeHtml(observation.unit)}</td>
  </tr>`).join('');
  return `<div class="table-scroll"><table><caption>Receipt observations</caption><thead><tr>
    <th scope="col">Observation ID</th><th scope="col">Label</th><th scope="col">Value</th><th scope="col">Unit</th>
  </tr></thead><tbody>${rows}</tbody></table></div>`;
}

function receiptArtifacts(artifacts) {
  if (artifacts.length === 0) return '<p class="muted">None recorded.</p>';
  const rows = artifacts.map((artifact) => `<tr>
    <th scope="row"><code>${escapeHtml(artifact.id)}</code></th><td><code>${escapeHtml(artifact.kind)}</code></td>
    <td><code>${escapeHtml(artifact.repositoryPath)}</code></td><td><code>${escapeHtml(artifact.sha256)}</code></td>
    <td class="number">${escapeHtml(artifact.sizeBytes)}</td>
  </tr>`).join('');
  return `<div class="table-scroll"><table><caption>Bound receipt artifacts</caption><thead><tr>
    <th scope="col">Artifact ID</th><th scope="col">Kind</th><th scope="col">Repository path</th>
    <th scope="col">SHA-256</th><th scope="col">Size bytes</th>
  </tr></thead><tbody>${rows}</tbody></table></div>`;
}

function receiptClaimSummary(claims) {
  const searchDiscovery = claims.searchDiscovery === null
    ? '<span class="muted">None</span>'
    : `Status <code>${escapeHtml(claims.searchDiscovery.status)}</code>`;
  const independentReview = claims.independentReview === null
    ? '<span class="muted">None</span>'
    : `Reviewer <code>${escapeHtml(claims.independentReview.reviewerId)}</code>; pass <code>${escapeHtml(claims.independentReview.passKind)}</code>`;
  return `<dl class="compact-dl">
    <div><dt>Search and AI discovery claim</dt><dd>${searchDiscovery}</dd></div>
    <div><dt>Independent-review claim</dt><dd>${independentReview}</dd></div>
  </dl>`;
}

function receiptDrillDown(projections) {
  if (projections.length === 0) return '<p class="muted">None recorded.</p>';
  return projections.map(({ reference, receipt }) => `<details>
    <summary><code>${escapeHtml(receipt.evidenceId)}</code> — <code>${escapeHtml(receipt.status)}</code></summary>
    <div class="details-body">
      <dl class="compact-dl">
        <div><dt>Registry repository path</dt><dd><code>${escapeHtml(reference.repositoryPath)}</code></dd></div>
        <div><dt>Receipt digest</dt><dd><code>${escapeHtml(receipt.receiptId)}</code></dd></div>
        <div><dt>Receipt schema version</dt><dd>${escapeHtml(receipt.schemaVersion)}</dd></div>
        <div><dt>Evidence kind</dt><dd><code>${escapeHtml(receipt.kind)}</code></dd></div>
        <div><dt>Status</dt><dd><code>${escapeHtml(receipt.status)}</code></dd></div>
        <div><dt>Summary</dt><dd>${escapeHtml(receipt.summary)}</dd></div>
        <div><dt>Subject</dt><dd><code>${escapeHtml(receipt.subject)}</code></dd></div>
        <div><dt>Bound subject identity ID</dt><dd><code>${escapeHtml(receipt.subjectIdentityRef)}</code></dd></div>
        <div><dt>Profile digest</dt><dd><code>${escapeHtml(receipt.profileDigest)}</code></dd></div>
        <div><dt>Created at</dt><dd>${escapeHtml(receipt.createdAt)}</dd></div>
        <div><dt>Producer ID</dt><dd><code>${escapeHtml(receipt.producer.id)}</code></dd></div>
        <div><dt>Producer version</dt><dd>${escapeHtml(receipt.producer.version)}</dd></div>
        <div><dt>Producer digest</dt><dd><code>${escapeHtml(receipt.producer.digest)}</code></dd></div>
      </dl>
      <h4>Execution</h4><dl class="compact-dl">
        <div><dt>Execution kind</dt><dd><code>${escapeHtml(receipt.execution.kind)}</code></dd></div>
        <div><dt>Adapter</dt><dd><code>${escapeHtml(receipt.execution.adapter)}</code></dd></div>
        <div><dt>Entrypoint</dt><dd>${receipt.execution.entrypoint === null ? '<span class="muted">Not supplied</span>' : `<code>${escapeHtml(receipt.execution.entrypoint)}</code>`}</dd></div>
        <div><dt>Official ingress</dt><dd>${booleanHtml(receipt.execution.officialIngress)}</dd></div>
        <div><dt>Arguments in exact order</dt><dd>${list(receipt.execution.arguments, (argument) => `<code>${escapeHtml(argument)}</code>`, 'None')}</dd></div>
      </dl>
      <h4>Gate outcomes</h4>${receiptGateOutcomes(receipt.claims.gateOutcomes)}
      <h4>Score attestations</h4>${receiptScoreAttestations(receipt.claims.scoreAttestations)}
      <h4>Other claims</h4>${receiptClaimSummary(receipt.claims)}
      <h5>Finding approvals</h5>${receiptFindingApprovals(receipt.claims.findingApprovals)}
      <h4>Observations</h4>${receiptObservations(receipt.observations)}
      <h4>Artifacts</h4>${receiptArtifacts(receipt.artifacts)}
    </div>
  </details>`).join('');
}

function evidenceSection(review, receiptProjections) {
  const executionProvenance = assertEvaluatorExecutionProvenance(review.trustControl);
  const freshness = markedEnumHtml(FRESHNESS_MARKERS, review.freshness.status, 'freshness status');
  const independent = markedEnumHtml(INDEPENDENT_REVIEW_MARKERS, review.independentReview.status, 'independent review status');
  const registry = review.evidenceRefs.length === 0
    ? '<p class="muted">No bound evidence references recorded.</p>'
    : `<div class="table-scroll"><table><caption>Bound evidence registry</caption><thead><tr><th scope="col">Evidence ID</th><th scope="col">Receipt digest</th><th scope="col">Repository path</th></tr></thead><tbody>
      ${review.evidenceRefs.map((reference) => `<tr><th scope="row"><code>${escapeHtml(reference.evidenceId)}</code></th><td><code>${escapeHtml(reference.receiptDigest)}</code></td><td><code>${escapeHtml(reference.repositoryPath)}</code></td></tr>`).join('')}
      </tbody></table></div>`;
  return `<div class="grid">
    <article class="card"><h3>Evidence manifest</h3><code>${escapeHtml(review.evidenceManifestDigest)}</code><p>Measured observations and reviewer judgments are identified in the bound receipts; this renderer does not infer evidence kinds from IDs.</p></article>
    <article class="card"><h3>Trust control</h3><dl class="compact-dl">
      <div><dt>Producer registry authority</dt><dd>${enumHtml(review.trustControl.producerRegistryAuthority, ENUM_LABELS.producerRegistryAuthority, 'producer registry authority')}</dd></div>
      <div><dt>Producer registry digest</dt><dd><code>${escapeHtml(review.trustControl.producerRegistryDigest)}</code></dd></div>
      <div><dt>Review tool source snapshot digest</dt><dd><code>${escapeHtml(review.trustControl.toolSourceDigest)}</code></dd></div>
      <div><dt>Review tool digest</dt><dd><code>${escapeHtml(review.trustControl.toolDigest)}</code></dd></div>
      <div><dt>Executed evaluator source status</dt><dd class="${statusClass(executionProvenance.status)}">${enumHtml(executionProvenance.status, ENUM_LABELS.evaluatorExecutionProvenanceStatus, 'evaluator execution provenance status')}</dd></div>
      <div><dt>Evaluator source capture method</dt><dd>${enumHtml(executionProvenance.method, ENUM_LABELS.evaluatorExecutionProvenanceMethod, 'evaluator execution provenance method')}</dd></div>
      <div><dt>Executed evaluator source digest</dt><dd>${escapeHtml(executionProvenance.executedSourceDigest)}</dd></div>
      <div><dt>Controller attestation reference</dt><dd>${escapeHtml(executionProvenance.controllerAttestationRef)}</dd></div>
      <div><dt>Evaluator execution provenance statement</dt><dd>${escapeHtml(executionProvenance.statement)}</dd></div>
    </dl></article>
    <article class="card"><h3>Freshness</h3><dl class="compact-dl">
      <div><dt>Status</dt><dd class="${statusClass(review.freshness.status)}">${freshness}</dd></div>
      <div><dt>Evaluated at</dt><dd>${escapeHtml(review.freshness.evaluatedAt)}</dd></div>
      <div><dt>Oldest bound evidence at</dt><dd>${escapeHtml(review.freshness.oldestEvidenceAt)}</dd></div>
      <div><dt>Maximum age</dt><dd>${escapeHtml(review.freshness.maxAgeSeconds)} seconds</dd></div>
      <div><dt>Candidate evidence subject match</dt><dd>${markedEnumHtml(IDENTITY_MATCH_MARKERS, review.freshness.candidateIdentityMatch, 'identity match status')}</dd></div>
      <div><dt>Producer authorization match</dt><dd>${markedEnumHtml(IDENTITY_MATCH_MARKERS, review.freshness.producerAuthorizationMatch, 'identity match status')}</dd></div>
      <div><dt>Tool source snapshot match</dt><dd>${markedEnumHtml(IDENTITY_MATCH_MARKERS, review.freshness.toolSourceSnapshotMatch, 'identity match status')}</dd></div>
      <div><dt>Profile identity match</dt><dd>${markedEnumHtml(IDENTITY_MATCH_MARKERS, review.freshness.profileIdentityMatch, 'identity match status')}</dd></div>
    </dl><p class="muted">The candidate identity object is validated separately. “Candidate evidence subject match” only states whether bound receipts name that validated identity.</p></article>
    <article class="card"><h3>Independent review</h3><dl class="compact-dl">
      <div><dt>Status</dt><dd>${independent}</dd></div>
      <div><dt>Reviewer IDs</dt><dd>${list(review.independentReview.reviewerIds, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</dd></div>
      <div><dt>Pass kinds</dt><dd>${list(review.independentReview.passKinds, (kind) => enumHtml(kind, ENUM_LABELS.passKind, 'independent review pass kind'), 'None declared')}</dd></div>
      <div><dt>Evidence</dt><dd>${list(review.independentReview.evidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</dd></div>
    </dl></article>
    <article class="card"><h3>Documentation impact</h3><dl class="compact-dl">
      <div><dt>Authority</dt><dd>${enumHtml(review.docsImpact.authority, ENUM_LABELS.docsImpactAuthority, 'documentation-impact authority')}</dd></div>
      <div><dt>Analysis evidence</dt><dd>${escapeHtml(review.docsImpact.analysisEvidenceRef)}</dd></div>
      <div><dt>Statement</dt><dd>${escapeHtml(review.docsImpact.statement)}</dd></div>
    </dl></article>
  </div>${registry}<h3>Receipt drill-down</h3><p>Receipt files are the canonical evidence source. The fields below are deterministic projections of the exact validated receipts bound by the registry above.</p>${receiptDrillDown(receiptProjections)}`;
}

function publicationBoundary(boundary) {
  return `<p>${escapeHtml(boundary.statement)}</p><dl>
    <div><dt>Maximum verified scope</dt><dd>${enumHtml(boundary.maximumVerifiedScope, ENUM_LABELS.verdictScope, 'verdict scope')}</dd></div>
    <div><dt>Merge authority</dt><dd>${enumHtml(boundary.mergeAuthority, ENUM_LABELS.externalAuthority, 'external authority status')}</dd></div>
    <div><dt>Publication authority</dt><dd>${enumHtml(boundary.publicationAuthority, ENUM_LABELS.externalAuthority, 'external authority status')}</dd></div>
    <div><dt>Deployment authority</dt><dd>${enumHtml(boundary.deploymentAuthority, ENUM_LABELS.externalAuthority, 'external authority status')}</dd></div>
    <div><dt>Authority evidence</dt><dd>${list(boundary.authorityEvidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</dd></div>
  </dl>`;
}

function searchDiscoverySection(searchDiscovery) {
  const status = markedEnumHtml(
    SEARCH_DISCOVERY_MARKERS,
    searchDiscovery.status,
    'search and AI discovery status',
  );
  return `<article class="card">
    <p><strong>Status:</strong> <span class="${statusClass(searchDiscovery.status)}">${status}</span></p>
    <p><strong>Statement:</strong> ${escapeHtml(searchDiscovery.statement)}</p>
    <div><strong>Evidence:</strong> ${list(searchDiscovery.evidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</div>
    <div><strong>Actions:</strong> ${list(searchDiscovery.actions, escapeHtml, 'None recorded')}</div>
    <p class="muted"><strong>External-outcome boundary:</strong> Local evidence cannot prove indexing, ranking, citation, recommendation, traffic, or model inclusion.</p>
  </article>`;
}

function contentsNavigation() {
  const entries = [
    ['legend', 'Marker legend'], ['comparison', 'Comparison'], ['evidence', 'Evidence'],
    ['search-discovery', 'Search and AI discovery impact'], ['readiness', 'Readiness'],
    ['overall', 'Overall'], ['regressions', 'Regressions'], ['gaps', 'Remaining gaps'],
    ['verdict', 'Verdict'], ['confidence', 'Confidence'], ['boundary', 'Publication boundary'],
  ];
  return `<nav class="contents" aria-label="Review report contents"><h2>Contents</h2><ol>${entries.map(([id, label]) => `<li><a href="#${id}">${label}</a></li>`).join('')}</ol></nav>`;
}

function confidenceBlock(confidence) {
  if (typeof confidence.value !== 'number' || !Number.isFinite(confidence.value)) {
    throw new TypeError('Confidence value must be finite');
  }
  const label = enumHtml(confidence.label, CONFIDENCE_LABELS, 'confidence label');
  return `<p><strong>${label} (${confidence.value.toFixed(2)}).</strong></p>
    <div>Basis evidence: ${list(confidence.basisEvidenceRefs, (item) => `<code>${escapeHtml(item)}</code>`, 'None declared')}</div>
    <div>Limitations: ${list(confidence.limitations, escapeHtml, 'None recorded')}</div>`;
}

export function renderHtml(review, evidenceReceipts) {
  assertScoreQuarantine(review);
  const receiptProjections = prepareEvidenceReceipts(review, evidenceReceipts);
  const hasBaseline = review.identityRefs.baselineIdentityId !== null;
  const comparison = markedEnumHtml(COMPARISON_MARKERS, review.comparisonStatus, 'comparison status');
  const readiness = markedEnumHtml(READINESS_MARKERS, review.readinessVerdict, 'readiness verdict');
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Completed Work Review — ${escapeHtml(review.reviewId)}</title><style>
:root{color-scheme:light dark;--bg:#f5f7fb;--panel:#fff;--text:#172033;--muted:#536079;--line:#c8d0df;--good:#12663a;--bad:#a51d28;--warn:#845400;--neutral:#5d3a91;--accent:#2e5bd1;--focus:#d26000;--quarantine:#fff7dc}@media(prefers-color-scheme:dark){:root{--bg:#101522;--panel:#1a2131;--text:#eef3ff;--muted:#b7c0d4;--line:#3d4961;--good:#76d69d;--bad:#ff8d96;--warn:#ffd27a;--neutral:#caa8ff;--accent:#9ab5ff;--focus:#ffd27a;--quarantine:#332915}}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}main{width:min(1180px,calc(100% - 2rem));margin:auto;padding:2rem 0 4rem}h1,h2,h3{line-height:1.2}h2{margin-top:2rem}a{color:var(--accent)}a:focus-visible,summary:focus-visible{outline:.2rem solid var(--focus);outline-offset:.2rem}code,pre{font-family:ui-monospace,"Cascadia Code",monospace;overflow-wrap:anywhere}.skip-link{position:absolute;left:.5rem;top:.5rem;transform:translateY(-200%);background:var(--panel);border:.15rem solid var(--focus);padding:.6rem;z-index:2}.skip-link:focus{transform:none}.hero,.card,.warning,.decision,.contents,.quarantine,details{background:var(--panel);border:1px solid var(--line);border-radius:.75rem}.hero{padding:1.5rem;border-top:.35rem solid var(--accent)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:1rem}.card{padding:1rem;min-width:0}.warning,.decision,.contents,.quarantine{margin-top:1.25rem;padding:1rem 1.25rem}.warning{border-left:.4rem solid var(--warn)}.quarantine{background:var(--quarantine);border:.15rem dashed var(--warn)}.quarantine table,.quarantined-row{background:var(--quarantine)}.contents ol{columns:2;column-gap:2rem}.table-scroll{overflow-x:auto}table{width:100%;border-collapse:collapse;background:var(--panel)}caption{text-align:left;font-weight:700;padding:.75rem 0}th,td{border:1px solid var(--line);padding:.7rem;text-align:left;vertical-align:top}.number{text-align:right;font-variant-numeric:tabular-nums}.overall-row{font-weight:700}.status-good{color:var(--good);font-weight:700}.status-bad{color:var(--bad);font-weight:700}.status-warning{color:var(--warn);font-weight:700}.status-neutral{color:var(--neutral);font-weight:700}.muted,.meta{color:var(--muted)}.meta{display:block}ul.compact{margin:.25rem 0;padding-left:1.15rem}.compact-dl div{display:block;border:0;padding:.15rem 0}.compact-dl dt,.compact-dl dd{display:inline}.compact-dl dt::after{content:": "}details{margin:.75rem 0}summary{cursor:pointer;font-weight:700;padding:.85rem 1rem}.details-body{padding:0 1rem 1rem}dl div{display:grid;grid-template-columns:minmax(9rem,1fr) 3fr;gap:1rem;padding:.35rem 0;border-bottom:1px solid var(--line)}dt{font-weight:700}dd{margin:0}.findings{padding-left:1.25rem}.findings>li{margin:1rem 0;padding:.75rem;background:var(--panel);border:1px solid var(--line);border-radius:.6rem}.decision{border-left:.4rem solid var(--accent)}@media(max-width:600px){main{width:calc(100% - 1rem)}.contents ol{columns:1}dl div{grid-template-columns:1fr;gap:.15rem}th,td{padding:.5rem}}
</style></head><body><a class="skip-link" href="#main-content">Skip to review content</a><main id="main-content" tabindex="-1">
<header class="hero"><h1>Completed Work Review</h1><p><strong>Overall result:</strong> <span class="${statusClass(review.readinessVerdict)}">${readiness}</span></p><p><strong>Comparison:</strong> <span class="${statusClass(review.comparisonStatus)}">${comparison}</span></p><p><strong>Scope:</strong> ${enumHtml(review.verdictScope, ENUM_LABELS.verdictScope, 'verdict scope')}</p><dl class="compact-dl"><div><dt>Schema version</dt><dd>${escapeHtml(review.schemaVersion)}</dd></div><div><dt>Review ID</dt><dd><code>${escapeHtml(review.reviewId)}</code></dd></div><div><dt>Generated at</dt><dd>${escapeHtml(review.generatedAt)}</dd></div></dl></header>
${warningPanel(review)}
<section aria-labelledby="identities"><h2 id="identities">Bound identities</h2><div class="grid">${identityCards(review.identityRefs)}</div></section>
<section aria-labelledby="summary"><h2 id="summary">Review summary</h2><div class="grid"><article class="card"><h3>Readiness</h3><strong class="${statusClass(review.readinessVerdict)}">${readiness}</strong></article><article class="card"><h3>Comparison</h3><strong class="${statusClass(review.comparisonStatus)}">${comparison}</strong></article><article class="card"><h3>Profile</h3><p>${enumHtml(review.profileId, ENUM_LABELS.profile, 'profile')}</p><p><strong>Scoring method:</strong> ${enumHtml(review.scoringMethod, ENUM_LABELS.scoringMethod, 'scoring method')}</p><code>${escapeHtml(review.profileDigest)}</code></article><article class="card"><h3>Confidence</h3><h4>Canonical evidence confidence</h4>${confidenceBlock(review.confidence)}<div class="quarantine"><h4>Submitted confidence — audit only</h4><p class="muted">This request assertion does not override canonical evidence confidence.</p>${confidenceBlock(review.submittedConfidence)}</div></article></div></section>
${contentsNavigation()}
<section aria-labelledby="legend"><h2 id="legend">Marker legend</h2><ul><li>${COMPARISON_MARKERS.IMPROVED} <code>IMPROVED</code></li><li>${COMPARISON_MARKERS.DECREASED} <code>DECREASED</code></li><li>${COMPARISON_MARKERS.UNCHANGED} <code>UNCHANGED</code></li><li>${COMPARISON_MARKERS.UNVERIFIED} <code>UNVERIFIED</code></li></ul></section>
<section aria-labelledby="comparison"><h2 id="comparison">Comparison</h2><p>${hasBaseline ? 'Canonical baseline and candidate scores use the same bound profile and trusted evidence contract. Unverified score evidence remains N/V.' : 'The change baseline was explicitly not supplied. This is a current-only review; no baseline score or delta is inferred.'}</p>${comparisonTable(review, hasBaseline)}${submittedScoresTable(review, hasBaseline)}</section>
<section aria-labelledby="evidence"><h2 id="evidence">Evidence</h2>${evidenceSection(review, receiptProjections)}</section>
<section aria-labelledby="search-discovery"><h2 id="search-discovery">Search and AI discovery impact</h2>${searchDiscoverySection(review.searchDiscovery)}</section>
<section aria-labelledby="readiness"><h2 id="readiness">Readiness</h2>${readinessTable(review)}<h3>Blocker drill-down</h3><p class="muted">Blocker details start collapsed. Activate a summary to inspect its evidence and exact unlock requirements.</p>${blockerDrilldown(review.blockers)}</section>
<section aria-labelledby="overall"><h2 id="overall">Overall</h2>${overallTable(review, hasBaseline)}</section>
<section aria-labelledby="regressions"><h2 id="regressions">Regressions</h2>${regressionList(review.regressions)}</section>
<section aria-labelledby="gaps"><h2 id="gaps">Remaining gaps</h2>${gapList(review.remainingGaps)}</section>
<section aria-labelledby="verdict"><h2 id="verdict">Verdict</h2><p><strong class="${statusClass(review.readinessVerdict)}">${readiness}.</strong> This is a ${enumHtml(review.verdictScope, ENUM_LABELS.verdictScope, 'verdict scope')} readiness verdict. The separate comparison status is ${comparison}.</p></section>
<section aria-labelledby="confidence"><h2 id="confidence">Confidence</h2><h3>Canonical evidence confidence</h3>${confidenceBlock(review.confidence)}<aside class="quarantine" aria-labelledby="submitted-confidence-heading"><h3 id="submitted-confidence-heading">Submitted confidence — audit only</h3><p><strong>This is the request-submitted confidence assertion.</strong> It is not the canonical evidence confidence and does not override it.</p>${confidenceBlock(review.submittedConfidence)}</aside></section>
<section aria-labelledby="boundary"><h2 id="boundary">Publication boundary</h2>${publicationBoundary(review.publicationBoundary)}<aside class="decision" aria-labelledby="native-decision-guidance"><h3 id="native-decision-guidance">Native pull-request decision guidance</h3><p>Use the repository host’s native review controls to approve, request changes, or defer. Re-check the bound review ID, candidate identity, evidence manifest, freshness, blockers, and publication boundary before recording that decision.</p><p><strong>This dashboard records no decision and grants no repository authority.</strong> It does not authorize a commit, merge, publication, deployment, or production mutation.</p></aside></section>
</main></body></html>\n`;
}
