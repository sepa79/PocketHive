import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('.', import.meta.url))
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
const js = fs.readFileSync(path.join(root, 'app.js'), 'utf8')
const css = fs.readFileSync(path.join(root, 'styles.css'), 'utf8')
const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8')
const designQa = fs.readFileSync(path.join(root, 'design-qa.md'), 'utf8')
const spec = fs.readFileSync(path.join(root, '..', 'managed-datasets-operator-ui-design-spec.md'), 'utf8')
const authoringHtml = fs.readFileSync(path.join(root, 'authoring.html'), 'utf8')
const authoringJs = fs.readFileSync(path.join(root, 'authoring.js'), 'utf8')
const authoringCss = fs.readFileSync(path.join(root, 'authoring.css'), 'utf8')
const authoringApi = fs.readFileSync(path.join(root, '..', '..', 'contracts', 'managed-dataset-authoring-api.md'), 'utf8')
const authoringSchema = fs.readFileSync(path.join(root, '..', '..', 'spec', 'managed-dataset-authoring.schema.json'), 'utf8')
const failures = []
let passed = 0

function check(condition, message) {
  if (!condition) failures.push(message)
}

function group(name, fn) {
  const before = failures.length
  fn()
  if (failures.length === before) passed += 1
  else failures.splice(before, 0, `${name}:`)
}

