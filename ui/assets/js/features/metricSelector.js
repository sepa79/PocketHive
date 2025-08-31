/**
 * Initialize dropdown controls for selecting which metric chart to display.
 *
 * @param {{currentMetric: string}} state Global state holding the current metric key.
 * @param {() => void} updateMetricView Called when the metric changes to refresh charts.
 */
export function initMetricSelector(state, updateMetricView) {
  const btn = document.getElementById('metric-btn');
  const dd = document.getElementById('metric-dropdown');
  if (!btn || !dd) return;
  let open = false;
  const toggle = (e) => { e && e.stopPropagation(); open = !open; dd.style.display = open ? 'block' : 'none'; };
  btn.addEventListener('click', toggle);
  document.addEventListener('click', (e) => {
    if (open && !dd.contains(e.target) && e.target !== btn) {
      open = false;
      dd.style.display = 'none';
    }
  });
  dd.querySelectorAll('button[data-metric]').forEach((item) => {
    item.addEventListener('click', () => {
      const metric = item.getAttribute('data-metric') || 'tps';
      state.currentMetric = metric;
      btn.textContent = item.textContent || 'TPS';
      open = false;
      dd.style.display = 'none';
      updateMetricView();
    });
  });
  updateMetricView();
}
