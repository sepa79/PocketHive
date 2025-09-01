export function initPanel(panels, role, id) {
  return import(`../../modules/${role}.js`)
    .then(mod => {
      const fn = mod[`render${role.charAt(0).toUpperCase() + role.slice(1)}Panel`];
      if (typeof fn === 'function') {
        const el = document.createElement('div');
        panels[role][id] = el;
        fn(el, id);
      }
    })
    .catch(err => console.error('module load', err));
}

export function showPanel(role, instances, panels, modalRefs, client) {
  const { modal, modalBody, modalTitle } = modalRefs;
  const inst = instances[role];
  if (!inst || !modal || !modalBody || !modalTitle) return;
  const el = panels[role] && panels[role][inst.id];
  if (!el) return;
  modalBody.innerHTML = '';
  modalBody.appendChild(el);
  modalTitle.textContent = `${inst.name || role} (${inst.id})`;
  modal.style.display = 'flex';
  try {
    const payload = { type: 'status-request', role, instance: inst.id };
    client.publish({ destination: `/exchange/ph.control/sig.status-request.${role}.${inst.id}`, body: JSON.stringify(payload) });
  } catch (err) {
    console.error('status-request', err);
  }
}
