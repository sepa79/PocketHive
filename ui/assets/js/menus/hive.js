import { phShowPanel } from "../main.js";

/**
 * Create the Hive panel that renders service nodes and queue edges as an SVG graph.
 *
 * @returns {{ensureNode: Function, updateQueues: Function, rebuildEdgesFromQueues: Function, redrawHive: Function}}
 *          Methods for maintaining and redrawing the hive topology.
 */
export function initHiveMenu() {
  const HIVE_DEFAULT_HOLD = 15000;
  const hiveSvg = /** @type {SVGSVGElement|null} */(document.getElementById('hive-canvas'));
  const hiveHoldInput = /** @type {HTMLInputElement|null} */(document.getElementById('hive-hold'));
  const hiveClearBtn = document.getElementById('hive-clear');
  const hiveStats = document.getElementById('hive-stats');
  const hive = {
    nodes: /** @type {Record<string,{id:string,label:string,service:string,x:number,y:number,last:number,tps?:number}>} */({}),
    edges: /** @type {Array<{a:string,b:string}>} */([]),
    queues: /** @type {Record<string,{in:Set<string>, out:Set<string>}>} */({}),
    holdMs: HIVE_DEFAULT_HOLD
  };
  function setHoldMs() { if (hiveHoldInput) { const v = Math.max(1, Number(hiveHoldInput.value) || 3); hive.holdMs = v * 1000; } }
  if (hiveHoldInput) { hiveHoldInput.addEventListener('change', setHoldMs); setHoldMs(); }
  if (hiveClearBtn) {
    hiveClearBtn.addEventListener('click', () => {
      hive.nodes = {};
      hive.edges = [];
      redrawHive();
      if (hiveStats) hiveStats.textContent = '';
    });
  }
  function ensureNode(service) {
    if (!hiveSvg) return null;
    const id = service;
    if (!hive.nodes[id]) {
      const map = { generator: [140, 160], moderator: [480, 160], processor: [820, 160], sut: [1060, 160] };
      const pos = map[id] || [100 + Object.keys(hive.nodes).length * 140, 320];
      hive.nodes[id] = { id, label: id.charAt(0).toUpperCase() + id.slice(1), service: id, x: pos[0], y: pos[1], last: Date.now() };
      if (id === 'generator') { if (hive.nodes['moderator']) addEdge('generator', 'moderator'); }
      if (id === 'moderator') { if (hive.nodes['generator']) addEdge('generator', 'moderator'); if (hive.nodes['processor']) addEdge('moderator', 'processor'); }
      if (id === 'processor') { if (hive.nodes['moderator']) addEdge('moderator', 'processor'); if (!hive.nodes['sut']) { hive.nodes['sut'] = { id: 'sut', label: 'SUT', service: 'sut', x: 1060, y: 160, last: Date.now() }; addEdge('processor', 'sut'); } else { addEdge('processor', 'sut'); } }
      redrawHive();
    }
    return hive.nodes[id];
  }
  function addEdge(a, b) { if (a === b) return; if (!hive.edges.find(e => (e.a === a && e.b === b))) { hive.edges.push({ a, b }); } }
  function arr(x) { return Array.isArray(x) ? x : (x != null ? [x] : []); }
  function updateQueues(service, evt) {
    if (!evt) return false;
    let changed = false;
    const queues = evt.queues || {};
    const ins = Array.isArray(queues.in) ? queues.in : (evt.inQueue && evt.inQueue.name ? [evt.inQueue.name] : []);
    const outs = Array.isArray(queues.out) ? queues.out : arr(evt.publishes).filter(Boolean);
    const apply = (name, dir) => {
      const key = String(name);
      if (!hive.queues[key]) hive.queues[key] = { in: new Set(), out: new Set() };
      const set = dir === 'in' ? hive.queues[key].in : hive.queues[key].out;
      const before = set.size; set.add(service);
      if (set.size !== before) changed = true;
    };
    ins.forEach(q => apply(q, 'in'));
    outs.forEach(q => apply(q, 'out'));
    return changed;
  }
  function rebuildEdgesFromQueues() {
    const edges = [];
    for (const q of Object.values(hive.queues)) {
      for (const prod of q.out) { for (const cons of q.in) { if (prod !== cons) edges.push({ a: prod, b: cons }); } }
    }
    if (hive.nodes['processor']) edges.push({ a: 'processor', b: 'sut' });
    const uniq = [];
    for (const e of edges) { if (!uniq.find(x => x.a === e.a && x.b === e.b)) uniq.push(e); }
    hive.edges = uniq;
  }
  function pruneExpired() {
    const now = Date.now(); let changed = false;
    for (const k of Object.keys(hive.nodes)) {
      if (k === 'sut') continue;
      if (now - hive.nodes[k].last > hive.holdMs) { delete hive.nodes[k]; changed = true; }
    }
    if (changed) { hive.edges = hive.edges.filter(e => hive.nodes[e.a] && hive.nodes[e.b]); }
  }
  function redrawHive() {
    if (!hiveSvg) return;
    pruneExpired();
    const svg = hiveSvg;
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    for (const e of hive.edges) {
      const a = hive.nodes[e.a], b = hive.nodes[e.b]; if (!a || !b) continue;
      const ln = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      ln.setAttribute('x1', String(a.x)); ln.setAttribute('y1', String(a.y));
      ln.setAttribute('x2', String(b.x)); ln.setAttribute('y2', String(b.y));
      ln.setAttribute('stroke', 'rgba(255,255,255,0.6)');
      ln.setAttribute('stroke-width', '3');
      ln.setAttribute('stroke-linecap', 'round');
      svg.appendChild(ln);
    }
    for (const id of Object.keys(hive.nodes)) {
      const n = hive.nodes[id];
      const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
      g.setAttribute('transform', `translate(${n.x - 60},${n.y - 46})`);
      if (id !== 'sut') { g.style.cursor = 'pointer'; g.addEventListener('click', () => { if (phShowPanel) phShowPanel(id); }); }
      const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      rect.setAttribute('x', '0'); rect.setAttribute('y', '0');
      rect.setAttribute('width', '120'); rect.setAttribute('height', '92');
      rect.setAttribute('rx', '12');
      rect.setAttribute('fill', id === 'sut' ? 'rgba(3,169,244,0.2)' : 'rgba(255,255,255,0.08)');
      rect.setAttribute('stroke', 'rgba(255,255,255,0.5)');
      rect.setAttribute('stroke-width', '2');
      g.appendChild(rect);
      const title = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      title.setAttribute('x', '60'); title.setAttribute('y', '24');
      title.setAttribute('text-anchor', 'middle'); title.setAttribute('fill', '#ffffff');
      title.setAttribute('font-family', 'Inter, Segoe UI, Arial, sans-serif');
      title.setAttribute('font-size', '13');
      title.textContent = n.label.toUpperCase();
      g.appendChild(title);
      const tps = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      tps.setAttribute('x', '60'); tps.setAttribute('y', '42');
      tps.setAttribute('text-anchor', 'middle');
      tps.setAttribute('fill', 'rgba(255,255,255,0.8)');
      tps.setAttribute('font-family', 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace');
      tps.setAttribute('font-size', '12');
      tps.textContent = (typeof n.tps === 'number') ? (`TPS ${n.tps}`) : 'TPS –';
      g.appendChild(tps);
      if (id !== 'sut') {
        const ins = [], outs = [];
        for (const [qname, obj] of Object.entries(hive.queues)) {
          if (obj.in && obj.in.has(n.id)) ins.push(qname);
          if (obj.out && obj.out.has(n.id)) outs.push(qname);
        }
        const fmt = (arr) => { if (!arr.length) return '–'; const first = arr[0]; return arr.length > 1 ? (first + '…') : first; };
        const inTxt = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        inTxt.setAttribute('x', '60'); inTxt.setAttribute('y', '62');
        inTxt.setAttribute('text-anchor', 'middle');
        inTxt.setAttribute('fill', 'rgba(255,255,255,0.9)');
        inTxt.setAttribute('font-family', 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace');
        inTxt.setAttribute('font-size', '11');
        inTxt.textContent = `IN  ${fmt(ins)}`; if (ins.length) inTxt.setAttribute('title', ins.join(', ')); g.appendChild(inTxt);
        const outTxt = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        outTxt.setAttribute('x', '60'); outTxt.setAttribute('y', '78');
        outTxt.setAttribute('text-anchor', 'middle');
        outTxt.setAttribute('fill', 'rgba(255,255,255,0.9)');
        outTxt.setAttribute('font-family', 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace');
        outTxt.setAttribute('font-size', '11');
        outTxt.textContent = `OUT ${fmt(outs)}`; if (outs.length) outTxt.setAttribute('title', outs.join(', ')); g.appendChild(outTxt);
      }
      svg.appendChild(g);
    }
    if (hiveStats) {
      const count = Object.keys(hive.nodes).length - (hive.nodes['sut'] ? 1 : 0);
      const qCount = Object.keys(hive.queues).length;
      hiveStats.textContent = `components: ${Math.max(0, count)} | queues: ${qCount} | edges: ${hive.edges.length}`;
    }
  }
  return { ensureNode, updateQueues, rebuildEdgesFromQueues, redrawHive };
}
