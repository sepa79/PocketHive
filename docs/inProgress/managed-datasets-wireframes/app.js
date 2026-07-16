(() => {
  const body = document.body
  const root = document.documentElement
  const queryParams = new URLSearchParams(window.location.search)
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
  const supplyFilter = document.getElementById('supply-filter')
  const fitnessFilter = document.getElementById('fitness-filter')
  const visibleCount = document.getElementById('visible-count')
  const emptyResults = document.getElementById('empty-results')
  const refreshAnnouncement = document.getElementById('refresh-announcement')
  const navCollapse = document.getElementById('nav-collapse')
  const compactNavigation = window.matchMedia('(max-width: 1100px)')

  const viewCopy = {
    datasets: ['Datasets'],
    dataset: ['Datasets', 'authorisation-instruments'],
    inspector: ['Hive', 'payment-traffic-01', 'Inspector'],
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

  function setView(view, updateHash = true) {
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
        ? 'authorisation-instruments — PocketHive'
        : 'payment-traffic-01 Inspector — PocketHive'

    if (updateHash) {
      const hash = `#${next}`
      if (window.location.hash !== hash) history.pushState(null, '', hash)
    }

    document.querySelector('.page-content')?.scrollTo({ top: 0, behavior: 'auto' })
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
    const supply = supplyFilter.value
    const fitness = fitnessFilter.value
    let count = 0

    const isVisible = (item) =>
      (!query || item.dataset.search.includes(query)) &&
      (environment === 'all' || item.dataset.environment === environment) &&
      (supply === 'all' || item.dataset.supply === supply) &&
      (fitness === 'all' || item.dataset.fitness === fitness)

    document.querySelectorAll('[data-dataset-row]').forEach((row) => {
      const visible = isVisible(row)

      row.hidden = !visible
      if (visible) count += 1
    })

    document.querySelectorAll('[data-dataset-card]').forEach((card) => {
      card.hidden = !isVisible(card)
    })

    visibleCount.textContent = String(count)
    emptyResults.hidden = count !== 0
  }

  function setTab(tab) {
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
    tab.focus({ preventScroll: true })
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

  ;[datasetSearch, environmentFilter, supplyFilter, fitnessFilter].forEach((control) => {
    control.addEventListener('input', applyFilters)
    control.addEventListener('change', applyFilters)
  })

  document.getElementById('clear-filters').addEventListener('click', () => {
    datasetSearch.value = ''
    globalSearch.value = ''
    environmentFilter.value = 'all'
    supplyFilter.value = 'all'
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

  document.querySelector('.theme-toggle').addEventListener('click', (event) => {
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

  window.addEventListener('hashchange', () => setView(window.location.hash.slice(1), false))

  if (queryParams.get('capture') === '1') {
    body.classList.add('capture-mode')
    if (window.screen.width <= 480) {
      body.classList.add('mobile-capture')
      root.style.setProperty('--capture-width', `${window.screen.width}px`)
    }
  }

  setView(window.location.hash.slice(1) || 'datasets', false)
  applyFilters()

  const requestedPopover = queryParams.get('tooltip')
  if (requestedPopover) {
    const popover = document.querySelector(`[data-supply-popover-id="${CSS.escape(requestedPopover)}"]`)
    if (popover) setSupplyPopover(popover, true)
  }

  const requestedTab = queryParams.get('tab')
  if (requestedTab) {
    const tab = document.getElementById(`tab-${requestedTab}`)
    if (tab) {
      setTab(tab)
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
