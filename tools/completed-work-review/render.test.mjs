import assert from 'node:assert/strict';
import test from 'node:test';

import { CONTRACT_VALUES } from './contracts/constants.mjs';
import { renderHtml } from './render-html.mjs';
import { renderMarkdown } from './render-markdown.mjs';

const SHA_A = 'a'.repeat(64);
const SHA_B = 'b'.repeat(64);
const SHA_C = 'c'.repeat(64);
const SHA_D = 'd'.repeat(64);
const SHA_E = 'e'.repeat(64);
const SHA_F = 'f'.repeat(64);

function fixture() {
  return {
    schemaVersion: 1,
    reviewId: SHA_A,
    profileId: 'POCKETHIVE_DOCUMENTATION_V1',
    profileDigest: SHA_B,
    scoringMethod: 'ANCHORED_RUBRIC_V1',
    evidenceManifestDigest: SHA_C,
    trustControl: {
      producerRegistryAuthority: 'CANDIDATE_UNVERIFIED',
      producerRegistryDigest: SHA_A,
      toolSourceDigest: SHA_C,
      toolDigest: SHA_B,
      evaluatorExecutionProvenance: {
        status: 'NOT_VERIFIED',
        method: 'POST_LOAD_FILESYSTEM_SNAPSHOT',
        executedSourceDigest: null,
        controllerAttestationRef: null,
        statement: CONTRACT_VALUES.evaluatorExecutionProvenanceStatement,
      },
    },
    verdictScope: 'LOCAL_CANDIDATE',
    identityRefs: {
      baselineIdentityId: SHA_A,
      candidateIdentityId: SHA_B,
      candidateVerificationTarget: 'LIVE_WORKTREE',
      approvedReferenceEvidenceRefs: ['approved-reference'],
      deploymentIdentityId: null,
    },
    docsImpact: {
      authority: 'INFORMATIONAL_UNVERIFIED',
      analysisEvidenceRef: 'docs-impact-analysis',
      statement: 'Analysis is informational until protected-controller evidence is supplied.',
    },
    searchDiscovery: {
      status: 'NO_MATERIAL_CHANGE',
      statement: 'No public route or machine-readable discovery surface changed.',
      evidenceRefs: ['validation-receipt'],
      actions: [],
    },
    comparisonStatus: 'IMPROVED',
    readinessVerdict: 'NOT_READY',
    publicationBoundary: {
      maximumVerifiedScope: 'LOCAL_CANDIDATE',
      mergeAuthority: 'NOT_GRANTED',
      publicationAuthority: 'NOT_GRANTED',
      deploymentAuthority: 'NOT_GRANTED',
      authorityEvidenceRefs: ['authority-boundary'],
      statement: 'Human and platform authority remain external.',
    },
    confidence: {
      label: 'HIGH',
      value: 0.91,
      basisEvidenceRefs: ['validation-receipt', 'independent-review'],
      limitations: ['Publication has not occurred.'],
    },
    submittedConfidence: {
      label: 'MEDIUM',
      value: 0.72,
      basisEvidenceRefs: ['validation-receipt'],
      limitations: ['Submitted before canonical evidence reconciliation.'],
    },
    freshness: {
      status: 'FRESH',
      evaluatedAt: '2026-08-17T12:00:00Z',
      oldestEvidenceAt: '2026-08-17T11:55:00Z',
      maxAgeSeconds: 3600,
      candidateIdentityMatch: 'MATCH',
      producerAuthorizationMatch: 'MATCH',
      toolSourceSnapshotMatch: 'MATCH',
      profileIdentityMatch: 'MATCH',
    },
    independentReview: {
      status: 'VERIFIED',
      reviewerIds: ['reviewer-one'],
      passKinds: ['NOVICE', 'EXPERT'],
      evidenceRefs: ['independent-review'],
    },
    dimensions: [
      {
        id: 'orientation', label: 'Orientation', kind: 'SCORE', direction: 'HIGHER_IS_BETTER', required: true, weight: 50,
        criterion: 'Readers can identify the correct start and next safe step.',
        scoreAnchors: [
          { score: 0, description: 'No usable orientation path exists.' },
          { score: 5, description: 'A partial path exists but important choices remain unclear.' },
          { score: 10, description: 'Every supported reader has a complete and unambiguous path.' },
        ],
        scoreStatus: 'VERIFIED', submittedBaseline: 6.2, submittedCurrent: 8.8, submittedDelta: 2.6, submittedComparisonStatus: 'IMPROVED',
        baseline: 6.2, current: 8.8, delta: 2.6, comparisonStatus: 'IMPROVED', evidenceRefs: ['orientation-evidence'], notes: 'Measured navigation plus reviewer judgment.',
      },
      {
        id: 'correctness', label: 'Correctness', kind: 'SCORE', direction: 'HIGHER_IS_BETTER', required: true, weight: 50,
        criterion: 'Claims and commands match the exact reviewed implementation.',
        scoreAnchors: [
          { score: 0, description: 'Material claims or commands are false.' },
          { score: 5, description: 'Core claims are accurate but verification has material gaps.' },
          { score: 10, description: 'All material claims and commands are exact and verified.' },
        ],
        scoreStatus: 'VERIFIED', submittedBaseline: 8.7, submittedCurrent: 8.7, submittedDelta: 0, submittedComparisonStatus: 'UNCHANGED',
        baseline: 8.7, current: 8.7, delta: 0, comparisonStatus: 'UNCHANGED', evidenceRefs: ['validation-receipt'], notes: 'Required validation passed.',
      },
    ],
    overall: { baseline: 7.5, current: 8.8, delta: 1.3, comparisonStatus: 'IMPROVED' },
    submittedOverall: { baseline: 7.5, current: 8.8, delta: 1.3, comparisonStatus: 'IMPROVED' },
    gates: [
      { id: 'required-validation', required: true, status: 'VERIFIED', summary: 'Required validation passed.', evidenceRefs: ['validation-receipt'], blockerRefs: [] },
      { id: 'human-approval', required: false, status: 'FAILED', summary: 'Human approval remains external.', evidenceRefs: ['authority-boundary'], blockerRefs: ['approval-required'] },
    ],
    blockers: [
      {
        id: 'approval-required', severity: 'MATERIAL', status: 'OPEN', summary: 'Human approval remains external.', evidenceRefs: ['authority-boundary'],
        unlock: { kind: 'HUMAN_APPROVAL', description: 'Obtain explicit publication approval.', requiredEvidenceKinds: ['MEASURED'] },
      },
    ],
    regressions: [
      { id: 'page-length', dimensionId: 'orientation', severity: 'NON_MATERIAL', summary: 'Quickstart page is longer.', evidenceRefs: ['line-count'], disposition: 'ACCEPTED_TRADE_OFF' },
    ],
    remainingGaps: [
      {
        id: 'publish-check', dimensionId: null, severity: 'MATERIAL', summary: 'Publication has not occurred.', evidenceRefs: ['authority-boundary'],
        unlock: { kind: 'EXTERNAL_EVIDENCE', description: 'Supply production publication evidence.', requiredEvidenceKinds: ['MEASURED'] },
      },
    ],
    evidenceRefs: [
      { evidenceId: 'validation-receipt', receiptDigest: SHA_A, repositoryPath: '.test-results/validation.json' },
      { evidenceId: 'independent-review', receiptDigest: SHA_B, repositoryPath: '.test-results/review.json' },
    ],
    generatedAt: '2026-08-17T12:00:00Z',
  };
}

