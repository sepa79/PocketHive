/**
 * Initialize the Buzz/Hive/Nectar navigation tabs.
 *
 * @param {() => void} redrawHive Callback to redraw the hive graph when its tab is activated.
 */
export function initTabs(redrawHive) {
  const tabBuzz = document.getElementById('tab-buzz');
  const tabHive = document.getElementById('tab-hive');
  const tabNectar = document.getElementById('tab-nectar');
  const viewBuzz = document.getElementById('view-buzz');
  const viewHive = document.getElementById('view-hive');
  const viewNectar = document.getElementById('view-nectar');
  const hiveSvg = document.getElementById('hive-canvas');
  const activate = (which) => {
    if (viewBuzz) viewBuzz.style.display = (which === 'buzz') ? 'block' : 'none';
    if (viewHive) viewHive.style.display = (which === 'hive') ? 'block' : 'none';
    if (viewNectar) viewNectar.style.display = (which === 'nectar') ? 'block' : 'none';
    if (tabBuzz) tabBuzz.classList.toggle('tab-active', which === 'buzz');
    if (tabHive) tabHive.classList.toggle('tab-active', which === 'hive');
    if (tabNectar) tabNectar.classList.toggle('tab-active', which === 'nectar');
    if (which === 'hive' && hiveSvg) redrawHive();
  };
  if (tabBuzz) tabBuzz.addEventListener('click', () => activate('buzz'));
  if (tabHive) tabHive.addEventListener('click', () => activate('hive'));
  if (tabNectar) tabNectar.addEventListener('click', () => activate('nectar'));
  activate('buzz');
}
