// Main bootstrap for dynamic panels
import { setupStompClient } from './features/stompClient.js';
import { initPanel, showPanel } from './features/panelRenderer.js';

const panels = { generator: {}, moderator: {}, processor: {}, postprocessor: {} };
const instances = {};
const loaded = { generator: {}, moderator: {}, processor: {}, postprocessor: {} };

const modal = document.getElementById('panel-modal');
const modalBody = document.getElementById('panel-body');
const closeBtn = document.getElementById('panel-close');
if (closeBtn) closeBtn.addEventListener('click', () => { if (modal) modal.style.display = 'none'; });
if (modal) modal.addEventListener('click', e => { if (e.target === modal) modal.style.display = 'none'; });

function handleStatusFull(msg) {
  try {
    const body = JSON.parse(msg.body || '{}');
    const role = body.role || body.name || body.service;
    const inst = body.instance || body.id;
    if (role && inst) {
      instances[role] = { id: inst, name: body.name || role };
      if (!loaded[role][inst]) {
        loaded[role][inst] = true;
        // seed panel with the snapshot that announced the instance
        initPanel(panels, role, inst, body);
      }
    }
  } catch (e) {
    console.error(e);
  }
}

const client = setupStompClient(handleStatusFull);
window.phClient = client;

function phShowPanel(role) {
  showPanel(role, instances, panels, { modal, modalBody }, client);
}

export { phShowPanel };