function evidenceReceiptsFixture() {
  return [
    {
      schemaVersion: 1,
      receiptId: SHA_A,
      evidenceId: 'validation-receipt',
      kind: 'MEASURED',
      subject: 'CANDIDATE',
      subjectIdentityRef: SHA_B,
      profileDigest: SHA_B,
      producer: {
        id: 'docs-validation-producer',
        version: '2.3.1',
        digest: SHA_D,
      },
      execution: {
        kind: 'AUTOMATED_CHECK',
        adapter: 'DOCS_VALIDATION',
        entrypoint: 'node',
        arguments: ['tools/docs-validation/run.mjs', '--profile', 'static'],
        officialIngress: true,
      },
      status: 'PASS',
      summary: 'Static documentation validation completed successfully.',
      claims: {
        gateOutcomes: [
          {
            gateId: 'documentation-validation',
            status: 'PASS',
            checkId: 'docs-static-check',
            checkContractDigest: SHA_E,
            configurationDigest: SHA_F,
          },
          {
            gateId: 'search-ai-discovery',
            status: 'PASS',
            checkId: 'search-discovery-check',
            checkContractDigest: SHA_D,
            configurationDigest: SHA_C,
          },
        ],
        scoreAttestations: [
          { dimensionId: 'correctness', side: 'CURRENT', score: 8.7 },
        ],
        searchDiscovery: { status: 'NO_MATERIAL_CHANGE' },
        independentReview: null,
        findingApprovals: [],
      },
      artifacts: [
        {
          id: 'validation-report',
          kind: 'JSON',
          repositoryPath: '.test-results/docs-validation/static.json',
          sha256: SHA_C,
          sizeBytes: 3456,
        },
      ],
      observations: [
        { id: 'route-count', label: 'Rendered routes', value: 47, unit: 'routes' },
        { id: 'warning-state', label: 'Warning state', value: false, unit: null },
      ],
      createdAt: '2026-08-17T11:55:00.000Z',
    },
    {
      schemaVersion: 1,
      receiptId: SHA_B,
      evidenceId: 'independent-review',
      kind: 'REVIEWER_JUDGMENT',
      subject: 'REVIEW',
      subjectIdentityRef: SHA_B,
      profileDigest: SHA_B,
      producer: {
        id: 'independent-review-producer',
        version: '1.4.0',
        digest: SHA_E,
      },
      execution: {
        kind: 'INDEPENDENT_REVIEW',
        adapter: 'INDEPENDENT_REVIEW',
        entrypoint: 'independent-review-agent',
        arguments: ['--pass', 'NOVICE', '--candidate', SHA_B],
        officialIngress: false,
      },
      status: 'PASS',
      summary: 'Independent novice review completed against the bound candidate.',
      claims: {
        gateOutcomes: [
          {
            gateId: 'independent-review',
            status: 'PASS',
            checkId: 'novice-review-check',
            checkContractDigest: SHA_F,
            configurationDigest: SHA_E,
          },
        ],
        scoreAttestations: [
          { dimensionId: 'orientation', side: 'CURRENT', score: 8.8 },
        ],
        searchDiscovery: null,
        independentReview: { reviewerId: 'reviewer-one', passKind: 'NOVICE' },
        findingApprovals: [
          { findingId: 'page-length', findingDigest: SHA_D, kind: 'ACCEPTED_TRADE_OFF' },
        ],
      },
      artifacts: [
        {
          id: 'review-notes',
          kind: 'MARKDOWN',
          repositoryPath: '.test-results/completed-work-review/review-notes.md',
          sha256: SHA_F,
          sizeBytes: 789,
        },
      ],
      observations: [
        { id: 'review-conclusion', label: 'Review conclusion', value: 'Clear with one trade-off', unit: null },
      ],
      createdAt: '2026-08-17T11:58:00.000Z',
    },
  ];
}

