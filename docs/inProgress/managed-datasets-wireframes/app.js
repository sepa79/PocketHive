(() => {
  const body = document.body
  const root = document.documentElement
  const queryParams = new URLSearchParams(window.location.search)
  if (queryParams.get('theme') === 'light') root.dataset.theme = 'light'
  const shell = document.getElementById('app-shell')
  const screens = {
    datasets: document.getElementById('datasets-screen'),
    dataset: document.getElementById('dataset-detail-screen'),
    inspector: document.getElementById('inspector-screen'),
  }

  const breadcrumbList = document.getElementById('breadcrumb-list')
  const datasetsTools = document.querySelector('.datasets-tools')
  const inspectorTools = document.querySelector('.inspector-tools')
  const topSearch = document.querySelector('.top-search')
  const globalSearch = document.getElementById('global-search')
  const datasetSearch = document.getElementById('dataset-search')
  const environmentFilter = document.getElementById('environment-filter')
  const healthFilter = document.getElementById('health-filter')
  const fitnessFilter = document.getElementById('fitness-filter')
  const visibleCount = document.getElementById('visible-count')
  const emptyResults = document.getElementById('empty-results')
  const refreshAnnouncement = document.getElementById('refresh-announcement')
  const navCollapse = document.getElementById('nav-collapse')
  const compactNavigation = window.matchMedia('(max-width: 1100px)')
  const adverseStatePanel = document.getElementById('adverse-state-panel')
  const adverseStateTitle = document.getElementById('adverse-state-title')
  const adverseStateKicker = document.getElementById('adverse-state-kicker')
  const adverseStateDetail = document.getElementById('adverse-state-detail')
  const adverseStateAction = document.getElementById('adverse-state-action')
  const adverseStateOwner = document.getElementById('adverse-state-owner')
  const adverseStateRunbookRef = document.getElementById('adverse-state-runbook-ref')
  const adverseStateRunbook = document.getElementById('adverse-state-runbook')
  const adverseStateRetry = document.getElementById('adverse-state-retry')
  const adverseStateRetryBoundary = document.getElementById('adverse-state-retry-boundary')
  const adverseStateFeedback = document.getElementById('adverse-state-feedback')
  const historicalScreenNote = document.getElementById('historical-screen-note')

  const demoStates = {
    reconciling: {
      kicker: 'Module state · RECONCILING',
      title: 'Dataset truth is reconciling',
      detail: 'Readiness and admission are unknown while durable projections reconcile. Progress may be shown only when the server returns it.',
      action: 'WAIT_FOR_RECONCILIATION',
      owner: 'Platform Operations',
      runbook: 'rb-dataset-reconciliation-02',
    },
    stale: {
      kicker: 'Freshness boundary exceeded',
      title: 'Dataset status is stale',
      detail: 'Last-known facts remain labelled as historical. Every current decision is unknown until a new product observation is accepted.',
      action: 'RETRY_STATUS_READ_AFTER_BOUNDARY',
      owner: 'Dataset Operations',
      runbook: 'rb-dataset-observation-04',
      retry: true,
      retryEnabled: true,
      retryBoundary: 'Refresh is available now because the prior validity boundary 16:25:18 UTC has passed.',
      preserveLastKnown: true,
    },
    rate_limited: {
      kicker: 'Read limited · HTTP 429',
      title: 'Status refresh is temporarily limited',
      detail: 'The prior observation is not promoted. Retry becomes available only after the server-provided Retry-After boundary.',
      action: 'RETRY_STATUS_READ_AFTER_BOUNDARY',
      owner: 'Dataset Operations',
      runbook: 'rb-dataset-read-limits-02',
      retry: true,
      retryEnabled: false,
      retryBoundary: 'Retry-After boundary: 16:25:48 UTC · Refresh remains disabled until that exact instant.',
      preserveLastKnown: true,
    },
    forbidden: {
      kicker: 'Feature permission',
      title: 'Managed Datasets is unavailable to this access scope',
      detail: 'No inventory totals, facets, identifiers or hidden-scope hints are disclosed.',
      action: 'REQUEST_ACCESS',
      owner: 'Access Administration',
      runbook: 'rb-generic-access-request-01',
    },
    empty: {
      kicker: 'Authorised result',
      title: 'No Managed Dataset selections are available',
      detail: 'The request succeeded, but this access scope currently has no authorised operational status scopes.',
      action: 'NO_OPERATOR_ACTION',
      owner: 'Not applicable',
      runbook: 'Not applicable',
    },
    incompatible: {
      kicker: 'Contract rejected',
      title: 'Dataset status response is incompatible',
      detail: 'The browser rejected the complete response and did not partially trust malformed safety facts. Request ID req-wireframe-1842.',
      action: 'CONTACT_DATASET_OWNER',
      owner: 'Dataset Operations',
      runbook: 'rb-dataset-contract-mismatch-03',
      retry: true,
      retryEnabled: true,
      retryBoundary: 'A new contract-safe read may be attempted now; the rejected response remains unused.',
    },
  }

  const viewCopy = {
    datasets: ['Datasets'],
    dataset: ['Datasets', 'primary-test-entities'],
    inspector: ['Hive', 'service-simulation-01', 'Inspector'],
  }

  function setBreadcrumbs(view) {
    const segments = viewCopy[view] ?? viewCopy.datasets
    const items = [
      '<li><button type="button" data-navigate="datasets">PocketHive</button></li>',
      ...segments.flatMap((segment, index) => {
        const isLast = index === segments.length - 1
        const target = view === 'inspector' ? 'inspector' : 'datasets'
        const item = isLast
          ? `<li aria-current="page">${segment}</li>`
          : `<li><button type="button" data-navigate="${target}">${segment}</button></li>`
        return ['<li aria-hidden="true">/</li>', item]
      }),
    ]
    breadcrumbList.innerHTML = items.join('')
    bindNavigation(breadcrumbList)
  }

  function selectedTabName() {
    const tab = document.querySelector('.detail-workspace [role="tab"][aria-selected="true"]')
    return tab?.id.replace('tab-', '') ?? 'overview'
  }

  function routeHash(view) {
    return view === 'dataset' ? `#dataset/${selectedTabName()}` : `#${view}`
  }

  function markInventoryFactsHistorical() {
    const inventory = screens.datasets
    if (inventory.dataset.historicalDecorated === 'true') return
    inventory.dataset.historicalDecorated = 'true'

    inventory.querySelectorAll('.summary-card').forEach((card) => {
      const label = card.querySelector('.summary-label')
      if (label) label.textContent = `Last-known ${label.textContent}`
    })
    const readySummary = inventory.querySelector('.summary-card.summary-good .summary-meta')
    if (readySummary) readySummary.textContent = 'Historical READY-scope count · current admission Unknown until refreshed'

    inventory.querySelectorAll('[data-dataset-row]').forEach((row) => {
      ;['Dataset health', 'Fitness'].forEach((label) => {
        const cell = row.querySelector(`[data-label="${label}"]`)
        const pill = cell?.querySelector('.status-pill')
        const detail = cell?.querySelector('small')
        if (!cell || !pill || !detail) return
        const prior = pill.textContent.trim()
        const priorDetail = detail.textContent.trim()
        pill.textContent = 'Unknown until refreshed'
        pill.className = 'status-pill pill-neutral'
        detail.textContent = `Last known ${prior}: ${priorDetail}`
      })

      const continuity = row.querySelector('[data-label="Continuity"]')
      const continuityValue = continuity?.querySelector('strong')
      const continuityDetail = continuity?.querySelector('small')
      if (continuity && continuityValue && continuityDetail) {
        const prior = `${continuityValue.textContent.trim()} · ${continuityDetail.textContent.trim()}`
        continuityValue.textContent = 'Unknown until refreshed'
        continuityDetail.textContent = `Last-known continuity: ${prior}`
      }
    })

    const attention = inventory.querySelector('.attention-copy span')
    if (attention) {
      attention.textContent = 'Last-known issue retained for diagnosis. Current Dataset admission and running-consumer decisions are Unknown until refreshed.'
    }
  }

  function setView(view, updateHash = true, moveFocus = updateHash) {
    const next = screens[view] ? view : 'datasets'
    body.dataset.view = next

    Object.entries(screens).forEach(([key, screen]) => {
      screen.hidden = key !== next
    })

    const inspector = next === 'inspector'
    const inventory = next === 'datasets'
    datasetsTools.hidden = inspector
    inspectorTools.hidden = !inspector
    globalSearch.disabled = !inventory
    globalSearch.placeholder = inventory ? 'Search datasets…' : 'Search available in Dataset inventory'
    topSearch.classList.toggle('is-disabled', !inventory)

    document.querySelectorAll('[data-nav]').forEach((item) => {
      const active = inspector ? item.dataset.nav === 'hive' : item.dataset.nav === 'datasets'
      item.classList.toggle('is-active', active)
      if (active) item.setAttribute('aria-current', 'page')
      else item.removeAttribute('aria-current')
    })

    setBreadcrumbs(next)
    document.title = next === 'datasets'
      ? 'Managed Datasets — PocketHive'
      : next === 'dataset'
        ? 'primary-test-entities — PocketHive'
        : 'service-simulation-01 Inspector — PocketHive'

    if (updateHash) {
      const hash = routeHash(next)
      if (window.location.hash !== hash) history.pushState(null, '', hash)
    }

    document.querySelector('.page-content')?.scrollTo({ top: 0, behavior: 'auto' })
    applyDemoState()

    if (moveFocus) {
      const heading = adverseStatePanel.hidden
        ? screens[next]?.querySelector('h1')
        : adverseStateTitle
      window.requestAnimationFrame(() => heading?.focus({ preventScroll: true }))
    }
  }

  function applyDemoState() {
    const state = demoStates[queryParams.get('state')]
    if (!state) {
      adverseStatePanel.hidden = true
      return
    }

    adverseStateKicker.textContent = state.kicker
    adverseStateTitle.textContent = state.title
    adverseStateDetail.textContent = state.detail
    adverseStateAction.textContent = state.action
    adverseStateOwner.textContent = state.owner
    adverseStateRunbookRef.textContent = state.runbook
    adverseStateRunbook.hidden = state.runbook === 'Not applicable'
    adverseStateRetry.hidden = !state.retry
    adverseStateRetry.disabled = state.retry && state.retryEnabled === false
    adverseStateRetry.textContent = state.retryEnabled === false ? 'Refresh disabled until 16:25:48 UTC' : 'Retry status read'
    adverseStateRetryBoundary.textContent = state.retryBoundary ?? ''
    adverseStateRetryBoundary.hidden = !state.retryBoundary
    adverseStateFeedback.textContent = ''
    adverseStatePanel.hidden = false
    globalSearch.disabled = true
    topSearch.classList.add('is-disabled')
    const preserveLastKnown = state.preserveLastKnown === true
    body.classList.toggle('has-historical-state', preserveLastKnown)
    Object.entries(screens).forEach(([key, screen]) => {
      screen.hidden = !(preserveLastKnown && key === 'datasets')
      screen.classList.toggle('is-historical', preserveLastKnown && key === 'datasets')
    })
    historicalScreenNote.hidden = !preserveLastKnown
    if (preserveLastKnown) {
      document.getElementById('observed-age').textContent = '42 sec ago'
      markInventoryFactsHistorical()
      screens.datasets.setAttribute('aria-describedby', 'historical-screen-note adverse-state-detail')
      screens.datasets.querySelectorAll('button, input, select').forEach((control) => {
        control.disabled = true
      })
    }
  }

  function bindNavigation(scope = document) {
    scope.querySelectorAll('[data-navigate]').forEach((control) => {
      if (control.dataset.navigationBound === 'true') return
      control.dataset.navigationBound = 'true'
      control.addEventListener('click', (event) => {
        event.preventDefault()
        setView(control.dataset.navigate)
      })
    })

    scope.querySelectorAll('[data-open-dataset]').forEach((control) => {
      if (control.dataset.datasetBound === 'true') return
      control.dataset.datasetBound = 'true'
      control.addEventListener('click', (event) => {
        event.preventDefault()
        setView('dataset')
      })
    })
  }

  function applyFilters() {
    const query = datasetSearch.value.trim().toLowerCase()
    const environment = environmentFilter.value
    const health = healthFilter.value
    const healthStates = health === 'all' ? [] : health.split(',')
    const fitness = fitnessFilter.value
    let count = 0

    const isVisible = (item) =>
      (!query || item.dataset.search.includes(query)) &&
      (environment === 'all' || item.dataset.environment === environment) &&
      (health === 'all' || healthStates.includes(item.dataset.health)) &&
      (fitness === 'all' || item.dataset.fitness === fitness)

    document.querySelectorAll('[data-dataset-row]').forEach((row) => {
      const visible = isVisible(row)

      row.hidden = !visible
      if (visible) count += 1
    })

    visibleCount.textContent = String(count)
    emptyResults.hidden = count !== 0
  }

  function setTab(tab, { focus = true, updateHash = true } = {}) {
    const tabList = tab.closest('[role="tablist"]')
    if (!tabList || !tab.hasAttribute('aria-controls')) return
    const tabs = Array.from(tabList.querySelectorAll('[role="tab"][aria-controls]'))
    tabs.forEach((item) => {
      const selected = item === tab
      item.setAttribute('aria-selected', String(selected))
      item.tabIndex = selected ? 0 : -1
      const panel = document.getElementById(item.getAttribute('aria-controls'))
      if (panel) panel.hidden = !selected
    })
    if (focus) {
      tab.focus({ preventScroll: true })
      tab.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' })
    }
    if (updateHash && body.dataset.view === 'dataset') {
      const hash = `#dataset/${tab.id.replace('tab-', '')}`
      if (window.location.hash !== hash) history.pushState(null, '', hash)
    }
  }

  function bindTabs() {
    document.querySelectorAll('[role="tablist"]').forEach((tabList) => {
      const tabs = Array.from(tabList.querySelectorAll('[role="tab"][aria-controls]'))
      tabs.forEach((tab, index) => {
        tab.addEventListener('click', () => setTab(tab))
        tab.addEventListener('keydown', (event) => {
          let targetIndex = null
          if (event.key === 'ArrowRight') targetIndex = (index + 1) % tabs.length
          if (event.key === 'ArrowLeft') targetIndex = (index - 1 + tabs.length) % tabs.length
          if (event.key === 'Home') targetIndex = 0
          if (event.key === 'End') targetIndex = tabs.length - 1
          if (targetIndex !== null) {
            event.preventDefault()
            setTab(tabs[targetIndex])
          }
        })
      })
    })

    document.querySelectorAll('[data-tab-target]').forEach((control) => {
      control.addEventListener('click', () => {
        const tab = document.getElementById(`tab-${control.dataset.tabTarget}`)
        if (tab) setTab(tab)
      })
    })
  }

  function runRefresh(button) {
    if (button.classList.contains('is-refreshing')) return
    const label = button.querySelector('span')
    const original = label?.textContent ?? 'Refresh'
    button.classList.add('is-refreshing')
    button.disabled = true
    if (label) label.textContent = 'Refreshing…'

    window.setTimeout(() => {
      button.classList.remove('is-refreshing')
      button.disabled = false
      if (label) label.textContent = 'Wireframe only'
      refreshAnnouncement.textContent = 'Planning wireframe only. Production Refresh performs a real conditional API request and preserves the original observation time until a response arrives.'
      window.setTimeout(() => {
        if (label) label.textContent = original
      }, 1200)
    }, 650)
  }

  function setSupplyPopover(popover, open) {
    const trigger = popover?.querySelector('.supply-trigger')
    const tooltip = popover?.querySelector('.supply-tooltip')
    if (!popover || !trigger || !tooltip) return
    popover.classList.toggle('is-open', open)
    trigger.setAttribute('aria-expanded', String(open))
    tooltip.setAttribute('aria-hidden', String(!open))
  }

  function closeSupplyPopovers(except = null) {
    document.querySelectorAll('[data-supply-popover]').forEach((popover) => {
      if (popover !== except) setSupplyPopover(popover, false)
    })
  }

  function bindSupplyPopovers() {
    document.querySelectorAll('[data-supply-popover]').forEach((popover) => {
      const trigger = popover.querySelector('.supply-trigger')
      if (!trigger) return
      trigger.addEventListener('click', (event) => {
        event.stopPropagation()
        const open = !popover.classList.contains('is-open')
        popover.classList.toggle('suppress-tooltip', !open)
        closeSupplyPopovers(popover)
        setSupplyPopover(popover, open)
      })
      trigger.addEventListener('blur', () => popover.classList.remove('suppress-tooltip'))
      popover.addEventListener('pointerleave', () => popover.classList.remove('suppress-tooltip'))
    })

    document.addEventListener('click', (event) => {
      if (!event.target.closest('[data-supply-popover]')) closeSupplyPopovers()
    })

    document.addEventListener('keydown', (event) => {
      if (event.key !== 'Escape') return
      const open = document.querySelector('[data-supply-popover].is-open')
      if (!open) return
      const trigger = open.querySelector('.supply-trigger')
      open.classList.add('suppress-tooltip')
      setSupplyPopover(open, false)
      trigger?.focus()
    })
  }

  bindNavigation()
  bindTabs()
  bindSupplyPopovers()

  document.getElementById('dataset-filters').addEventListener('submit', (event) => {
    event.preventDefault()
    applyFilters()
  })

  ;[datasetSearch, environmentFilter, healthFilter, fitnessFilter].forEach((control) => {
    control.addEventListener('input', applyFilters)
    control.addEventListener('change', applyFilters)
  })

  document.getElementById('clear-filters').addEventListener('click', () => {
    datasetSearch.value = ''
    globalSearch.value = ''
    environmentFilter.value = 'all'
    healthFilter.value = 'all'
    fitnessFilter.value = 'all'
    applyFilters()
    datasetSearch.focus()
  })

  globalSearch.addEventListener('input', () => {
    if (body.dataset.view !== 'datasets') return
    datasetSearch.value = globalSearch.value
    applyFilters()
  })

  window.addEventListener('keydown', (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault()
      globalSearch.focus()
    }
  })

  document.querySelectorAll('.refresh-button').forEach((button) => {
    button.addEventListener('click', () => runRefresh(button))
  })

  const themeToggle = document.querySelector('.theme-toggle')
  themeToggle.setAttribute('aria-label', root.dataset.theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme')
  themeToggle.addEventListener('click', (event) => {
    const button = event.currentTarget
    const light = root.dataset.theme !== 'light'
    root.dataset.theme = light ? 'light' : 'dark'
    button.setAttribute('aria-label', light ? 'Switch to dark theme' : 'Switch to light theme')
  })

  navCollapse.addEventListener('click', (event) => {
    if (event.currentTarget.disabled) return
    const expanded = shell.classList.toggle('nav-expanded')
    event.currentTarget.setAttribute('aria-expanded', String(expanded))
    event.currentTarget.setAttribute('aria-label', expanded ? 'Collapse navigation' : 'Expand navigation')
  })

  function syncNavigationMode() {
    if (compactNavigation.matches) {
      shell.classList.remove('nav-expanded')
      navCollapse.disabled = true
      navCollapse.setAttribute('aria-expanded', 'false')
      navCollapse.setAttribute('aria-label', 'Navigation is compact at this viewport')
      return
    }

    if (navCollapse.disabled) shell.classList.add('nav-expanded')
    navCollapse.disabled = false
    const expanded = shell.classList.contains('nav-expanded')
    navCollapse.setAttribute('aria-expanded', String(expanded))
    navCollapse.setAttribute('aria-label', expanded ? 'Collapse navigation' : 'Expand navigation')
  }

  compactNavigation.addEventListener('change', syncNavigationMode)
  syncNavigationMode()

  document.querySelectorAll('.runtime-item').forEach((item) => {
    item.addEventListener('click', () => {
      document.querySelectorAll('.runtime-item').forEach((candidate) => {
        const selected = candidate === item
        candidate.classList.toggle('selected', selected)
        candidate.setAttribute('aria-pressed', String(selected))
      })

      document.getElementById('runtime-id').textContent = item.dataset.runtimeId
      document.getElementById('runtime-kind').textContent = item.dataset.runtimeKind
      document.getElementById('runtime-role').textContent = item.dataset.runtimeRole
      document.getElementById('runtime-instance').textContent = item.dataset.runtimeInstance
      document.getElementById('runtime-declared-version').textContent = item.dataset.runtimeVersion
      document.getElementById('runtime-reported-version').textContent = item.dataset.runtimeReportedVersion ?? 'Not reported'
      document.getElementById('runtime-output').textContent = `Planning wireframe only. Production loads authorised redacted runtime evidence for ${item.dataset.runtimeInstance} from the existing Orchestrator runtime API.`
    })
  })

  document.querySelectorAll('[data-runtime-action]').forEach((button) => {
    button.addEventListener('click', () => {
      const output = document.getElementById('runtime-output')
      output.textContent = button.dataset.runtimeAction === 'logs'
        ? 'Planning wireframe only. Production Logs calls the existing authorised, bounded and redacted Orchestrator runtime logs endpoint.'
        : 'Planning wireframe only. Production Inspect calls the existing authorised and redacted Orchestrator runtime inspect endpoint.'
    })
  })

  let consumerPage = 1
  const consumerPrevious = document.getElementById('consumer-previous')
  const consumerNext = document.getElementById('consumer-next')
  const consumerPageSummary = document.getElementById('consumer-page-summary')
  const consumerPagePosition = document.getElementById('consumer-page-position')

  function setConsumerPage(page, announce = true) {
    consumerPage = Math.min(2, Math.max(1, page))
    document.querySelectorAll('[data-consumer-page]').forEach((panel) => {
      panel.hidden = Number(panel.dataset.consumerPage) !== consumerPage
    })
    consumerPrevious.disabled = consumerPage === 1
    consumerNext.disabled = consumerPage === 2
    consumerPageSummary.textContent = consumerPage === 1 ? 'Showing 1–3 of 6' : 'Showing 4–6 of 6'
    consumerPagePosition.textContent = `Page ${consumerPage} of 2`
    if (announce) refreshAnnouncement.textContent = consumerPageSummary.textContent
  }

  consumerPrevious.addEventListener('click', () => setConsumerPage(consumerPage - 1, false))
  consumerNext.addEventListener('click', () => setConsumerPage(consumerPage + 1, false))

  const requestedConsumerPage = Number(queryParams.get('consumerPage'))
  if (requestedConsumerPage === 2) setConsumerPage(2, false)

  const proofLevels = Object.freeze([
    'CONFIGURED',
    'SOURCED',
    'PERSISTED',
    'BROKER_ACCEPTED',
    'FINAL_MATERIALIZER_APPLIED',
    'TRAFFIC_ACTIVATED',
    'SELECTOR_APPLIED',
    'READY',
    'FLOW_PROVEN',
  ])
  if (new Set(proofLevels).size !== proofLevels.length) {
    throw new Error('Duplicate canonical proof level in planning fixture')
  }
  // Fixed illustrative product-response fixtures. No verdict is derived from
  // the fact cards or recomputed in this browser.
  const proofResponseFixtures = Object.freeze({
    CONFIGURED: {
      verdict: 'PASS',
      target: 'STATUS_SCOPE · ds-status-94f2',
      context: 'NONE',
      revision: 'Not applicable · FACT_PRECEDES_DATASET_REVISION · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:18 UTC',
      proofId: 'proof-scope-94f2-configured',
      required: ['CONFIGURED'],
      digest: '291bad5dcecf9748fa586eca1e9e7fd858ad0fe579fa84cf90d2b7993c1675cc',
      gaps: [],
    },
    SOURCED: {
      verdict: 'PASS',
      target: 'OPERATION · op-supply-7760 · PROVISION_NEW',
      context: 'NONE',
      revision: 'Not applicable · FACT_PRECEDES_DATASET_REVISION · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:12 UTC',
      proofId: 'proof-op-supply-7760-sourced',
      required: ['SOURCED'],
      digest: '17658ce5b44205cd48924883f8cd188d5490067d85dd7cbf1b52fbf8cc69b182',
      gaps: [],
    },
    PERSISTED: {
      verdict: 'PASS',
      target: 'OPERATION · op-supply-7760 · PROVISION_NEW',
      context: 'NONE',
      revision: '1842 · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:14 UTC',
      proofId: 'proof-op-supply-7760-persisted',
      required: ['PERSISTED'],
      digest: '5b5c617630929bf18c25a2d6dbcbe424a09d8e243e50af97fa250a8f3dd76570',
      gaps: [],
    },
    BROKER_ACCEPTED: {
      verdict: 'PASS',
      target: 'DELIVERY_ATTEMPT · ev-broker-09',
      context: 'NONE',
      revision: '1842 · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:15 UTC',
      proofId: 'proof-delivery-ev-broker-09-broker-accepted',
      required: ['BROKER_ACCEPTED'],
      digest: '4b0d0862fe4468ff7e991197971f6bcfdbcd06db7d61d742b00f5a2778a6db8c',
      gaps: [],
    },
    FINAL_MATERIALIZER_APPLIED: {
      verdict: 'FAIL',
      target: 'BINDING · bs-primary-1842',
      context: 'NONE',
      revision: '1842 · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:16 UTC',
      proofId: 'proof-primary-1842-final-materializer-applied',
      required: ['FINAL_MATERIALIZER_APPLIED'],
      digest: '31a8f68744c4824fcf7bc352cf3a908c694ea44781008883742044ce4edea1c8',
      gaps: [['FINAL_MATERIALIZER_INCOMPLETE', 'processor-12 has not applied candidate revision 1842']],
    },
    TRAFFIC_ACTIVATED: {
      verdict: 'PASS',
      target: 'BINDING · bs-primary-1842',
      context: 'NONE',
      revision: '1841 · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:17 UTC',
      proofId: 'proof-primary-1842-traffic-activated',
      required: ['TRAFFIC_ACTIVATED'],
      digest: '8e0a0270cad9604faa2b52f96786fbf63d6b28deba418930031cae5b1a6a6ce9',
      gaps: [],
    },
    SELECTOR_APPLIED: {
      verdict: 'PASS',
      target: 'BINDING · bs-primary-1842',
      context: 'NONE',
      revision: '1841 · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:17 UTC',
      proofId: 'proof-primary-1842-selector-applied',
      required: ['SELECTOR_APPLIED'],
      digest: 'c0e5dfbe30e3d0ea3be9722ed279ed07a0e83a1c1d79eea3698e0e3c412fb8c5',
      gaps: [],
    },
    READY: {
      verdict: 'FAIL',
      target: 'BINDING · bs-primary-1842',
      context: 'NONE',
      revision: '1842 · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:16 UTC',
      proofId: 'proof-primary-1842-ready',
      required: ['FINAL_MATERIALIZER_APPLIED', 'TRAFFIC_ACTIVATED', 'SELECTOR_APPLIED', 'READY'],
      digest: 'd13ec8128f03311b952dae912733c4772077108a4186cdf83d95429ccfbe3ea6',
      gaps: [
        ['FINAL_MATERIALIZER_INCOMPLETE', 'processor-12 has not applied candidate revision 1842'],
        ['CANDIDATE_NOT_ACTIVATED', 'candidate 1842 is not activated'],
        ['SELECTOR_REVISION_BEHIND', 'selectors remain constrained to activated revision 1841'],
        ['SUPPLY_BELOW_MINIMUM', 'eligible 4,820 is below minimum 5,000'],
        ['EVIDENCE_TEMPORARILY_UNAVAILABLE', 'current Fitness evidence is unavailable'],
      ],
    },
    FLOW_PROVEN: {
      verdict: 'UNKNOWN',
      target: 'BINDING · bs-primary-1842',
      context: 'TRANSACTION · txn-opaque-9ef2',
      revision: '1841 · revision scope ds-revision-6a21',
      validity: 'Observed 16:24:48 UTC · valid until 16:25:18 UTC',
      proofId: 'proof-primary-1842-flow-proven-txn9ef2',
      required: ['FLOW_PROVEN'],
      digest: 'aa7437a2970fd2574bd6a9b94fc6441a62c60f9b333e644ccad842cf7378db99',
      gaps: [['FLOW_EVIDENCE_NOT_YET_OBSERVED', 'the requested transaction has no accepted observation']],
    },
  })
  const proofForm = document.getElementById('proof-query-form')
  const proofLevel = document.getElementById('proof-level')
  const proofTarget = document.getElementById('proof-target')
  const proofReferenceKindField = document.getElementById('proof-reference-kind-field')
  const proofReferenceField = document.getElementById('proof-reference-field')
  const proofReferenceKind = document.getElementById('proof-reference-kind')
  const proofReference = document.getElementById('proof-reference')
  const proofReferenceError = document.getElementById('proof-reference-error')
  const proofStatus = document.getElementById('proof-query-status')
  const proofNotRequested = document.getElementById('proof-not-requested')
  const proofGaps = document.getElementById('proof-gaps')

  function setProofReferenceError(message = '') {
    proofReference.setCustomValidity(message)
    proofReference.setAttribute('aria-invalid', String(Boolean(message)))
    proofReferenceError.textContent = message
    proofReferenceError.hidden = !message
  }

  function syncProofQueryControls() {
    const flowRequested = proofLevel.value === 'FLOW_PROVEN'
    proofTarget.value = proofResponseFixtures[proofLevel.value].target
    proofReferenceKind.disabled = !flowRequested
    proofReference.disabled = !flowRequested
    proofReference.required = flowRequested
    proofReferenceKindField.hidden = !flowRequested
    proofReferenceField.hidden = !flowRequested
    proofForm.classList.toggle('has-flow-context', flowRequested)
    setProofReferenceError()
  }

  function setProofFactStatus(kind, status) {
    const fact = document.querySelector(`[data-proof-fact="${kind}"]`)
    if (!fact) return
    fact.classList.remove('pass', 'fail', 'unknown')
    fact.classList.add(status.toLowerCase())
    fact.querySelector('.evidence-symbol').textContent = status
  }

  function renderBindingFactContext(level) {
    const readyTarget = level === 'READY'
    setProofFactStatus('TRAFFIC_ACTIVATED', readyTarget ? 'FAIL' : 'PASS')
    setProofFactStatus('SELECTOR_APPLIED', readyTarget ? 'FAIL' : 'PASS')
    if (readyTarget) {
      document.getElementById('proof-traffic-fact').textContent = 'TRAFFIC_ACTIVATED · READY target binding bs-primary-1842 · revision scope ds-revision-6a21'
      document.getElementById('proof-traffic-revision').textContent = 'Required candidate revision 1842 · membership epoch 32 · activated revision 1841'
      document.getElementById('proof-traffic-evidence').textContent = 'Observed 16:24:47 UTC · valid until 16:25:17 UTC · reason CANDIDATE_NOT_ACTIVATED · evidence ev-activate-18'
      document.getElementById('proof-selector-fact').textContent = 'SELECTOR_APPLIED · READY target binding bs-primary-1842 · revision scope ds-revision-6a21'
      document.getElementById('proof-selector-revision').textContent = 'Required candidate revision 1842 · membership epoch 32 · selectors remain 12/12 on revision 1841'
      document.getElementById('proof-selector-evidence').textContent = 'Observed 16:24:47 UTC · valid until 16:25:17 UTC · reason SELECTOR_REVISION_BEHIND · evidence ev-select-18'
      return
    }
    document.getElementById('proof-traffic-fact').textContent = 'TRAFFIC_ACTIVATED · binding bs-primary-1842 · revision scope ds-revision-6a21'
    document.getElementById('proof-traffic-revision').textContent = 'Activated revision 1841 · membership epoch 32'
    document.getElementById('proof-traffic-evidence').textContent = 'Observed 16:24:47 UTC · valid until 16:25:17 UTC · reason codes none · evidence ev-activate-18'
    document.getElementById('proof-selector-fact').textContent = 'SELECTOR_APPLIED · binding bs-primary-1842 · revision scope ds-revision-6a21'
    document.getElementById('proof-selector-revision').textContent = 'Activated revision 1841 · membership epoch 32 · selectors 12/12'
    document.getElementById('proof-selector-evidence').textContent = 'Observed 16:24:47 UTC · valid until 16:25:17 UTC · reason codes none · evidence ev-select-18'
  }

  function renderReturnedProof(level) {
    const requestedIndex = proofLevels.indexOf(level)
    const fixture = proofResponseFixtures[level]
    document.querySelectorAll('[data-proof-fact]').forEach((fact) => {
      fact.hidden = Number(fact.dataset.proofOrder) > requestedIndex
    })

    const returnedVerdict = fixture.verdict
    document.getElementById('proof-id').textContent = fixture.proofId
    document.getElementById('proof-requested-level').textContent = level
    document.getElementById('proof-claim-target-result').textContent = fixture.target
    document.getElementById('proof-claim-context-result').textContent = fixture.context
    document.getElementById('proof-required-facts').textContent = fixture.required.join(' · ')
    document.getElementById('proof-authoritative-revision').textContent = fixture.revision
    document.getElementById('proof-validity').textContent = fixture.validity
    renderBindingFactContext(level)
    const digestElement = document.getElementById('proof-canonical-digest')
    digestElement.textContent = `sha256:${fixture.digest.slice(0, 8)}…${fixture.digest.slice(-8)}`
    document.getElementById('proof-canonical-digest-full').textContent = `sha256:${fixture.digest}`
    document.getElementById('proof-verdict').textContent = `Verdict: ${returnedVerdict}`
    document.getElementById('proof-verdict').className = returnedVerdict === 'PASS'
      ? 'status-pill pill-good'
      : returnedVerdict === 'FAIL'
        ? 'status-pill pill-bad'
        : 'status-pill pill-neutral'

    proofGaps.hidden = fixture.gaps.length === 0
    if (!proofGaps.hidden) {
      const codes = fixture.gaps.map(([code]) => code)
      const details = fixture.gaps.map(([, detail]) => detail)
      document.getElementById('proof-gap-count').textContent = `Explicit gaps (${codes.length})`
      document.getElementById('proof-gap-codes').textContent = codes.join(' · ')
      document.getElementById('proof-gap-detail').textContent = `${details.join('; ')}.`
    }
    if (level === 'FLOW_PROVEN') {
      document.getElementById('proof-flow-reference').textContent = `FLOW_PROVEN · transaction ref ${proofReference.value.trim()}`
    }
    proofNotRequested.hidden = requestedIndex === proofLevels.length - 1
    if (!proofNotRequested.hidden) {
      const omitted = proofLevels.slice(requestedIndex + 1).join(', ')
      const label = document.createElement('strong')
      label.textContent = 'Not requested for this proof:'
      proofNotRequested.replaceChildren(
        label,
        document.createTextNode(` ${omitted} ${omitted.includes(',') ? 'are' : 'is'} omitted because the requested level is ${level}.`),
      )
    }
    proofStatus.textContent = `Product proof ${level} loaded with returned verdict ${returnedVerdict}. Canonical fact statuses were not recalculated.`
  }

  proofLevel.addEventListener('change', syncProofQueryControls)
  proofReference.addEventListener('change', () => setProofReferenceError())
  proofForm.addEventListener('submit', (event) => {
    event.preventDefault()
    if (proofLevel.value === 'FLOW_PROVEN' && !proofReference.value.trim()) {
      setProofReferenceError('Select the exact transaction reference for FLOW_PROVEN.')
      proofStatus.textContent = 'FLOW_PROVEN was not loaded because the exact transaction reference is missing. The prior proof result remains unchanged.'
      proofReference.reportValidity()
      proofReference.focus()
      return
    }
    setProofReferenceError()
    renderReturnedProof(proofLevel.value)
    document.querySelector('.proof-result-heading')?.scrollIntoView({ block: 'nearest', behavior: 'auto' })
  })
  syncProofQueryControls()

  const requestedProofLevel = queryParams.get('proofLevel')
  if (proofLevels.includes(requestedProofLevel)) {
    proofLevel.value = requestedProofLevel
    proofReferenceKind.value = 'transaction'
    proofReference.value = queryParams.get('proofReference') ?? ''
    syncProofQueryControls()
    if (requestedProofLevel !== 'FLOW_PROVEN' || proofReference.value) renderReturnedProof(requestedProofLevel)
    if (requestedProofLevel === 'FLOW_PROVEN' && queryParams.get('proofValidation') === 'missing') {
      setProofReferenceError('Select the exact transaction reference for FLOW_PROVEN.')
      proofStatus.textContent = 'FLOW_PROVEN was not loaded because the exact transaction reference is missing. The prior proof result remains unchanged.'
    }
  }

  adverseStateRetry.addEventListener('click', () => {
    adverseStateFeedback.textContent = 'Planning fixture: no network request was sent. Production performs one real conditional status read and preserves the historical observation until a response is accepted.'
    refreshAnnouncement.textContent = adverseStateFeedback.textContent
  })

  adverseStateRunbook.addEventListener('click', () => {
    adverseStateFeedback.textContent = `Planning reference ${adverseStateRunbookRef.textContent}. Production opens only the server-authorised runbook.`
    refreshAnnouncement.textContent = adverseStateFeedback.textContent
  })

  function applyHashRoute(moveFocus = true) {
    const [view = 'datasets', tabName] = window.location.hash.slice(1).split('/')
    setView(view || 'datasets', false, moveFocus)
    if (view === 'dataset' && tabName) {
      const tab = document.getElementById(`tab-${tabName}`)
      if (tab) setTab(tab, { focus: false, updateHash: false })
    }
  }

  window.addEventListener('hashchange', () => applyHashRoute(true))

  if (queryParams.get('capture') === '1') {
    body.classList.add('capture-mode')
    if (window.screen.width <= 480) {
      body.classList.add('mobile-capture')
      root.style.setProperty('--capture-width', `${window.screen.width}px`)
    }
  }

  applyHashRoute(false)
  applyFilters()

  if (queryParams.get('focusCancel') === '1') {
    window.requestAnimationFrame(() => {
      const specimen = document.getElementById('cancel-specimen')
      specimen?.scrollIntoView({ block: 'start', behavior: 'auto' })
      specimen?.focus({ preventScroll: true })
    })
  }

  const requestedPopover = queryParams.get('tooltip')
  if (requestedPopover) {
    const popover = document.querySelector(`[data-supply-popover-id="${CSS.escape(requestedPopover)}"]`)
    if (popover) {
      setSupplyPopover(popover, true)
      if (queryParams.get('focusPopover') === '1') {
        window.requestAnimationFrame(() => {
          popover.scrollIntoView({ block: 'center', behavior: 'auto' })
          popover.querySelector('.supply-trigger')?.focus({ preventScroll: true })
        })
      }
    }
  }

  const requestedTab = queryParams.get('tab')
  if (requestedTab) {
    const tab = document.getElementById(`tab-${requestedTab}`)
    if (tab) {
      setTab(tab, { focus: false, updateHash: false })
      tab.blur()

      if (queryParams.get('focusPanel') === '1') {
        const panel = document.getElementById(tab.getAttribute('aria-controls'))
        window.requestAnimationFrame(() => {
          panel?.scrollIntoView({ block: 'start', behavior: 'auto' })
        })
      }
    }
  }
})()
