(() => {
  const views = {
    inventory: document.getElementById('inventory-view'),
    remove: document.getElementById('remove-view'),
    package: document.getElementById('package-view'),
    space: document.getElementById('space-view'),
    registration: document.getElementById('registration-view'),
  }
  const labels = {
    inventory: ['Authoring inventory', 'LIVE API DATA ONLY'],
    remove: ['Remove', 'SERVER DECISION REQUIRED'],
    package: ['Dataset packages', 'DRAFT · revision 7'],
    space: ['Dataset spaces', 'DRAFT · policy v3'],
    registration: ['Registrations', 'NEW REGISTRATION'],
  }
  const inventoryKinds = {
    packages: {
      label: 'Dataset packages',
      searchLabel: 'Search packages',
      placeholder: 'Search authorised packages',
      endpoint: 'GET /api/dataset-packages',
      headers: ['Package', 'Latest version', 'Lifecycle', 'Dependencies', 'Actions'],
      icon: '▦',
      emptyTitle: 'No Dataset packages are available',
      emptyCopy: 'The authorised Scenario Manager request returned zero packages. No sample rows or local fallback data are displayed.',
      addLabel: 'Add Dataset package',
      addView: 'package',
      importVisible: true,
    },
    spaces: {
      label: 'Dataset Spaces',
      searchLabel: 'Search Dataset Spaces',
      placeholder: 'Search authorised Dataset Spaces',
      endpoint: 'GET /api/dataset-spaces',
      headers: ['Dataset Space', 'Latest version', 'Lifecycle', 'Dependencies', 'Actions'],
      icon: '⬢',
      emptyTitle: 'No Dataset Spaces are available',
      emptyCopy: 'The authorised Scenario Manager request returned zero Dataset Spaces. No sample policy or local fallback data are displayed.',
      addLabel: 'Add Dataset Space',
      addView: 'space',
      importVisible: false,
    },
    registrations: {
      label: 'Dataset registrations',
      searchLabel: 'Search Dataset registrations',
      placeholder: 'Search authorised registrations',
      endpoint: 'GET /api/dataset-registrations',
      headers: ['Registration', 'Current version', 'Lifecycle', 'Dependencies', 'Actions'],
      icon: '⇄',
      emptyTitle: 'No Dataset registrations are available',
      emptyCopy: 'The authorised Scenario Manager request returned zero registrations. No adapter selection or local fallback data are displayed.',
      addLabel: 'Add Dataset registration',
      addView: 'registration',
      importVisible: false,
    },
  }
  const adverseStates = {
    loading: ['Loading authorised authoring data', 'No names, counts, lifecycle states or pseudo-values are shown before Scenario Manager responds.', 'info'],
    forbidden: ['Authoring access is not permitted', 'No totals, identifiers, drafts or dependency facts are retained or displayed for this scope.', 'danger'],
    unavailable: ['Scenario Manager authoring is unavailable', 'This is an error state, not a successful empty inventory. Retry performs a real authenticated read.', 'warning'],
    validation: ['Draft validation failed', 'Path-specific server issues preserve unsaved input and link to the affected field, file and package section.', 'danger'],
    conflict: ['Draft revision changed', 'Local input is preserved. Reload the current server version or export the local draft; PocketHive never merges or overwrites automatically.', 'warning'],
    dependency_blocked: ['Lifecycle action blocked by dependencies', 'Only bounded server-returned dependency facts are shown. There is no force-delete or force-retire path.', 'warning'],
    accepted_read_failed: ['Command accepted; current state unavailable', 'The command receipt and correlation ID are retained. Retry re-reads authority and never fabricates the new lifecycle state.', 'warning'],
  }

  function applyAdverseState() {
    const requested = new URLSearchParams(window.location.search).get('authoringState')
    const state = adverseStates[requested]
    const banner = document.getElementById('authoring-state-banner')
    banner.hidden = !state
    if (!state) return
    document.getElementById('authoring-state-title').textContent = state[0]
    document.getElementById('authoring-state-detail').textContent = state[1]
    banner.dataset.tone = state[2]
    if (requested === 'dependency_blocked') {
      document.getElementById('remove-title').textContent = 'Removal blocked'
      document.getElementById('remove-description').textContent = 'Scenario Manager reports that the exact selected object still has dependencies. No delete or retire command is permitted from this response.'
      const dependencies = document.getElementById('remove-dependencies')
      dependencies.textContent = 'Bounded dependency facts returned'
      dependencies.classList.remove('good-text')
      document.getElementById('remove-command').textContent = 'No permitted effect'
      document.getElementById('remove-confirm').hidden = true
    }
    if (requested === 'conflict') {
      const status = document.getElementById('package-review-status')
      status.textContent = 'STALE'
      status.classList.remove('good')
      status.classList.add('muted-status')
      document.getElementById('package-review-issues').textContent = 'Not evaluated against current revision'
      document.getElementById('package-review-issues').classList.remove('good-text')
      document.getElementById('package-review-next-action').textContent = 'Reload server version or export local draft'
    }
    if (requested === 'validation') {
      const status = document.getElementById('package-review-status')
      status.textContent = 'INVALID'
      status.classList.remove('good')
      status.classList.add('muted-status')
      document.getElementById('package-review-issues').textContent = 'Path-specific issues returned'
      document.getElementById('package-review-issues').classList.remove('good-text')
      document.getElementById('package-review-next-action').textContent = 'Open first issue'
    }
  }

  function setInventoryKind(requested) {
    const kind = Object.hasOwn(inventoryKinds, requested) ? requested : 'packages'
    const config = inventoryKinds[kind]
    document.querySelectorAll('[data-inventory-kind]').forEach(button => {
      const selected = button.dataset.inventoryKind === kind
      button.classList.toggle('selected', selected && button.closest('.inventory-tabs'))
      button.classList.toggle('active', selected && button.classList.contains('inventory-route-button'))
      if (button.closest('.inventory-tabs')) button.setAttribute('aria-pressed', String(selected))
    })
    document.getElementById('inventory-search-label').textContent = config.searchLabel
    document.getElementById('inventory-search').placeholder = config.placeholder
    document.getElementById('inventory-api').textContent = config.endpoint
    document.getElementById('inventory-header').replaceChildren(...config.headers.map(label => {
      const span = document.createElement('span')
      span.textContent = label
      return span
    }))
    document.getElementById('inventory-empty-icon').textContent = config.icon
    document.getElementById('inventory-empty-title').textContent = config.emptyTitle
    document.getElementById('inventory-empty-copy').textContent = config.emptyCopy
    for (const id of ['inventory-add', 'inventory-empty-add']) {
      const button = document.getElementById(id)
      button.textContent = config.addLabel
      button.dataset.openView = config.addView
    }
    document.getElementById('inventory-import').hidden = !config.importVisible
    document.getElementById('breadcrumb-current').textContent = config.label
  }

  function setPackageSection(requested) {
    const available = new Set(['identity', 'record-schema', 'contracts', 'storage', 'content', 'review'])
    const section = available.has(requested) ? requested : 'record-schema'
    document.querySelectorAll('[data-package-section]').forEach(button => {
      const selected = button.dataset.packageSection === section
      button.classList.toggle('selected', selected)
      button.setAttribute('aria-pressed', String(selected))
    })
    document.querySelectorAll('[data-package-panel]').forEach(panel => {
      panel.hidden = panel.dataset.packagePanel !== section
    })
  }

  function showView(requested) {
    const [requestedView, requestedKind] = requested.split('/')
    const view = Object.hasOwn(views, requestedView) ? requestedView : 'inventory'
    Object.entries(views).forEach(([key, element]) => { element.hidden = key !== view })
    document.querySelectorAll('.route-button').forEach(button => button.classList.toggle('active', button.dataset.view === view))
    document.getElementById('breadcrumb-current').textContent = labels[view][0]
    document.getElementById('context-chip').textContent = labels[view][1]
    if (view === 'inventory') setInventoryKind(requestedKind)
    else document.querySelectorAll('.inventory-route-button').forEach(button => button.classList.remove('active'))
    if (view === 'package') setPackageSection(requestedKind)
    document.title = `PocketHive — ${labels[view][0]} authoring wireframe`
  }

  document.querySelectorAll('.route-button').forEach(button => button.addEventListener('click', () => {
    window.location.hash = button.dataset.view
  }))
  document.querySelectorAll('[data-open-view]').forEach(button => button.addEventListener('click', () => {
    window.location.hash = button.dataset.openView
  }))
  document.querySelectorAll('[data-inventory-kind]').forEach(button => button.addEventListener('click', () => {
    window.location.hash = `inventory/${button.dataset.inventoryKind}`
  }))
  document.querySelectorAll('[data-package-section]').forEach(button => button.addEventListener('click', () => {
    window.location.hash = `package/${button.dataset.packageSection}`
  }))
  window.addEventListener('hashchange', () => showView(window.location.hash.slice(1)))
  applyAdverseState()
  showView(window.location.hash.slice(1))
})()