function attributes(source) {
  const result = new Map()
  const pattern = /([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g
  for (const match of source.matchAll(pattern)) {
    result.set(match[1].toLowerCase(), match[2] ?? match[3] ?? match[4] ?? '')
  }
  return result
}

function parse(source) {
  const documentNode = { tag: '#document', attrs: new Map(), parent: null, children: [] }
  const stack = [documentNode]
  const nodes = []
  const voidTags = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr'])
  const clean = source.replace(/<!--[\s\S]*?-->/g, '')
  const tags = /<\s*(\/?)\s*([a-zA-Z][\w:-]*)([^>]*)>/g
  for (const match of clean.matchAll(tags)) {
    const closing = match[1] === '/'
    const tag = match[2].toLowerCase()
    if (closing) {
      while (stack.length > 1 && stack.at(-1).tag !== tag) stack.pop()
      if (stack.length > 1) stack.pop()
      continue
    }
    const node = {
      tag,
      attrs: attributes(match[3]),
      parent: stack.at(-1),
      children: [],
    }
    node.parent.children.push(node)
    nodes.push(node)
    if (!voidTags.has(tag) && !match[3].trimEnd().endsWith('/')) stack.push(node)
  }
  return nodes
}

const nodes = parse(html)
const authoringNodes = parse(authoringHtml)
const byId = new Map()
for (const node of nodes) {
  const id = node.attrs.get('id')
  if (!id) continue
  if (!byId.has(id)) byId.set(id, [])
  byId.get(id).push(node)
}

group('Authoring document structure and control names', () => {
  const authoringIds = new Map()
  for (const node of authoringNodes) {
    const id = node.attrs.get('id')
    if (!id) continue
    authoringIds.set(id, (authoringIds.get(id) ?? 0) + 1)
  }
  for (const [id, count] of authoringIds) check(count === 1, `authoring document has duplicate id="${id}"`)
  for (const control of authoringNodes.filter((node) => ['input', 'select', 'textarea'].includes(node.tag))) {
    if (control.attrs.get('type') === 'hidden') continue
    const id = control.attrs.get('id')
    const labelFors = new Set(authoringNodes.filter((node) => node.tag === 'label').map((node) => node.attrs.get('for')).filter(Boolean))
    const named = control.attrs.has('aria-label') || control.attrs.has('aria-labelledby') || hasAncestor(control, 'label') || (id && labelFors.has(id))
    check(named, `authoring <${control.tag}>${id ? `#${id}` : ''} has no programmatic label`)
  }
  for (const match of authoringHtml.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/gi)) {
    const attrs = attributes(match[1])
    const visibleText = match[2].replace(/<[^>]+>/g, ' ').replace(/&[a-zA-Z0-9#]+;/g, ' ').replace(/\s+/g, ' ').trim()
    check(Boolean(attrs.get('aria-label') || attrs.get('aria-labelledby') || visibleText), 'authoring button has no accessible name')
  }
})

group('Unique element IDs', () => {
  for (const [id, matches] of byId) check(matches.length === 1, `duplicate id="${id}" (${matches.length} occurrences)`)
})

group('ARIA ID references', () => {
  for (const node of nodes) {
    for (const attribute of ['aria-labelledby', 'aria-controls', 'aria-describedby']) {
      const value = node.attrs.get(attribute)
      if (!value) continue
      for (const id of value.trim().split(/\s+/)) {
        check(byId.has(id), `<${node.tag}> ${attribute} references missing id="${id}"`)
      }
    }
  }
})

group('Internal fragment targets', () => {
  for (const node of nodes.filter((candidate) => candidate.tag === 'a')) {
    const href = node.attrs.get('href')
    if (href?.startsWith('#') && href.length > 1) check(byId.has(href.slice(1)), `href="${href}" has no target`)
  }
})

group('Tab relationships and keyboard contract', () => {
  const tabs = nodes.filter((node) => node.attrs.get('role') === 'tab')
  check(tabs.length > 0, 'no tabs found')
  for (const tab of tabs) {
    const id = tab.attrs.get('id')
    const controlledId = tab.attrs.get('aria-controls')
    const panel = byId.get(controlledId)?.[0]
    check(Boolean(id), 'tab has no id')
    check(Boolean(controlledId), `tab ${id ?? '(missing id)'} has no aria-controls`)
    check(panel?.attrs.get('role') === 'tabpanel', `tab ${id} does not control a tabpanel`)
    check(panel?.attrs.get('aria-labelledby')?.split(/\s+/).includes(id), `tabpanel ${controlledId} does not point back to ${id}`)
    check(['true', 'false'].includes(tab.attrs.get('aria-selected')), `tab ${id} has invalid aria-selected`)
  }
  for (const token of ['ArrowRight', 'ArrowLeft', 'Home', 'End', 'setTab']) {
    check(js.includes(token), `tab keyboard contract is missing ${token}`)
  }
})

function hasAncestor(node, tag) {
  for (let current = node.parent; current; current = current.parent) if (current.tag === tag) return true
  return false
}

group('Form labels and image alternatives', () => {
  const labelFors = new Set(nodes.filter((node) => node.tag === 'label').map((node) => node.attrs.get('for')).filter(Boolean))
  for (const control of nodes.filter((node) => ['input', 'select', 'textarea'].includes(node.tag))) {
    if (control.attrs.get('type') === 'hidden') continue
    const id = control.attrs.get('id')
    const named = control.attrs.has('aria-label') || control.attrs.has('aria-labelledby') || hasAncestor(control, 'label') || (id && labelFors.has(id))
    check(named, `<${control.tag}>${id ? `#${id}` : ''} has no programmatic label`)
  }
  for (const image of nodes.filter((node) => node.tag === 'img')) check(image.attrs.has('alt'), '<img> is missing alt')
})

group('Button accessible names', () => {
  for (const match of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/gi)) {
    const attrs = attributes(match[1])
    const visibleText = match[2].replace(/<[^>]+>/g, ' ').replace(/&[a-zA-Z0-9#]+;/g, ' ').replace(/\s+/g, ' ').trim()
    check(Boolean(attrs.get('aria-label') || attrs.get('aria-labelledby') || visibleText), 'button has no accessible name')
  }
})

group('Route and in-page navigation targets', () => {
  const screenKeys = new Set(Array.from(js.matchAll(/^\s{4}([a-z][\w]*): document\.getElementById\('[^']+-screen'\)/gm), (match) => match[1]))
  check(screenKeys.size === 3, `expected 3 routed screens, found ${screenKeys.size}`)
  for (const node of nodes.filter((candidate) => candidate.attrs.has('data-navigate'))) {
    const target = node.attrs.get('data-navigate')
    check(screenKeys.has(target), `data-navigate="${target}" has no routed screen`)
  }
  for (const node of nodes.filter((candidate) => candidate.attrs.has('data-tab-target'))) {
    const target = node.attrs.get('data-tab-target')
    check(byId.has(`tab-${target}`), `data-tab-target="${target}" has no matching tab`)
  }
})

group('Required architecture and operator copy', () => {
  const required = [
    'swarm-start',
    'ph.control',
    'DATASET_SUPPLY',
    'WorkItem',
    'PostgreSQL',
    'Swarm Controller',
    'Producer workload',
    'FILLING_TO_TARGET',
    'SHARED',
    'add-back',
    '50,000',
    'Not yet proven',
    'Dataset reconciler',
    'Scenario timeline',
    'Traffic pacer',
  ]
  for (const token of required) check(html.includes(token), `wireframe is missing required copy token: ${token}`)
  for (const token of ['two-stage', 'control plane', 'DATASET_SUPPLY', 'orthogonal', '50,000', 'SHARED', 'add-back']) {
    check(spec.toLowerCase().includes(token.toLowerCase()), `operator specification is missing required concept: ${token}`)
  }
})

group('Supply-state specimens', () => {
  for (const state of ['idle', 'waiting_readiness', 'dispatch_failed', 'commit_uncertain']) {
    check(js.includes(`${state}: {`), `missing supplyState=${state} specimen`)
    check(readme.includes(`supplyState=${state}`), `README does not document supplyState=${state}`)
  }
  for (const step of ['reconcile', 'control', 'ready', 'dispatch', 'commit']) {
    check(html.includes(`data-supply-step="${step}"`), `missing supply journey step: ${step}`)
  }
})

group('Supply WorkItem transport proof boundary', () => {
  const currentContractSource = `${html}\n${js}\n${readme}\n${designQa}\n${spec}`
  const deferredHintToken = ['DATASET', 'HINT'].join('_')
  check(!currentContractSource.includes(deferredHintToken), 'deferred hint destination contract leaked into current UI artifacts')
  for (const token of [
    'DATASET_SUPPLY',
    'WORKITEM_SUPPLY',
    'Supply WorkItem transport accepted',
    'Transport evidence only',
    'terminal PostgreSQL receipt not established',
  ]) {
    check(currentContractSource.toLowerCase().includes(token.toLowerCase()), `missing supply transport boundary copy: ${token}`)
  }
  const levelBlock = js.match(/const proofLevels = Object\.freeze\(\[([\s\S]*?)\]\)/)?.[1] ?? ''
  const configured = levelBlock.indexOf("'CONFIGURED'")
  const broker = levelBlock.indexOf("'BROKER_ACCEPTED'")
  const sourced = levelBlock.indexOf("'SOURCED'")
  const persisted = levelBlock.indexOf("'PERSISTED'")
  check(configured >= 0 && configured < broker && broker < sourced && sourced < persisted, 'proof order must be CONFIGURED -> BROKER_ACCEPTED -> SOURCED -> PERSISTED')
  check(/data-proof-fact="BROKER_ACCEPTED" data-proof-order="1"/.test(html), 'BROKER_ACCEPTED card must be proof order 1')
  check(/data-proof-fact="SOURCED" data-proof-order="2"/.test(html), 'SOURCED card must be proof order 2')
  check(/data-proof-fact="PERSISTED" data-proof-order="3"/.test(html), 'PERSISTED card must be proof order 3')
  check(spec.includes('destinationClass: WORKITEM_SUPPLY'), 'proof target must restrict broker evidence to WORKITEM_SUPPLY')
})

group('Source-result routing boundaries', () => {
  for (const token of ['HTTP 201', 'FAILED_WRONG_STATE', 'UPSERT_DATASET', 'failure-results@1', 'contributesToPrimarySupply false', 'raw HTTP/TCP response withheld']) {
    check(html.includes(token), `operation specimen is missing ${token}`)
  }
})

group('No undersized operational text', () => {
  check(!/font-size:\s*[89]px\b/.test(css), 'styles contain 8px or 9px operational text')
})

group('No prohibited planning regressions', () => {
  check(!/\b(?:REFRESH|VALIDATE|DEPROVISION)\b/.test(html.replace(/REFRESH_MATERIAL|VALIDATE_RECORD|DEPROVISION_ENTITY/g, '')), 'deprecated operation literal found')
  check(!/status-pill[^>]*>\s*Attention\s*</i.test(html), 'serialized Attention status found')
  const currentNeutralSource = `${html}\n${js}\n${readme}\n${designQa}\n${spec}`
  const nonNeutralPatterns = [
    new RegExp(['pay', 'ment'].join(''), 'i'),
    new RegExp(['card', 'holder'].join(''), 'i'),
    new RegExp(['customer', 'Class'].join(''), 'i'),
    new RegExp(['product', 'Class'].join(''), 'i'),
    new RegExp(`\\b${['P', 'AN'].join('')}\\b`, 'i'),
    new RegExp(`\\b${['S', 'AD'].join('')}\\b`, 'i'),
  ]
  for (const [index, pattern] of nonNeutralPatterns.entries()) {
    check(!pattern.test(currentNeutralSource), `non-neutral fixture term pattern ${index + 1} found`)
  }
})

group('Authoring contract and complete journeys', () => {
  for (const kind of ['packages', 'spaces', 'registrations']) {
    check(authoringHtml.includes(`data-inventory-kind="${kind}"`), `authoring inventory is missing ${kind} control`)
    check(authoringJs.includes(`${kind}: {`), `authoring inventory has no ${kind} behavior`)
  }
  for (const endpoint of ['/api/dataset-packages', '/api/dataset-spaces', '/api/dataset-registrations']) {
    check(authoringJs.includes(endpoint), `authoring inventory is missing ${endpoint}`)
    check(authoringApi.includes(endpoint), `canonical authoring API is missing ${endpoint}`)
  }
  for (const token of ['Manifest-declared package content', 'Quota policy reference', 'Replacement transaction', 'Expected ETag']) {
    check(authoringHtml.includes(token), `authoring flow is missing ${token}`)
  }
  check(!authoringHtml.includes('Maximum registrations'), 'wireframe duplicates quotaPolicyRef with Maximum registrations')
  check(!authoringHtml.includes('Maximum records'), 'wireframe duplicates quotaPolicyRef with Maximum records')
  check(authoringCss.includes('.authoring-tabs>button'), 'authoring inventory controls have no button styling')
  check(authoringCss.includes('font-size:10px'), 'authoring readability override is missing')
})

group('Authoring SSOT and no-fallback invariants', () => {
  for (const token of ['DatasetPackageWrite', 'DatasetPackageVersion', 'DatasetSpaceWrite', 'DatasetRegistrationWrite', 'CommandReceipt', 'Problem']) {
    check(authoringSchema.includes(`"${token}"`), `canonical authoring schema is missing ${token}`)
  }
  for (const token of ['If-Match', 'Idempotency-Key', 'Registration replacement transaction', 'Canonical package archive and digest']) {
    check(authoringApi.includes(token), `canonical authoring API is missing ${token}`)
  }
  check(authoringApi.includes('Space and registration lifecycle are UI/API-only in the MVP'), 'MCP/UI authoring scope is ambiguous')
  check(!authoringHtml.includes('datasetContractId'), 'registration UI must not select a package-local contract')
  check(authoringHtml.includes('never switches to Redis'), 'registration UI no-fallback copy is missing')
})

group('Canonical package digest vectors', () => {
  function packageDigest(files) {
    const chunks = []
    for (const [filePath, content] of files) {
      const pathBytes = Buffer.from(filePath, 'utf8')
      const contentBytes = Buffer.from(content, 'utf8')
      const pathLength = Buffer.alloc(4)
      pathLength.writeUInt32BE(pathBytes.length)
      const contentLength = Buffer.alloc(8)
      contentLength.writeBigUInt64BE(BigInt(contentBytes.length))
      chunks.push(pathLength, pathBytes, contentLength, contentBytes)
    }
    return `sha256:${createHash('sha256').update(Buffer.concat(chunks)).digest('hex')}`
  }
  const vectors = [
    [[['dataset.yaml', '{}\n']], 'sha256:932f7792e72c1211925905e7728361627606767c85949069701bff437a564b89'],
    [[['dataset.yaml', '{}\n'], ['schema/record.yaml', 'type: object\n']], 'sha256:b2ad187264a84797becbdd60d59f62a020204278ee17286911576f4ca6dc4bd4'],
  ]
  for (const [files, expected] of vectors) {
    check(packageDigest(files) === expected, `package digest vector mismatch for ${files.map(([name]) => name).join(', ')}`)
    check(authoringApi.includes(expected), `canonical API does not publish digest vector ${expected}`)
  }
})

group('Authoring adverse states', () => {
  for (const state of ['loading', 'forbidden', 'unavailable', 'validation', 'conflict', 'dependency_blocked', 'accepted_read_failed']) {
    check(authoringJs.includes(`${state}: [`), `authoringState=${state} behavior is missing`)
    check(readme.includes(`authoringState=${state}`), `README does not document authoringState=${state}`)
  }
  check(authoringHtml.includes('id="authoring-state-banner"'), 'authoring adverse-state live region is missing')
})

if (failures.length) {
  console.error(`Managed Datasets wireframe QA failed (${failures.length} findings):`)
  for (const failure of failures) console.error(`- ${failure}`)
  process.exitCode = 1
} else {
  console.log(`Managed Datasets wireframe QA passed: ${passed} deterministic source-level groups.`)
  console.log('Visual layout, contrast, zoom, accessibility-tree and screen-reader checks remain separate evidence.')
}
