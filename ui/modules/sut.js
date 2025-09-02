export function renderSutPanel(containerEl, baseUrl) {
  const display = (baseUrl || '').replace(/\/$/, '');
  let adminUrl = '/__admin/';
  let reqUrl = '/__admin/requests?limit=25';
  try {
    const u = new URL(baseUrl || '/', window.location.href);
    const port = u.port ? `:${u.port}` : '';
    const origin = `${u.protocol}//${window.location.hostname}${port}`;
    adminUrl = new URL('/__admin/', origin).toString();
    reqUrl = new URL('/__admin/requests?limit=25', origin).toString();
  } catch {}
  containerEl.innerHTML = `
    <div class="card" data-role="sut">
      <h3>WireMock – <a href="${display}" target="_blank" rel="noopener">${display}</a></h3>
      <div class="tab-buttons">
        <button class="tab-btn tab-active" data-tab="admin">Admin</button>
        <button class="tab-btn" data-tab="requests">Requests</button>
      </div>
      <div class="tab-content" data-tab="admin">
        <div class="admin-output" style="padding:8px;max-height:60vh;overflow:auto;"></div>
      </div>
      <div class="tab-content" data-tab="requests" style="display:none">
        <div class="requests-output" style="padding:8px;max-height:60vh;overflow:auto;"></div>
      </div>
    </div>`;
  const tabBtns = containerEl.querySelectorAll('.tab-btn');
  const tabContents = containerEl.querySelectorAll('.tab-content');
  tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const tab = btn.getAttribute('data-tab');
      tabBtns.forEach(b => b.classList.toggle('tab-active', b === btn));
      tabContents.forEach(c => { c.style.display = c.getAttribute('data-tab') === tab ? 'block' : 'none'; });
      if (tab === 'requests') loadRequests();
    });
  });
  const adminEl = containerEl.querySelector('.admin-output');
  const reqEl = containerEl.querySelector('.requests-output');
  const fetchOpts = {
    mode: 'cors',
    credentials: 'omit',
    headers: {
      'Accept': 'application/json',
      'Access-Control-Allow-Origin': '*'
    }
  };
  async function loadAdmin() {
    if (!adminEl) return;
    adminEl.innerHTML = '<div style="color:#fff;">Loading…</div>';
    try {
      const res = await fetch(adminUrl, fetchOpts);
      if (!res.ok) { adminEl.innerHTML = `<div style="color:#f66;">HTTP ${res.status}</div>`; return; }
      const data = await res.json();
      const mappings = Array.isArray(data && data.mappings) ? data.mappings : [];
      if (!mappings.length) { adminEl.innerHTML = '<div style="color:#9aa0a6;">No mappings</div>'; return; }
      const table = document.createElement('table');
      table.style.width = '100%';
      table.style.borderCollapse = 'collapse';
      table.innerHTML = '<thead><tr><th style="text-align:left;padding:4px 8px;">Method</th><th style="text-align:left;padding:4px 8px;">URL</th><th style="text-align:left;padding:4px 8px;">Status</th></tr></thead>';
      const tbody = document.createElement('tbody');
      mappings.forEach(m => {
        const tr = document.createElement('tr');
        const method = m.request && m.request.method ? m.request.method : '';
        const url = m.request && (m.request.url || m.request.urlPath) ? (m.request.url || m.request.urlPath) : '';
        const status = m.response && m.response.status ? m.response.status : '';
        tr.innerHTML = `<td style="padding:4px 8px;">${method}</td><td style="padding:4px 8px;">${url}</td><td style="padding:4px 8px;">${status}</td>`;
        tbody.appendChild(tr);
      });
      table.appendChild(tbody);
      adminEl.innerHTML = '';
      adminEl.appendChild(table);
    } catch (e) {
      adminEl.innerHTML = `<div style="color:#f66;">Error: ${e && e.message ? e.message : 'fetch failed'}</div>`;
    }
  }
  let reqLoaded = false;
  async function loadRequests() {
    if (reqLoaded || !reqEl) return;
    reqLoaded = true;
    reqEl.innerHTML = '<div style="color:#fff;">Loading…</div>';
    try {
      const res = await fetch(reqUrl, fetchOpts);
      if (!res.ok) { reqEl.innerHTML = `<div style="color:#f66;">HTTP ${res.status}</div>`; return; }
      const data = await res.json();
      const requests = Array.isArray(data && data.requests) ? data.requests : [];
      if (!requests.length) { reqEl.innerHTML = '<div style="color:#9aa0a6;">No requests</div>'; return; }
      const table = document.createElement('table');
      table.style.width = '100%';
      table.style.borderCollapse = 'collapse';
      table.innerHTML = '<thead><tr><th style="text-align:left;padding:4px 8px;">Method</th><th style="text-align:left;padding:4px 8px;">URL</th><th style="text-align:left;padding:4px 8px;">Status</th></tr></thead>';
      const tbody = document.createElement('tbody');
      requests.forEach(r => {
        const tr = document.createElement('tr');
        const method = r.request && r.request.method ? r.request.method : '';
        const url = r.request && r.request.url ? r.request.url : '';
        const status = (r.response && r.response.status) || (r.responseDefinition && r.responseDefinition.status) || '';
        tr.innerHTML = `<td style="padding:4px 8px;">${method}</td><td style="padding:4px 8px;">${url}</td><td style="padding:4px 8px;">${status}</td>`;
        tbody.appendChild(tr);
      });
      table.appendChild(tbody);
      reqEl.innerHTML = '';
      reqEl.appendChild(table);
    } catch (e) {
      reqEl.innerHTML = `<div style="color:#f66;">Error: ${e && e.message ? e.message : 'fetch failed'}</div>`;
    }
  }
  loadAdmin();
}
