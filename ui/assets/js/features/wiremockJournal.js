/**
 * Attach a dropdown under the WireMock button and populate it with the
 * request journal from the local WireMock instance.
 */
export function initWiremockJournal() {
  const trigger = document.getElementById('link-wiremock');
  const panel = document.getElementById('wiremock-dropdown');
  if (!trigger || !panel) return;

  let open = false;
  const host = window.location.hostname;
  const url = `http://${host}:8080/__admin/requests?limit=25`;

  async function loadJournal() {
    panel.innerHTML = '<div style="padding:8px;color:#fff;">Loading…</div>';
    try {
      const res = await fetch(url);
      if (!res.ok) {
        panel.innerHTML = `<div style="padding:8px;color:#f66;">HTTP ${res.status}</div>`;
        return;
      }
      const data = await res.json();
      const requests = (data && Array.isArray(data.requests)) ? data.requests : [];
      if (!requests.length) {
        panel.innerHTML = '<div style="padding:8px;color:#9aa0a6;">No requests</div>';
        return;
      }
      const table = document.createElement('table');
      table.style.width = '100%';
      table.style.borderCollapse = 'collapse';
      table.innerHTML = '<thead><tr><th style="text-align:left;padding:4px 8px;">Method</th><th style="text-align:left;padding:4px 8px;">URL</th><th style="text-align:left;padding:4px 8px;">Status</th></tr></thead>';
      const tbody = document.createElement('tbody');
      requests.forEach(r => {
        const tr = document.createElement('tr');
        const method = r.request && r.request.method ? r.request.method : '';
        const urlCell = r.request && r.request.url ? r.request.url : '';
        const status = (r.response && r.response.status) || (r.responseDefinition && r.responseDefinition.status) || '';
        tr.innerHTML = `<td style="padding:4px 8px;">${method}</td><td style="padding:4px 8px;">${urlCell}</td><td style="padding:4px 8px;">${status}</td>`;
        tbody.appendChild(tr);
      });
      table.appendChild(tbody);
      panel.innerHTML = '';
      panel.appendChild(table);
    } catch (e) {
      panel.innerHTML = `<div style="padding:8px;color:#f66;">Error: ${e && e.message ? e.message : 'fetch failed'}</div>`;
    }
  }

  const toggle = (e) => {
    e.preventDefault();
    e.stopPropagation();
    open = !open;
    panel.style.display = open ? 'block' : 'none';
    if (open) loadJournal();
  };

  trigger.addEventListener('click', toggle);
  document.addEventListener('click', (e) => {
    if (open && !panel.contains(e.target) && e.target !== trigger) {
      open = false;
      panel.style.display = 'none';
    }
  });
}