const CLOSED_ENUMS = new Set([
  ...CONTRACT_VALUES.profileId,
  ...CONTRACT_VALUES.scoringMethod,
  ...CONTRACT_VALUES.verdictScope,
  ...CONTRACT_VALUES.candidateVerificationTarget,
  ...CONTRACT_VALUES.producerRegistryAuthority,
  ...CONTRACT_VALUES.evaluatorExecutionProvenanceStatus,
  ...CONTRACT_VALUES.evaluatorExecutionProvenanceMethod,
  CONTRACT_VALUES.evaluatorExecutionProvenanceStatement,
  ...CONTRACT_VALUES.docsImpactAuthority,
  ...CONTRACT_VALUES.discoveryStatus,
  ...CONTRACT_VALUES.comparisonStatus,
  ...CONTRACT_VALUES.readinessVerdict,
  ...CONTRACT_VALUES.externalAuthorityStatus,
  ...CONTRACT_VALUES.confidenceLabel,
  ...CONTRACT_VALUES.freshnessStatus,
  ...CONTRACT_VALUES.identityMatchStatus,
  ...CONTRACT_VALUES.independentReviewStatus,
  ...CONTRACT_VALUES.independentReviewPass,
  ...CONTRACT_VALUES.dimensionKind,
  ...CONTRACT_VALUES.scoreDirection,
  ...CONTRACT_VALUES.scoreVerificationStatus,
  ...CONTRACT_VALUES.gateStatus,
  ...CONTRACT_VALUES.blockerSeverity,
  ...CONTRACT_VALUES.blockerStatus,
  ...CONTRACT_VALUES.unlockKind,
  ...CONTRACT_VALUES.evidenceKind,
  ...CONTRACT_VALUES.findingDisposition,
  ...CONTRACT_VALUES.evidenceSubject,
  ...CONTRACT_VALUES.executionKind,
  ...CONTRACT_VALUES.evidenceAdapter,
  ...CONTRACT_VALUES.evidenceStatus,
  ...CONTRACT_VALUES.gateId,
  ...CONTRACT_VALUES.scoreSide,
  ...CONTRACT_VALUES.findingApprovalKind,
  ...CONTRACT_VALUES.artifactKind,
]);

