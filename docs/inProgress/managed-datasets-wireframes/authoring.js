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

  function showView(requested) {
    const view = Object.hasOwn(views, requested) ? requested : 'inventory'
    Object.entries(views).forEach(([key, element]) => { element.hidden = key !== view })
    document.querySelectorAll('.route-button').forEach(button => button.classList.toggle('active', button.dataset.view === view))
    document.getElementById('breadcrumb-current').textContent = labels[view][0]
    document.getElementById('context-chip').textContent = labels[view][1]
    document.title = `PocketHive — ${labels[view][0]} authoring wireframe`
  }

  document.querySelectorAll('.route-button').forEach(button => button.addEventListener('click', () => {
    window.location.hash = button.dataset.view
  }))
  document.querySelectorAll('[data-open-view]').forEach(button => button.addEventListener('click', () => {
    window.location.hash = button.dataset.openView
  }))
  window.addEventListener('hashchange', () => showView(window.location.hash.slice(1)))
  showView(window.location.hash.slice(1))
})()
