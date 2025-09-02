/**
 * Mount the connection form inside a dropdown triggered by the WS status icon.
 * Rearranges existing DOM nodes and wires click/keyboard handlers for toggling.
 */
export function initConnectionDropdown() {
  const host = document.getElementById('conn-dropdown');
  const trigger = document.getElementById('status-ws') || document.getElementById('ph-conn-btn');
  const controls = document.querySelector('.controls');
  if (!host || !trigger || !controls) return;
  host.appendChild(controls);
  controls.style.display = 'block';
  controls.style.display = 'grid';
  controls.style.gridTemplateColumns = '1fr 120px';
  controls.style.gap = '8px';
  const url = document.getElementById('wsurl');
  if (url) url.style.gridColumn = '1 / span 2';
  const stateLbl = document.getElementById('state');
  if (stateLbl) {
    stateLbl.style.gridColumn = '1 / span 2';
    stateLbl.style.alignSelf = 'center';
    stateLbl.style.color = '#9aa0a6';
  }
  let open = false;
  const toggle = (e) => { e && e.stopPropagation(); open = !open; host.style.display = open ? 'block' : 'none'; };
  trigger.addEventListener('click', toggle);
  trigger.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(e); } });
  document.addEventListener('click', (e) => { if (open && !host.contains(e.target) && e.target !== trigger) { open = false; host.style.display = 'none'; } });
}
