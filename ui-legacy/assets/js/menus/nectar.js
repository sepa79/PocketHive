/**
 * Initialize the Nectar metrics panel with simple canvas-based charts.
 *
 * @param {{currentMetric: string}} appState Shared application state.
 * @returns {{addPoint: Function, updateTps: Function, refreshCharts: Function, updateMetricView: Function}}
 *          Methods for updating metrics and redrawing charts.
 */
export function initNectarMenu(appState) {
  const EVENT_LIMIT_DEFAULT = 600;
  const WINDOW_MS = 60000;
  const genEl = document.getElementById('gen');
  const modEl = document.getElementById('mod');
  const procEl = document.getElementById('proc');
  const postEl = document.getElementById('post');
  const chartsEl = document.getElementById('charts');
  const chartsTps = document.getElementById('charts-tps');
  const chartsLatency = document.getElementById('charts-latency');
  const chartsHops = document.getElementById('charts-hops');
  const chartsTitle = document.getElementById('charts-title');
  const canvases = {
    generator: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-gen')),
    moderator: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-mod')),
    processor: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-proc')),
    postprocessor: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-post')),
    latency: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-latency')),
    hops: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-hops'))
  };
  const series = { generator: [], moderator: [], processor: [], postprocessor: [], latency: [], hops: [] };
  let eventLimit = EVENT_LIMIT_DEFAULT;
  const rafPending = { generator:false, moderator:false, processor:false, postprocessor:false, latency:false, hops:false };

  const eventLimitInput = document.getElementById('event-limit');
  function trimSeries() {
    for (const key of Object.keys(series)) {
      const arr = series[key];
      if (arr.length > eventLimit) arr.splice(0, arr.length - eventLimit);
    }
  }
  if (eventLimitInput) {
    eventLimitInput.addEventListener('change', () => {
      const v = Number(eventLimitInput.value) || EVENT_LIMIT_DEFAULT;
      eventLimit = Math.max(10, v);
      trimSeries();
    });
  }

  function addPoint(svc, v) {
    const now = Date.now();
    const arr = series[svc];
    if (!arr) return;
    arr.push({ t: now, v: Number(v) || 0 });
    if (arr.length > eventLimit) arr.splice(0, arr.length - eventLimit);
    const cutoff = now - WINDOW_MS;
    while (arr.length && arr[0].t < cutoff) arr.shift();
    scheduleDraw(svc);
  }
  function scheduleDraw(svc) {
    if (!chartsEl || chartsEl.style.display === 'none') return;
    if (rafPending[svc]) return;
    rafPending[svc] = true;
    requestAnimationFrame(() => { rafPending[svc] = false; drawChart(svc); });
  }
  function drawChart(svc) {
    const cv = canvases[svc];
    if (!cv) return;
    const ctx = cv.getContext('2d'); if (!ctx) return;
    const w = cv.width, h = cv.height;
    ctx.clearRect(0,0,w,h);
    ctx.strokeStyle = 'rgba(255,255,255,0.2)';
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(40,10); ctx.lineTo(40,h-24); ctx.lineTo(w-6,h-24); ctx.stroke();
    const now = Date.now();
    const data = series[svc];
    if (!data.length) return;
    const xmin = now - WINDOW_MS, xmax = now;
    let ymin = 0, ymax = 1;
    for (const p of data) { if (p.v > ymax) ymax = p.v; }
    ymax = Math.max(1, ymax * 1.1);
    const xr = (x) => 40 + ((x - xmin) / (xmax - xmin)) * (w - 46);
    const yr = (y) => (h - 24) - ( y / (ymax - ymin) ) * (h - 34);
    ctx.strokeStyle = 'rgba(255,255,255,0.1)';
    for (let i=1;i<=3;i++){ const gy = 10 + i*(h-34)/4; ctx.beginPath(); ctx.moveTo(40,gy); ctx.lineTo(w-6,gy); ctx.stroke(); }
    ctx.fillStyle = 'rgba(255,255,255,0.7)'; ctx.font = '12px monospace';
    ctx.fillText(String(Math.round(ymax)), 6, 12);
    ctx.fillText('0', 28, h-24);
    const colors = { generator:'#4CAF50', moderator:'#FFC107', processor:'#03A9F4', postprocessor:'#FF5722', latency:'#E91E63', hops:'#9C27B0' };
    ctx.strokeStyle = colors[svc] || '#FFFFFF';
    ctx.lineWidth = 2;
    ctx.beginPath();
    let started=false;
    for (const p of data){ const x=xr(p.t), y=yr(p.v); if(!started){ ctx.moveTo(x,y); started=true; } else { ctx.lineTo(x,y); } }
    ctx.stroke();
  }

  function refreshCharts() {
    const metric = appState.currentMetric;
    if (metric === 'tps') { ['generator','moderator','processor','postprocessor'].forEach(s => drawChart(s)); }
    if (metric === 'latency') drawChart('latency');
    if (metric === 'hops') drawChart('hops');
  }

  function updateMetricView() {
    const metric = appState.currentMetric;
    if (chartsTps) chartsTps.style.display = metric === 'tps' ? 'grid' : 'none';
    if (chartsLatency) chartsLatency.style.display = metric === 'latency' ? 'block' : 'none';
    if (chartsHops) chartsHops.style.display = metric === 'hops' ? 'block' : 'none';
    if (chartsTitle) {
      chartsTitle.textContent = metric==='tps'
        ? 'RabbitMQ Buzz Stream — TPS (last 60s)'
        : metric==='latency'
          ? 'End-to-End Latency (last 60s)'
          : 'Per-Hop Count (last 60s)';
    }
    if (chartsEl && chartsEl.style.display !== 'none') refreshCharts();
  }

  function updateTps(svc, val) {
    const str = String(val);
    if (svc === 'generator' && genEl) genEl.textContent = str;
    if (svc === 'moderator' && modEl) modEl.textContent = str;
    if (svc === 'processor' && procEl) procEl.textContent = str;
    if (svc === 'postprocessor' && postEl) postEl.textContent = str;
  }

  return { addPoint, updateTps, refreshCharts, updateMetricView };
}
