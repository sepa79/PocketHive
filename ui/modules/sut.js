export function renderSutPanel(containerEl, baseUrl) {
  const display = (baseUrl || '').replace(/\/$/, '');
  const adminUrl = '/wiremock/__admin';
  const reqUrl = '/wiremock/__admin/requests?limit=25';
  containerEl.innerHTML = `
    <div class="card" data-role="sut">
      <h3>WireMock – <a href="${display}" target="_blank" rel="noopener">${display}</a></h3>
      <div class="tab-buttons">
        <button class="tab-btn tab-active" data-tab="admin">Admin</button>
        <button class="tab-btn" data-tab="requests">Requests</button>
      </div>
      <div class="tab-content" data-tab="admin">
        <pre class="admin-output" style="white-space:pre-wrap;word-break:break-all;padding:8px;max-height:60vh;overflow:auto;background:#1e1e1e;color:#fff;"></pre>
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
  async function loadAdmin() {
    if (!adminEl) return;
    adminEl.textContent = 'Loading…';
    try {
      const res = await fetch(adminUrl);
      if (!res.ok) { adminEl.textContent = `HTTP ${res.status}`; return; }
      const data = await res.json();
      adminEl.textContent = JSON.stringify(data, null, 2);
    } catch (e) {
      adminEl.textContent = `Error: ${e && e.message ? e.message : 'fetch failed'}`;
    }
  }
  let reqLoaded = false;
  async function loadRequests() {
    if (reqLoaded || !reqEl) return;
    reqLoaded = true;
    reqEl.innerHTML = '<div style="color:#fff;">Loading…</div>';
    try {
      const res = await fetch(reqUrl);
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