function sentinelFixture() {
  let sequence = 0;
  const sentinels = [];
  const enumValues = new Set();
  const stringSentinels = new Map();

  function visit(value, path) {
    if (Array.isArray(value)) {
      return value.map((item, index) => visit(item, `${path}-${index}`));
    }
    if (value !== null && typeof value === 'object') {
      return Object.fromEntries(
        Object.entries(value).map(([key, item]) => [key, visit(item, `${path}-${key}`)]),
      );
    }
    if (typeof value === 'string' && CLOSED_ENUMS.has(value)) {
      enumValues.add(value);
      return value;
    }
    if (typeof value === 'string') {
      if (stringSentinels.has(value)) return stringSentinels.get(value);
      sequence += 1;
      const sentinel = `sentinel-${sequence}-${path}`.replaceAll(/[^a-z0-9-]/giu, '-');
      stringSentinels.set(value, sentinel);
      sentinels.push(sentinel);
      return sentinel;
    }
    return value;
  }

  return {
    review: visit(fixture(), 'review'),
    receipts: visit(evidenceReceiptsFixture(), 'receipts'),
    sentinels,
    enumValues,
  };
}

test('renders deterministic Markdown in the required order with canonical UTF-8 markers', () => {
  const review = fixture();
  const receipts = evidenceReceiptsFixture();
  const first = renderMarkdown(review, receipts);
  const html = renderHtml(review, receipts);
  assert.equal(first, renderMarkdown(review, receipts));
  assert.equal(html, renderHtml(review, receipts));

  const headings = ['## Marker legend', '## Comparison', '## Evidence', '## Search and AI discovery impact', '## Readiness', '## Overall', '## Regressions', '## Remaining gaps', '## Verdict', '## Confidence', '## Publication boundary'];
  for (let index = 1; index < headings.length; index += 1) {
    assert.ok(first.indexOf(headings[index - 1]) < first.indexOf(headings[index]));
  }
  const htmlSections = ['id="legend"', 'id="comparison"', 'id="evidence"', 'id="search-discovery"', 'id="readiness"', 'id="overall"', 'id="regressions"', 'id="gaps"', 'id="verdict"', 'id="confidence"', 'id="boundary"'];
  for (let index = 1; index < htmlSections.length; index += 1) {
    assert.ok(html.indexOf(htmlSections[index - 1]) < html.indexOf(htmlSections[index]));
  }
  assert.match(first, /🟢 ▲ Improved \(\+2\.6\)/u);
  assert.match(first, /🟣 = Unchanged \(\+0\.0\)/u);
  assert.match(first, /🔴 Not ready/u);
  assert.match(first, /🟣 No material change/u);
  assert.match(first, /Oldest bound evidence at/u);
  assert.match(first, /Measured evidence \(`MEASURED`\)/u);
  assert.match(first, /Measured navigation plus reviewer judgment/u);
  assert.match(first, /Anchored rubric v1 \(`ANCHORED&#95;RUBRIC&#95;V1`\)/u);
  assert.match(first, /Readers can identify the correct start and next safe step/u);
  assert.match(first, /Score anchors: 0 — No usable orientation path exists\.<br>5 — A partial path exists but important choices remain unclear\.<br>10 — Every supported reader has a complete and unambiguous path\./u);
  assert.match(first, /Receipt drill-down/u);
  assert.match(first, /docs-validation-producer/u);
  assert.match(first, /2\.3\.1/u);
  assert.match(first, new RegExp(SHA_D, 'u'));
  assert.match(first, /AUTOMATED&#95;CHECK/u);
  assert.match(first, /DOCS&#95;VALIDATION/u);
  assert.match(first, /tools\/docs-validation\/run\.mjs/u);
  assert.match(first, /Exact gate outcomes/u);
  assert.match(first, /docs-static-check/u);
  assert.match(first, /Rendered routes/u);
  assert.match(first, /validation-report/u);
  assert.match(first, /Canonical evidence confidence/u);
  assert.ok(first.indexOf('Canonical evidence confidence') < first.indexOf('Submitted confidence'));
  assert.match(html, /Receipt drill-down/u);
  assert.match(html, /docs-validation-producer/u);
  assert.match(html, /Exact gate outcome tuples/u);
  assert.match(html, /Anchored rubric v1 <code>ANCHORED_RUBRIC_V1<\/code>/u);
  assert.match(html, /<dt>Criterion<\/dt><dd>Readers can identify the correct start and next safe step\.<\/dd>/u);
  assert.match(html, /<strong>0<\/strong> — No usable orientation path exists\./u);
  assert.match(html, /<strong>5<\/strong> — A partial path exists but important choices remain unclear\./u);
  assert.match(html, /<strong>10<\/strong> — Every supported reader has a complete and unambiguous path\./u);
  assert.ok(html.indexOf('Canonical evidence confidence') < html.indexOf('Submitted confidence'));
  assert.doesNotMatch(first, /�|ðŸ/u);
});

test('projects every bound evidence receipt fact with Markdown and HTML parity', () => {
  const review = fixture();
  const receipts = evidenceReceiptsFixture();
  const projections = [
    ['Markdown', renderMarkdown(review, receipts).replaceAll('&#95;', '_')],
    ['HTML', renderHtml(review, receipts)],
  ];

  const requiredFacts = [
    'validation-receipt',
    'docs-validation-producer',
    '2.3.1',
    SHA_D,
    'CANDIDATE',
    SHA_B,
    '2026-08-17T11:55:00.000Z',
    'AUTOMATED_CHECK',
    'DOCS_VALIDATION',
    'node',
    'tools/docs-validation/run.mjs',
    '--profile',
    'documentation-validation',
    'PASS',
    'docs-static-check',
    SHA_E,
    SHA_F,
    'Rendered routes',
    '47',
    'routes',
    'validation-report',
    '.test-results/docs-validation/static.json',
    '3456',
  ];
  for (const [name, projection] of projections) {
    for (const fact of requiredFacts) {
      assert.ok(projection.includes(fact), `${name} omitted receipt fact ${fact}`);
    }
    assert.match(projection, /Created at/u);
    assert.match(projection, /Bound subject identity ID/u);
    assert.match(projection, /Arguments in exact order/u);
    assert.match(projection, /Check-contract digest/u);
    assert.match(projection, /Configuration digest/u);
    assert.match(projection, /Observations/u);
    assert.match(projection, /Artifacts/u);
  }

  assert.doesNotMatch(projections[1][1], /<script\b|<button\b|<details\s+open/iu);
});

test('renders a missing baseline as current-only and warns about stale and unverified evidence', () => {
  const review = fixture();
  review.identityRefs.baselineIdentityId = null;
  review.comparisonStatus = 'UNVERIFIED';
  review.freshness.status = 'STALE';
  review.searchDiscovery.status = 'UNVERIFIED';
  review.searchDiscovery.statement = 'Search and AI discovery evidence is incomplete.';
  review.dimensions = review.dimensions.map((dimension) => ({
    ...dimension,
    submittedBaseline: null,
    submittedDelta: null,
    submittedComparisonStatus: 'UNVERIFIED',
    baseline: null,
    delta: null,
    comparisonStatus: 'UNVERIFIED',
  }));
  review.overall = { baseline: null, current: 8.8, delta: null, comparisonStatus: 'UNVERIFIED' };
  review.submittedOverall = { baseline: null, current: 8.8, delta: null, comparisonStatus: 'UNVERIFIED' };

  const receipts = evidenceReceiptsFixture();
  const markdown = renderMarkdown(review, receipts);
  const html = renderHtml(review, receipts);
  assert.match(markdown, /current-only review/u);
  assert.match(markdown, /\| Dimension \| Contract \| Candidate \/ Current \| Status \| Evidence and notes \|/u);
  assert.doesNotMatch(markdown, /\| Dimension \| Contract \| Baseline \/ Production \| Candidate/u);
  assert.match(markdown, /🟠 \? Unverified \(N\/V - Unverified\)/u);
  assert.match(html, /The bound evidence is stale/u);
  assert.match(html, /The comparison is unverified/u);
  assert.match(html, /Search and AI discovery impact is unverified/u);
});

test('quarantines submitted scores until trusted score evidence verifies them', () => {
  const review = fixture();
  review.comparisonStatus = 'UNVERIFIED';
  review.dimensions = review.dimensions.map((dimension, index) => ({
    ...dimension,
    scoreStatus: 'NOT_VERIFIED',
    submittedBaseline: index === 0 ? 1.1 : 2.3,
    submittedCurrent: index === 0 ? 9.9 : 8.6,
    submittedDelta: index === 0 ? 8.8 : 6.3,
    submittedComparisonStatus: 'IMPROVED',
    baseline: null,
    current: null,
    delta: null,
    comparisonStatus: 'UNVERIFIED',
  }));
  review.overall = { baseline: null, current: null, delta: null, comparisonStatus: 'UNVERIFIED' };
  review.submittedOverall = { baseline: 1.7, current: 9.2, delta: 7.5, comparisonStatus: 'IMPROVED' };

  const receipts = evidenceReceiptsFixture();
  const markdown = renderMarkdown(review, receipts);
  const html = renderHtml(review, receipts);
  const canonicalMarkdown = markdown.slice(
    markdown.indexOf('## Comparison'),
    markdown.indexOf('### Submitted score inputs'),
  );
  const canonicalHtml = html.slice(
    html.indexOf('<h2 id="comparison">'),
    html.indexOf('<aside class="quarantine"'),
  );

  assert.match(canonicalMarkdown, /\| Orientation \|[^\n]*\| N\/V \| N\/V \| 🟠 \? Unverified/u);
  assert.doesNotMatch(canonicalMarkdown, /\b1\.1\b|\b9\.9\b/u);
  assert.match(markdown, /Submitted score inputs — audit only/u);
  assert.match(markdown, /Not canonical scores/u);
  assert.match(markdown, /🟠 Submitted only — not verified; raw `NOT&#95;VERIFIED`/u);
  assert.match(markdown, /\| \*\*Submitted Overall input\*\*[^\n]*\*\*1\.7\*\*[^\n]*\*\*9\.2\*\*/u);
  assert.match(markdown, /\| \*\*Overall\*\* \| \*\*N\/V\*\* \| \*\*N\/V\*\*/u);

  assert.match(canonicalHtml, />N\/V</u);
  assert.doesNotMatch(canonicalHtml, /\b1\.1\b|\b9\.9\b/u);
  assert.match(html, /class="quarantine"/u);
  assert.match(html, /Quarantined request-submitted score inputs/u);
  assert.match(html, /Request-submitted scores are quarantined/u);
  assert.match(html, /Submitted Overall input/u);
  assert.match(html, /Producer authorization match/u);
  assert.match(html, /Tool source snapshot match/u);
});

test('escapes adversarial text and emits no executable or external assets', () => {
  const review = fixture();
  const receipts = evidenceReceiptsFixture();
  const attack = '<script>alert("owned")</script>|[click](javascript:alert(1))\n# injected';
  review.dimensions[0].label = attack;
  review.blockers[0].summary = attack;
  review.publicationBoundary.statement = attack;
  review.searchDiscovery.status = 'CHANGED_ACTION_REQUIRED';
  review.searchDiscovery.statement = attack;
  review.searchDiscovery.actions = [attack];
  receipts[0].producer.id = attack;
  receipts[0].producer.version = attack;
  receipts[0].execution.arguments[0] = attack;
  receipts[0].claims.gateOutcomes[0].checkId = attack;
  receipts[0].observations[0].label = attack;
  receipts[0].observations[0].value = attack;
  receipts[0].artifacts[0].repositoryPath = attack;

  const markdown = renderMarkdown(review, receipts);
  const html = renderHtml(review, receipts);
  assert.doesNotMatch(markdown, /<script\b/iu);
  assert.match(markdown, /&lt;script&gt;/u);
  assert.match(markdown, /&#124;/u);
  assert.match(markdown, /&#91;click&#93;/u);
  assert.doesNotMatch(html, /<script\b|<link\b|\ssrc=|href=["'](?:https?:|\/\/|javascript:|data:)/iu);
  assert.match(html, /&lt;script&gt;/u);
  assert.doesNotMatch(html, /<button\b/iu);
  assert.match(html, /Native pull-request decision guidance/u);
  assert.match(html, /class="skip-link" href="#main-content"/u);
  assert.match(html, /aria-label="Review report contents"/u);
  assert.doesNotMatch(html, /<details\s+open/iu);
  assert.match(html, /<caption>/u);
  assert.match(html, /Search and AI discovery changed and requires action/u);
  assert.equal(html, renderHtml(review, receipts));
});

test('keeps every nested review-result fact visible in both projections', () => {
  const { review, receipts, sentinels, enumValues } = sentinelFixture();
  const projections = [
    ['Markdown', renderMarkdown(review, receipts)],
    ['HTML', renderHtml(review, receipts)],
  ];

  for (const [name, projection] of projections) {
    const normalizedProjection = projection.replaceAll('&#95;', '_');
    for (const sentinel of sentinels) {
      assert.ok(normalizedProjection.includes(sentinel), `${name} omitted nested fact ${sentinel}`);
    }
    for (const enumValue of enumValues) {
      assert.ok(normalizedProjection.includes(enumValue), `${name} omitted raw enum ${enumValue}`);
    }
    assert.match(projection, /6\.2/u, `${name} omitted a baseline score`);
    assert.match(projection, /8\.8/u, `${name} omitted a current score`);
    assert.match(projection, /\+2\.6/u, `${name} omitted a signed delta`);
    assert.match(projection, /0\.91/u, `${name} omitted confidence value`);
    assert.match(projection, /0\.72/u, `${name} omitted submitted confidence value`);
    assert.match(projection, /3600/u, `${name} omitted maximum evidence age`);
    assert.match(projection, /3456/u, `${name} omitted artifact size`);
    assert.match(projection, /47/u, `${name} omitted numeric observation`);
    assert.match(projection, /true/u, `${name} omitted a required=true fact`);
    assert.match(projection, /false/u, `${name} omitted a required=false fact`);
    assert.match(projection, /Cross-cutting \(not bound to one dimension\)/u, `${name} omitted a null dimension binding`);
    assert.match(projection, /Not supplied/u, `${name} omitted an explicitly absent identity`);
    assert.doesNotMatch(projection, /\[\s*(?:"|&quot;)/u, `${name} serialized an array as JSON`);
  }

  assert.match(projections[0][1].replaceAll('&#95;', '_'), /Candidate-controlled and unverified \(`CANDIDATE_UNVERIFIED`\)/u);
  assert.match(projections[1][1], /Candidate-controlled and unverified <code>CANDIDATE_UNVERIFIED<\/code>/u);
  assert.match(projections[0][1], /Review tool source snapshot digest/u);
  assert.match(projections[1][1], /Review tool source snapshot digest/u);
  assert.match(projections[0][1], /Required evidence kinds/u);
  assert.match(projections[1][1], /Required evidence kinds/u);
  assert.match(projections[0][1], /Candidate evidence subject match/u);
  assert.match(projections[1][1], /Candidate evidence subject match/u);
  assert.match(projections[0][1], /Producer authorization match/u);
  assert.match(projections[1][1], /Producer authorization match/u);
  assert.match(projections[0][1], /Tool source snapshot match/u);
  assert.match(projections[1][1], /Tool source snapshot match/u);
  assert.match(projections[0][1], /Executed evaluator source not verified/u);
  assert.match(projections[1][1], /Executed evaluator source not verified/u);
  assert.match(projections[0][1].replaceAll('&#95;', '_'), /POST_LOAD_FILESYSTEM_SNAPSHOT/u);
  assert.match(projections[1][1], /POST_LOAD_FILESYSTEM_SNAPSHOT/u);
  assert.match(projections[0][1], new RegExp(CONTRACT_VALUES.evaluatorExecutionProvenanceStatement, 'u'));
  assert.match(projections[1][1], new RegExp(CONTRACT_VALUES.evaluatorExecutionProvenanceStatement, 'u'));
  assert.match(projections[0][1], /Submitted Overall input/u);
  assert.match(projections[1][1], /Submitted Overall input/u);
});

test('binds receipt projections by evidence ID and renders in registry order', () => {
  const review = fixture();
  const receipts = evidenceReceiptsFixture();
  const reversed = [...receipts].reverse();
  assert.equal(renderMarkdown(review, receipts), renderMarkdown(review, reversed));
  assert.equal(renderHtml(review, receipts), renderHtml(review, reversed));
});

test('fails closed when the explicit receipt collection is incomplete, invalid, or unbound', () => {
  const invalidCases = [
    {
      label: 'missing collection',
      mutate: () => undefined,
    },
    {
      label: 'empty collection',
      mutate: () => [],
    },
    {
      label: 'missing bound receipt',
      mutate: (receipts) => receipts.slice(0, 1),
    },
    {
      label: 'extra receipt',
      mutate: (receipts) => [...receipts, { ...receipts[0], evidenceId: 'extra-receipt' }],
    },
    {
      label: 'duplicate evidence ID',
      mutate: (receipts) => [receipts[0], { ...receipts[1], evidenceId: receipts[0].evidenceId }],
    },
    {
      label: 'mismatched receipt digest',
      mutate: (receipts) => {
        receipts[0].receiptId = SHA_F;
        return receipts;
      },
    },
    {
      label: 'unsupported receipt schema version',
      mutate: (receipts) => {
        receipts[0].schemaVersion = 2;
        return receipts;
      },
    },
    {
      label: 'missing producer digest',
      mutate: (receipts) => {
        delete receipts[0].producer.digest;
        return receipts;
      },
    },
    {
      label: 'unsupported adapter',
      mutate: (receipts) => {
        receipts[0].execution.adapter = 'SHELL_GUESS';
        return receipts;
      },
    },
    {
      label: 'unsupported gate-outcome status',
      mutate: (receipts) => {
        receipts[0].claims.gateOutcomes[0].status = 'SKIP';
        return receipts;
      },
    },
    {
      label: 'missing gate outcomes',
      mutate: (receipts) => {
        delete receipts[0].claims.gateOutcomes;
        return receipts;
      },
    },
    {
      label: 'non-scalar observation',
      mutate: (receipts) => {
        receipts[0].observations[0].value = { nested: true };
        return receipts;
      },
    },
  ];

  for (const { label, mutate } of invalidCases) {
    const review = fixture();
    const receipts = mutate(evidenceReceiptsFixture());
    assert.throws(() => renderMarkdown(review, receipts), TypeError, `Markdown accepted ${label}`);
    assert.throws(() => renderHtml(review, receipts), TypeError, `HTML accepted ${label}`);
  }
});

test('fails closed on unknown enums or a missing required projection fact', () => {
  const cases = [
    (review) => { review.dimensions[0].comparisonStatus = 'BETTER'; },
    (review) => { review.gates[0].status = 'SKIPPED'; },
    (review) => { review.readinessVerdict = 'MAYBE'; },
    (review) => { review.confidence.label = 'CERTAIN'; },
    (review) => { review.submittedConfidence.label = 'CERTAIN'; },
    (review) => { delete review.submittedConfidence; },
    (review) => { review.freshness.status = 'RECENT'; },
    (review) => { review.searchDiscovery.status = 'UNKNOWN'; },
    (review) => { review.trustControl.producerRegistryAuthority = 'TRUSTED'; },
    (review) => { review.blockers[0].unlock.kind = 'AUTOMATIC'; },
    (review) => { delete review.trustControl.toolSourceDigest; },
    (review) => { delete review.trustControl.evaluatorExecutionProvenance; },
    (review) => { review.trustControl.evaluatorExecutionProvenance.status = 'VERIFIED'; },
    (review) => { review.trustControl.evaluatorExecutionProvenance.method = 'PRELOAD_DIGEST'; },
    (review) => { review.trustControl.evaluatorExecutionProvenance.executedSourceDigest = SHA_D; },
    (review) => { review.trustControl.evaluatorExecutionProvenance.controllerAttestationRef = 'controller'; },
    (review) => { review.trustControl.evaluatorExecutionProvenance.statement = 'Exact execution was verified.'; },
    (review) => { review.dimensions[0].scoreStatus = 'PENDING'; },
    (review) => { review.dimensions[0].scoreStatus = 'NOT_VERIFIED'; },
    (review) => { review.scoringMethod = 'FREEFORM'; },
    (review) => { delete review.dimensions[0].criterion; },
    (review) => { review.dimensions[0].scoreAnchors = review.dimensions[0].scoreAnchors.slice(0, 2); },
    (review) => { review.dimensions[0].scoreAnchors.reverse(); },
  ];
  for (const mutate of cases) {
    const review = fixture();
    const receipts = evidenceReceiptsFixture();
    mutate(review);
    assert.throws(() => renderMarkdown(review, receipts), TypeError);
    assert.throws(() => renderHtml(review, receipts), TypeError);
  }
});
