// Minimal STOMP over WebSocket client for RabbitMQ Web-STOMP
(function(){
  const PH_CONN_KEY = 'pockethive.conn';
  const PH_CFG_URL = '/config.json';
  let PH_CONFIG = null;
  let DEMO = false;
  const qs = (id) => document.getElementById(id);
  const elUrl = qs('wsurl');
  const elUser = qs('login');
  const elPass = qs('pass');
  const btn = qs('connect');
  const state = qs('state');
  const logEl = qs('log');
  const sysEl = qs('syslog');
  const genEl = qs('gen');
  const modEl = qs('mod');
  const procEl = qs('proc');
  const uiStatus = qs('status-ui');
  const connBtn = qs('ph-conn-btn');
  if(connBtn) connBtn.dataset.state = 'searching';
  let uiHealth = 'checking';
  let wsHealth = 'searching';
  const chartsEl = qs('charts');
  const toggleChartsBtn = qs('toggle-charts');
  const metricBtn = qs('metric-btn');
  const metricDropdown = qs('metric-dropdown');
  let currentMetric = 'tps';
  const chartsTps = qs('charts-tps');
  const chartsLatency = qs('charts-latency');
  const chartsHops = qs('charts-hops');
  const chartsTitle = qs('charts-title');
  const broadcastBtn = qs('broadcast-status');
  const radarBtn = qs('radar-btn');
  const canvases = {
    generator: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-gen')),
    moderator: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-mod')),
    processor: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-proc')),
    latency: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-latency')),
    hops: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-hops'))
  };
  const LOG_LIMIT_DEFAULT = 100;
  const SYSLOG_LIMIT_DEFAULT = 100;
  const EVENT_LIMIT_DEFAULT = 600;
  let logLimit = LOG_LIMIT_DEFAULT;
  let sysLogLimit = SYSLOG_LIMIT_DEFAULT;
  let eventLimit = EVENT_LIMIT_DEFAULT;
  const logLines = [];
  const sysLines = [];
  const topicLines = [];
  // Logging toggles
  const LOG_CTRL_RAW = true; // show raw control payloads in Event Log
  const LOG_STOMP_DEBUG = true; // STOMP frame debug to System Logs
  // Swarm view elements
  const tabControl = document.getElementById('tab-control');
  const tabHive = document.getElementById('tab-hive');
  const viewControl = document.getElementById('view-control');
  const viewHive = document.getElementById('view-hive');
  const swarmSvg = /** @type {SVGSVGElement|null} */(document.getElementById('swarm-canvas'));
  const swarmHoldInput = /** @type {HTMLInputElement|null} */(document.getElementById('swarm-hold'));
  const swarmClearBtn = document.getElementById('swarm-clear');
  const swarmStats = document.getElementById('swarm-stats');
  const series = { generator: [], moderator: [], processor: [], latency: [], hops: [] }; // {t:number,v:number}
  const WINDOW_MS = 60_000; // 60s window
  let rafPending = { generator:false, moderator:false, processor:false, latency:false, hops:false };
  const HAS_CONN = !!(elUrl && btn);
  const logLimitInput = qs('log-limit');
  if(logLimitInput){
    logLimitInput.addEventListener('change', ()=>{
      const v = Number(logLimitInput.value) || LOG_LIMIT_DEFAULT;
      logLimit = Math.min(500, Math.max(10, v));
      logLimitInput.value = String(logLimit);
      if(logLines.length>logLimit){ logLines.splice(0, logLines.length - logLimit); if(logEl) logEl.textContent = logLines.join('\n'); }
      if(topicLines.length>logLimit){ topicLines.splice(0, topicLines.length - logLimit); const tl=document.getElementById('topic-log'); if(tl) tl.textContent = topicLines.join('\n'); }
    });
  }
  const sysLimitInput = qs('syslog-limit');
  if(sysLimitInput){
    sysLimitInput.addEventListener('change', ()=>{
      const v = Number(sysLimitInput.value) || SYSLOG_LIMIT_DEFAULT;
      sysLogLimit = Math.min(500, Math.max(10, v));
      sysLimitInput.value = String(sysLogLimit);
      if(sysLines.length>sysLogLimit){ sysLines.splice(0, sysLines.length - sysLogLimit); if(sysEl) sysEl.textContent = sysLines.join('\n'); }
    });
  }
  const eventLimitInput = qs('event-limit');
  if(eventLimitInput){
    eventLimitInput.addEventListener('change', ()=>{
      const v = Number(eventLimitInput.value) || EVENT_LIMIT_DEFAULT;
      eventLimit = Math.max(10, v);
      trimSeries();
    });
  }

  // Tabs handling
  (function(){
    if(!tabControl || !tabHive || !viewControl || !viewHive) return;
    const activate = (which)=>{
      if(which==='control'){
        viewControl.style.display='block'; viewHive.style.display='none';
        tabControl.classList.add('tab-active'); tabHive.classList.remove('tab-active');
      } else {
        viewControl.style.display='none'; viewHive.style.display='block';
        tabControl.classList.remove('tab-active'); tabHive.classList.add('tab-active');
      }
    };
    tabControl.addEventListener('click', ()=> activate('control'));
    tabHive.addEventListener('click', ()=> activate('hive'));
    activate('control');
  })();

  // Hive sub-tabs handling
  (function(){
    const tabAll = document.getElementById('hive-tab-all');
    const tabSwarm = document.getElementById('hive-tab-swarm');
    const tabSwarmlets = document.getElementById('hive-tab-swarmlets');
    const viewAll = document.getElementById('hive-view-all');
    const viewSwarm = document.getElementById('hive-view-swarm');
    const viewSwarmlets = document.getElementById('hive-view-swarmlets');
    if(!tabAll || !tabSwarm || !tabSwarmlets || !viewAll || !viewSwarm || !viewSwarmlets) return;
    const activate = (which)=>{
      viewAll.style.display = which==='all'? 'block':'none';
      viewSwarm.style.display = which==='swarm'? 'block':'none';
      viewSwarmlets.style.display = which==='swarmlets'? 'block':'none';
      tabAll.classList.toggle('tab-active', which==='all');
      tabSwarm.classList.toggle('tab-active', which==='swarm');
      tabSwarmlets.classList.toggle('tab-active', which==='swarmlets');
      if(which==='swarm' && swarmSvg) redrawSwarm();
    };
    tabAll.addEventListener('click', ()=> activate('all'));
    tabSwarm.addEventListener('click', ()=> activate('swarm'));
    tabSwarmlets.addEventListener('click', ()=> activate('swarmlets'));
    activate('all');
  })();

  // Log tabs handling (Control vs Topic)
  (function(){
    const tCtrl = document.getElementById('log-tab-control');
    const tTop = document.getElementById('log-tab-topic');
    const vCtrl = document.getElementById('log-control');
    const vTop = document.getElementById('log-topic');
    if(!tCtrl || !tTop || !vCtrl || !vTop) return;
    const set = (which)=>{
      if(which==='control'){
        vCtrl.style.display='block'; vTop.style.display='none'; tCtrl.classList.add('tab-active'); tTop.classList.remove('tab-active');
      } else {
        vCtrl.style.display='none'; vTop.style.display='block'; tCtrl.classList.remove('tab-active'); tTop.classList.add('tab-active');
      }
    };
    tCtrl.addEventListener('click', ()=> set('control'));
    tTop.addEventListener('click', ()=> set('topic'));
    set('control');
  })();

  // Topic sniffer (subscribe to any RK on control exchange)
  (function(){
    const subBtn = document.getElementById('topic-sub');
    const unsubBtn = document.getElementById('topic-unsub');
    const rkInput = /** @type {HTMLInputElement|null} */(document.getElementById('topic-rk'));
    const log = document.getElementById('topic-log');
    let sub = null;
    function logLine(s){
      if(!log) return;
      const ts=new Date().toISOString();
      topicLines.push(`[${ts}] ${s}`);
      if(topicLines.length>logLimit) topicLines.splice(0, topicLines.length - logLimit);
      log.textContent = topicLines.join('\n');
      log.scrollTop = log.scrollHeight;
    }
    if(!subBtn || !unsubBtn || !rkInput) return;
    subBtn.addEventListener('click', ()=>{
      if(!client || !connected){ appendSys('[CTRL] Topic subscribe aborted: not connected'); return; }
      const rk = (rkInput.value || 'ev.#').trim();
      const dest = '/exchange/ph.control/' + rk;
      try{
        if(sub) { try{ sub.unsubscribe(); }catch{} sub=null; }
        // eslint-disable-next-line no-undef
        sub = client.subscribe(dest, (message)=>{ const b=message.body||''; logLine(`${message.headers.destination||''} ${b}`); }, { ack:'auto' });
        appendSys(`[CTRL] SUB TOPIC ${dest}`);
      }catch(e){ appendSys('Topic subscribe error: ' + (e && e.message ? e.message : String(e))); }
    });
    unsubBtn.addEventListener('click', ()=>{ if(sub){ try{ sub.unsubscribe(); appendSys('[CTRL] UNSUB TOPIC'); }catch{} sub=null; } });
  })();

  // Swarm graph state
  const SWARM_DEFAULT_HOLD = 15_000; // 3x default 5s schedule (status.delta)
  const swarm = {
    nodes: /** @type {Record<string,{id:string,label:string,service:string,x:number,y:number,last:number,tps?:number}>} */({}),
    edges: /** @type {Array<{a:string,b:string}>} */([]),
    queues: /** @type {Record<string,{in:Set<string>, out:Set<string>}>} */({}),
    holdMs: SWARM_DEFAULT_HOLD
  };
  function setHoldMs(){ if(swarmHoldInput){ const v = Math.max(1, Number(swarmHoldInput.value)||3); swarm.holdMs = v*1000; } }
  if(swarmHoldInput){ swarmHoldInput.addEventListener('change', setHoldMs); setHoldMs(); }
  if(swarmClearBtn){ swarmClearBtn.addEventListener('click', ()=>{ swarm.nodes={}; swarm.edges=[]; redrawSwarm(); if(swarmStats) swarmStats.textContent=''; }); }
  function ensureNode(service){
    if(!swarmSvg) return null;
    const id = service;
    if(!swarm.nodes[id]){
      // simple layout: fixed columns
      const map = { generator: [140, 160], moderator:[480,160], processor:[820,160], sut:[1060,160] };
      const pos = map[id] || [100+Object.keys(swarm.nodes).length*140, 320];
      swarm.nodes[id] = { id, label: id.charAt(0).toUpperCase()+id.slice(1), service:id, x: pos[0], y: pos[1], last: Date.now() };
      // edges
      if(id==='generator'){ if(swarm.nodes['moderator']) addEdge('generator','moderator'); }
      if(id==='moderator'){ if(swarm.nodes['generator']) addEdge('generator','moderator'); if(swarm.nodes['processor']) addEdge('moderator','processor'); }
      if(id==='processor'){ if(swarm.nodes['moderator']) addEdge('moderator','processor'); if(!swarm.nodes['sut']) { swarm.nodes['sut']={id:'sut',label:'SUT',service:'sut',x:1060,y:160,last:Date.now()}; addEdge('processor','sut'); } else { addEdge('processor','sut'); } }
      redrawSwarm();
    }
    return swarm.nodes[id];
  }
  function addEdge(a,b){ if(a===b) return; if(!swarm.edges.find(e=> (e.a===a && e.b===b))){ swarm.edges.push({a,b}); } }
  function arr(x){ return Array.isArray(x)? x : (x!=null? [x] : []); }
  function updateQueues(service, queuesObj){
    if(!queuesObj) return false;
    let changed=false;
    const ins = arr(queuesObj.in).concat(arr(queuesObj.inQueues)).filter(Boolean);
    const outs = arr(queuesObj.out).concat(arr(queuesObj.outQueues)).filter(Boolean);
    const apply = (name, dir)=>{
      const key = String(name);
      if(!swarm.queues[key]) swarm.queues[key] = { in:new Set(), out:new Set() };
      const set = dir==='in'? swarm.queues[key].in : swarm.queues[key].out;
      const before = set.size; set.add(service);
      if(set.size!==before) changed=true;
    };
    ins.forEach(q=> apply(q,'in'));
    outs.forEach(q=> apply(q,'out'));
    return changed;
  }
  function rebuildEdgesFromQueues(){
    const edges=[];
    for(const q of Object.values(swarm.queues)){
      for(const prod of q.out){ for(const cons of q.in){ if(prod!==cons) edges.push({a:prod, b:cons}); } }
    }
    // include processor→sut if processor exists
    if(swarm.nodes['processor']) edges.push({a:'processor', b:'sut'});
    // de-dup
    const uniq=[]; for(const e of edges){ if(!uniq.find(x=> x.a===e.a && x.b===e.b)) uniq.push(e); }
    swarm.edges = uniq;
  }
  function pruneExpired(){ const now=Date.now(); let changed=false; for(const k of Object.keys(swarm.nodes)){ if(k==='sut') continue; if(now - swarm.nodes[k].last > swarm.holdMs){ delete swarm.nodes[k]; changed=true; } } if(changed) { // rebuild edges
      swarm.edges = swarm.edges.filter(e=> swarm.nodes[e.a] && swarm.nodes[e.b]);
    }
  }
  function redrawSwarm(){ if(!swarmSvg) return; pruneExpired(); const svg=swarmSvg; while(svg.firstChild) svg.removeChild(svg.firstChild);
    // draw edges
    for(const e of swarm.edges){ const a=swarm.nodes[e.a], b=swarm.nodes[e.b]; if(!a||!b) continue; const ln=document.createElementNS('http://www.w3.org/2000/svg','line'); ln.setAttribute('x1', String(a.x)); ln.setAttribute('y1', String(a.y)); ln.setAttribute('x2', String(b.x)); ln.setAttribute('y2', String(b.y)); ln.setAttribute('stroke','rgba(255,255,255,0.6)'); ln.setAttribute('stroke-width','3'); ln.setAttribute('stroke-linecap','round'); svg.appendChild(ln); }
    // draw nodes
    for(const id of Object.keys(swarm.nodes)){
      const n=swarm.nodes[id]; const g=document.createElementNS('http://www.w3.org/2000/svg','g'); g.setAttribute('transform',`translate(${n.x-60},${n.y-46})`);
      const rect=document.createElementNS('http://www.w3.org/2000/svg','rect'); rect.setAttribute('x','0'); rect.setAttribute('y','0'); rect.setAttribute('width','120'); rect.setAttribute('height','92'); rect.setAttribute('rx','12'); rect.setAttribute('fill', id==='sut' ? 'rgba(3,169,244,0.2)' : 'rgba(255,255,255,0.08)'); rect.setAttribute('stroke','rgba(255,255,255,0.5)'); rect.setAttribute('stroke-width','2'); g.appendChild(rect);
      const title=document.createElementNS('http://www.w3.org/2000/svg','text'); title.setAttribute('x','60'); title.setAttribute('y','24'); title.setAttribute('text-anchor','middle'); title.setAttribute('fill','#ffffff'); title.setAttribute('font-family','Inter, Segoe UI, Arial, sans-serif'); title.setAttribute('font-size','13'); title.textContent = n.label.toUpperCase(); g.appendChild(title);
      const tps=document.createElementNS('http://www.w3.org/2000/svg','text'); tps.setAttribute('x','60'); tps.setAttribute('y','42'); tps.setAttribute('text-anchor','middle'); tps.setAttribute('fill','rgba(255,255,255,0.8)'); tps.setAttribute('font-family','ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'); tps.setAttribute('font-size','12'); tps.textContent = (typeof n.tps==='number')? (`TPS ${n.tps}`) : 'TPS –'; g.appendChild(tps);
      if(id !== 'sut'){
        // compute IN/OUT queues for this service
        const ins=[], outs=[];
        for(const [qname, obj] of Object.entries(swarm.queues)){
          if(obj.in && obj.in.has(n.id)) ins.push(qname);
          if(obj.out && obj.out.has(n.id)) outs.push(qname);
        }
        const fmt = (arr)=>{
          if(!arr.length) return '–';
          const first = arr[0];
          return arr.length>1 ? (first + '…') : first;
        };
        const inTxt = document.createElementNS('http://www.w3.org/2000/svg','text');
        inTxt.setAttribute('x','60'); inTxt.setAttribute('y','62'); inTxt.setAttribute('text-anchor','middle'); inTxt.setAttribute('fill','rgba(255,255,255,0.9)'); inTxt.setAttribute('font-family','ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'); inTxt.setAttribute('font-size','11');
        inTxt.textContent = `IN  ${fmt(ins)}`; if(ins.length) inTxt.setAttribute('title', ins.join(', ')); g.appendChild(inTxt);
        const outTxt = document.createElementNS('http://www.w3.org/2000/svg','text');
        outTxt.setAttribute('x','60'); outTxt.setAttribute('y','78'); outTxt.setAttribute('text-anchor','middle'); outTxt.setAttribute('fill','rgba(255,255,255,0.9)'); outTxt.setAttribute('font-family','ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'); outTxt.setAttribute('font-size','11');
        outTxt.textContent = `OUT ${fmt(outs)}`; if(outs.length) outTxt.setAttribute('title', outs.join(', ')); g.appendChild(outTxt);
      }
      svg.appendChild(g); }
    if(swarmStats){ const count = Object.keys(swarm.nodes).length - (swarm.nodes['sut']?1:0); const qCount = Object.keys(swarm.queues).length; swarmStats.textContent = `components: ${Math.max(0,count)} | queues: ${qCount} | edges: ${swarm.edges.length}`; }
  }

  function loadConn(){
    try{
      const raw = localStorage.getItem(PH_CONN_KEY);
      if(!raw) return null;
      return JSON.parse(raw);
    }catch{ return null; }
  }
  function saveConn(obj){
    try{ localStorage.setItem(PH_CONN_KEY, JSON.stringify(obj)); }catch{}
  }

  async function loadConfig(){
    try{
      const res = await fetch(PH_CFG_URL, {cache:'no-store'});
      if(res.ok){
        PH_CONFIG = await res.json();
        // Demo mode from config or URL (?demo=1)
        try{
          const qp = new URLSearchParams(window.location.search);
          DEMO = !!(PH_CONFIG.demo || qp.get('demo')==='1');
          if(DEMO) appendSys('Demo mode: WS disabled (no auto-connect, no health ping)');
        }catch{}
        try{
          const subs = (PH_CONFIG && Array.isArray(PH_CONFIG.subscriptions) && PH_CONFIG.subscriptions.length)
            ? PH_CONFIG.subscriptions.join(', ')
            : '(none)';
          appendSys(`Config loaded: stomp=${JSON.stringify(PH_CONFIG.stomp||{})}, subs=${subs}`);
        }catch{}
      } else {
        appendSys(`Config fetch failed: HTTP ${res.status}`);
      }
    }catch(e){ appendSys('Config fetch error: '+ (e && e.message ? e.message : String(e))); }
  }

  // Compute default WS URL; prefer same-origin `/ws` proxied by Nginx
  async function setDefaultWsUrl(){
    try {
      const pageHost = (window.location && window.location.host) || '';
      const hostOnly = (window.location && window.location.hostname) || '';
      const isLocalHost = /^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(hostOnly);
      const scheme = (window.location && window.location.protocol === 'https:') ? 'wss' : 'ws';
      const wsPath = (PH_CONFIG && PH_CONFIG.stomp && PH_CONFIG.stomp.wsPath) || '/ws';
      const sameOrigin = `${scheme}://${pageHost}${wsPath.startsWith('/')?wsPath:'/'+wsPath}`;
      const current = (elUrl.value || '').trim();
      // Prefer same-origin proxy when not explicitly customized by user
      if(!current || /^(ws|wss):\/\/(localhost|127\.0\.0\.1|\[?::1\]?)(:?\d+)?\/ws?$/i.test(current)){
        elUrl.value = sameOrigin;
        appendSys(`Default WS URL set: ${elUrl.value}`);
      }
      // Load stored connection if present
      const stored = loadConn();
      if(stored){
        if(stored.url) elUrl.value = stored.url;
        if(elUser && stored.login) elUser.value = stored.login;
        if(elPass && stored.pass) elPass.value = stored.pass;
        appendSys(`Stored connection loaded: url=${stored.url||'(empty)'} login=${stored.login||'(empty)'} vhost=${stored.vhost||'/'}`);
      }
    } catch(e) { /* noop */ }
  }

  let client = null;
  let connected = false;

  function setState(txt){ if(state) state.textContent = txt; }
  function appendLog(line){
    if(!logEl) return;
    const ts=new Date().toISOString();
    logLines.push(`[${ts}] ${line}`);
    if(logLines.length>logLimit) logLines.splice(0, logLines.length - logLimit);
    logEl.textContent = logLines.join('\n');
    logEl.scrollTop = logEl.scrollHeight;
  }
  function appendSys(line){
    if(!sysEl) return;
    const ts=new Date().toISOString();
    sysLines.push(`[${ts}] ${line}`);
      if(sysLines.length>sysLogLimit) sysLines.splice(0, sysLines.length - sysLogLimit);
    sysEl.textContent = sysLines.join('\n');
    sysEl.scrollTop = sysEl.scrollHeight;
  }

  function updateHealth(){
    if(!uiStatus) return;
    let state;
    if(uiHealth==='checking') state='checking';
    else {
      const uiOk = uiHealth==='good';
      const wsOk = wsHealth==='connected';
      if(uiOk && wsOk) state='good';
      else if(!uiOk && !wsOk) state='down';
      else state='warn';
    }
    uiStatus.dataset.state = state;
    uiStatus.setAttribute('aria-label', `Health ${state}`);
    uiStatus.title = `Health: ${state}`;
  }
  function setUiStatus(state){
    uiHealth = state || 'checking';
    updateHealth();
  }
  function setWsStatus(state){
    if(connBtn){
      const vis = {connecting:'searching', closed:'searching', error:'searching', idle:'searching'}[state||''] || state || 'searching';
      connBtn.dataset.state = vis;
      connBtn.setAttribute('aria-label', `WS ${state||''}`.trim());
      connBtn.title = `WebSocket: ${state}`;
      wsHealth = state || 'idle';
      updateHealth();
    }
  }

  function trimSeries(){
    for(const key of Object.keys(series)){
      const arr = series[key];
      if(arr.length>eventLimit) arr.splice(0, arr.length - eventLimit);
    }
  }
  // Charts helpers
  function addPoint(svc, v){
    const now = Date.now();
    const arr = series[svc];
    arr.push({t:now, v: Number(v)||0});
    if(arr.length>eventLimit) arr.splice(0, arr.length - eventLimit);
    const cutoff = now - WINDOW_MS;
    while(arr.length && arr[0].t < cutoff) arr.shift();
    scheduleDraw(svc);
  }
  function scheduleDraw(svc){
    if(!chartsEl || chartsEl.style.display === 'none') return;
    if(rafPending[svc]) return;
    rafPending[svc] = true;
    requestAnimationFrame(()=>{ rafPending[svc] = false; drawChart(svc); });
  }
  function drawChart(svc){
    const cv = canvases[svc];
    if(!cv) return;
    const ctx = cv.getContext('2d'); if(!ctx) return;
    const w = cv.width, h = cv.height;
    ctx.clearRect(0,0,w,h);
    // axes
    ctx.strokeStyle = 'rgba(255,255,255,0.2)';
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(40,10); ctx.lineTo(40,h-24); ctx.lineTo(w-6,h-24); ctx.stroke();
    const now = Date.now();
    const data = series[svc];
    if(!data.length) return;
    const xmin = now - WINDOW_MS, xmax = now;
    let ymin = 0, ymax = 1;
    for(const p of data){ if(p.v > ymax) ymax = p.v; }
    // padding
    ymax = Math.max(1, ymax * 1.1);
    const xr = (x) => 40 + ( (x - xmin) / (xmax - xmin) ) * (w - 46);
    const yr = (y) => (h - 24) - ( y / (ymax - ymin) ) * (h - 34);
    // gridlines (3)
    ctx.strokeStyle = 'rgba(255,255,255,0.1)';
    for(let i=1;i<=3;i++){ const gy = 10 + i*(h-34)/4; ctx.beginPath(); ctx.moveTo(40,gy); ctx.lineTo(w-6,gy); ctx.stroke(); }
    // labels
    ctx.fillStyle = 'rgba(255,255,255,0.7)'; ctx.font = '12px monospace';
    ctx.fillText(String(Math.round(ymax)), 6, 12);
    ctx.fillText('0', 28, h-24);
    // line
    const colors = { generator:'#4CAF50', moderator:'#FFC107', processor:'#03A9F4', latency:'#E91E63', hops:'#9C27B0' };
    ctx.strokeStyle = colors[svc] || '#FFFFFF';
    ctx.lineWidth = 2;
    ctx.beginPath();
    let started=false;
    for(const p of data){ const x=xr(p.t), y=yr(p.v); if(!started){ ctx.moveTo(x,y); started=true; } else { ctx.lineTo(x,y); } }
    ctx.stroke();
  }

  // Using StompJS (same as Generator UI)

  async function doConnect(){
    appendSys(`doConnect invoked (connected=${connected})`);
    if(connected){
      try{ if(client) client.deactivate(); }catch(e){}
      return;
    }
    let url = elUrl.value.trim() || `ws://${(window.location && window.location.host) || 'localhost:8088'}/ws`;
    // If user left localhost but page is opened from a remote host, swap host
    try{
      const u = new URL(url);
      // If URL points to localhost but page has a host:port, switch to same-origin path
      const pageHost = (window.location && window.location.host) || '';
      const hostOnly = (window.location && window.location.hostname) || '';
      if(pageHost && !/^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(hostOnly)){
        if(/^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(u.hostname)){
          url = `${u.protocol}//${pageHost}${u.pathname || '/ws'}`;
        }
      }
    }catch(e){ appendSys('URL normalize error: ' + (e && e.message ? e.message : String(e))); /* keep as-is */ }
    const cfgStomp = (PH_CONFIG && PH_CONFIG.stomp) || {};
    const prefer = !!(PH_CONFIG && PH_CONFIG.preferConfig);
    const login = (prefer ? (cfgStomp.login || '') : '') || (elUser && elUser.value) || 'guest';
    const passcode = (prefer ? (cfgStomp.passcode || '') : '') || (elPass && elPass.value) || 'guest';
    const vhost = cfgStomp.vhost || '/';
    // Persist connection for other pages (e.g., /generator)
    saveConn({ url, login, pass: passcode, vhost: '/' });
    // Log connect params (mask pass)
    try{ appendSys(`[CTRL] CONNECT url=${url} vhost=${vhost} login=${login} pass=***`); }catch{}
    setState('Connecting...'); btn.disabled = true; btn.textContent = 'Connecting...';
    setWsStatus('connecting');
    // eslint-disable-next-line no-undef
    client = new StompJs.Client({
      brokerURL: url,
      connectHeaders: { login, passcode, host: vhost },
      reconnectDelay: 0,
      debug: (s) => { if(LOG_STOMP_DEBUG) appendSys(`[STOMP] ${s}`); }
    });
    client.onConnect = (frame) => {
      connected = true;
      setState('Connected'); btn.disabled = false; btn.textContent = 'Disconnect';
      setWsStatus('connected');
      appendSys('CONNECTED ' + JSON.stringify(frame.headers || {}));
      let subs = (PH_CONFIG && Array.isArray(PH_CONFIG.subscriptions) && PH_CONFIG.subscriptions.length)
        ? PH_CONFIG.subscriptions.slice()
        : ['/queue/ph.control'];
      // Always include control exchange event/signal routes so Events see everything
      if(!subs.includes('/exchange/ph.control/ev.#')) subs.push('/exchange/ph.control/ev.#');
      if(!subs.includes('/exchange/ph.control/sig.#')) subs.push('/exchange/ph.control/sig.#');
      if(!subs.includes('/exchange/ph.control/ev.metric.#')) subs.push('/exchange/ph.control/ev.metric.#');
      if(!(PH_CONFIG && Array.isArray(PH_CONFIG.subscriptions) && PH_CONFIG.subscriptions.length)){
        appendSys('[CTRL] SUB using fallback [/queue/ph.control]');
      }
      subs.forEach(d => {
        try{
          client.subscribe(d, (message) => {
            const body = message.body || '';
            const dest = message.headers.destination || '';
            try{
              const obj = JSON.parse(body);
              if(dest.includes('/ev.metric.')){
                const m = dest.match(/\/ev\.metric\.([^/]+)/);
                const metricName = (obj.metric || obj.name || (m && m[1]) || '').toLowerCase();
                const val = obj.value ?? (obj.data && obj.data.value) ?? obj.v;
                if(typeof val === 'number'){
                  if(metricName==='latency' || metricName==='end_to_end_latency' || metricName==='e2e') addPoint('latency', val);
                  if(metricName==='hops' || metricName==='hop' || metricName==='hopcount') addPoint('hops', val);
                }
              } else if(obj){
                // Use API spec fields strictly: role/name, data.tps, queues{in,out}
                const svc = obj.role || obj.name || obj.service; // prefer role/name
                if(!svc) return;
                // Swarm: mark node, update TPS
                const node = ensureNode(svc);
                const tpsVal = (obj && obj.data && typeof obj.data.tps==='number') ? obj.data.tps : (typeof obj.tps==='number' ? obj.tps : undefined);
                if(node){ node.last = Date.now(); if(typeof tpsVal==='number') node.tps = tpsVal; }
                // Queues discovery (per spec: queues{in,out})
                const qobj = obj.queues;
                const changed = updateQueues(svc, qobj);
                if(changed) rebuildEdgesFromQueues();
                // Log a concise control summary
                try{
                  const role = svc;
                  const inst = obj.instance || '–';
                  const ev = obj.event || 'status';
                  const kind = obj.kind || (typeof tpsVal!=='undefined' ? 'status.delta' : 'status');
                  const qin = obj.queues && Array.isArray(obj.queues.in) ? obj.queues.in.length : 0;
                  const qout = obj.queues && Array.isArray(obj.queues.out) ? obj.queues.out.length : 0;
                  appendSys(`[CTRL] RECV ${ev}/${kind} role=${role} inst=${inst} tps=${typeof tpsVal==='number'?tpsVal:'–'} in=${qin} out=${qout}`);
                }catch{}
                redrawSwarm();
                // Control charts (if TPS provided)
                if(typeof tpsVal !== 'undefined'){
                  if(svc === 'generator' && genEl) genEl.textContent = String(tpsVal);
                  if(svc === 'moderator' && modEl) modEl.textContent = String(tpsVal);
                  if(svc === 'processor' && procEl) procEl.textContent = String(tpsVal);
                  if(svc === 'generator') addPoint('generator', tpsVal);
                  if(svc === 'moderator') addPoint('moderator', tpsVal);
                  if(svc === 'processor') addPoint('processor', tpsVal);
                }
              }
              if(LOG_CTRL_RAW) appendLog(`CTRL RAW ${dest} ${body}`);
            } catch(e){
              if(LOG_CTRL_RAW) appendLog(`CTRL RAW ${dest} ${body}`);
            }
          }, { ack:'auto' });
          appendSys(`[CTRL] SUB ${d}`);
        }catch(e){ appendSys('Subscribe error: '+ (e && e.message ? e.message : String(e))); }
      });
    };
    client.onStompError = (frame) => {
      appendSys('STOMP ERROR ' + (frame.headers && frame.headers.message ? frame.headers.message : '') + (frame.body ? (' | ' + frame.body) : ''));
    };
    client.onWebSocketError = (ev) => {
      appendSys('WebSocket error (stompjs) ' + (ev && ev.message ? ev.message : '[event]'));
      setWsStatus('error');
    };
    client.onWebSocketClose = (ev) => {
      appendSys(`Socket closed (stompjs) code=${ev && typeof ev.code==='number'?ev.code:'?'} reason=${ev && ev.reason ? ev.reason : ''} clean=${ev && typeof ev.wasClean==='boolean'?ev.wasClean:'?'}`);
      setWsStatus('closed');
      cleanup();
    };
    try{
      appendSys('Activating STOMP client');
      client.activate();
    }catch(e){
      appendSys('Activation error: ' + (e && e.message ? e.message : String(e)));
      setWsStatus('error'); btn.disabled=false; btn.textContent='Connect';
    }
    function cleanup(){ connected = false; setState('Disconnected'); btn.textContent = 'Connect'; btn.disabled = false; }
  }

  if(HAS_CONN) btn.addEventListener('click', ()=>{
    if(connected){
      appendSys('User action: Disconnect');
      try{ client && client.deactivate(); }catch(e){}
    } else {
      appendSys('User action: Connect');
      doConnect();
    }
  });

  // Initialize: load config, set defaults, then try auto-connect
  if(HAS_CONN && !DEMO){
    (async () => {
      await loadConfig();
      await setDefaultWsUrl();
      try{
        appendSys('Auto-connect attempt');
        doConnect();
      }catch{}
    })();
  }
  if(!HAS_CONN){ (async ()=>{ await loadConfig(); })(); }

  // Load VERSION and display in header if present
  (async () => {
    try{
      const el = document.getElementById('ph-version');
      if(!el) return;
      const r = await fetch('./VERSION', {cache:'no-store'});
      if(r.ok){ const txt = (await r.text()).trim(); if(txt) el.textContent = 'v'+txt; }
    }catch{}
  })();

  // Simple header menu dropdown
  (function(){
    const btn = document.getElementById('menu-btn');
    const dd = document.getElementById('menu-dropdown');
    if(!btn || !dd) return;
    let open=false;
    const toggle=(e)=>{ e && e.stopPropagation(); open=!open; dd.style.display=open?'block':'none'; };
    btn.addEventListener('click', toggle);
    document.addEventListener('click', (e)=>{ if(open && !dd.contains(e.target) && e.target!==btn){ open=false; dd.style.display='none'; } });
  })();

  // Move connection controls into header dropdown and toggle via WS health icon
  (function(){
    const host = document.getElementById('conn-dropdown');
    const trigger = connBtn;
    const controls = document.querySelector('.controls');
    if(!host || !trigger || !controls) return;
    host.appendChild(controls);
    controls.style.display = 'block';
    // make controls tidy in dropdown
    controls.style.display = 'grid';
    controls.style.gridTemplateColumns = '1fr 120px';
    controls.style.gap = '8px';
    // widen url row
    const url = document.getElementById('wsurl'); if(url) url.style.gridColumn = '1 / span 2';
    const stateLbl = document.getElementById('state'); if(stateLbl){ stateLbl.style.gridColumn = '1 / span 2'; stateLbl.style.alignSelf='center'; stateLbl.style.color='#9aa0a6'; }
    let open=false;
    const toggle=(e)=>{ e && e.stopPropagation(); open=!open; host.style.display = open ? 'block' : 'none'; };
    trigger.addEventListener('click', toggle);
    trigger.addEventListener('keydown', (e)=>{ if(e.key==='Enter' || e.key===' '){ e.preventDefault(); toggle(e); } });
    document.addEventListener('click', (e)=>{ if(open && !host.contains(e.target) && e.target!==trigger){ open=false; host.style.display='none'; } });
  })();

  // Log user edits to connection fields (mask secrets)
  if(HAS_CONN){
    if(elUrl){ elUrl.addEventListener('change', ()=> { appendSys(`User set WebSocket URL: ${elUrl.value.trim()||'(empty)'}`); saveConn({ url: elUrl.value||'', login: (elUser&&elUser.value)||'', pass: (elPass&&elPass.value)||'', vhost: '/' }); }); }
    if(elUser){ elUser.addEventListener('change', ()=> { appendSys(`User set username: ${elUser.value||'(empty)'}`); saveConn({ url: (elUrl&&elUrl.value)||'', login: elUser.value||'', pass: (elPass&&elPass.value)||'', vhost: '/' }); }); }
    if(elPass){ elPass.addEventListener('change', ()=> { appendSys(`User updated password (len=${(elPass.value||'').length})`); saveConn({ url: (elUrl&&elUrl.value)||'', login: (elUser&&elUser.value)||'', pass: elPass.value||'', vhost: '/' }); }); }
  }

  // Periodic healthcheck of UI reverse proxy
  (function startHealthPing(){
    if(!window.fetch) return; // older browsers
    if(DEMO) return;
    let lastStatus = null;
    const ping = async () => {
      try{
        setUiStatus('checking');
        const res = await fetch('/healthz', {cache:'no-store'});
        const ok = res.ok;
        const txt = await res.text().catch(()=> '');
        const status = ok && /ok/i.test(txt) ? 'good' : `warn(${res.status})`;
        if(status !== lastStatus){ appendSys(`UI health: ${status}`); setUiStatus(/good/.test(status)?'good':'warn'); lastStatus = status; }
      }catch(e){
        if(lastStatus !== 'down'){ appendSys('UI health: down'); setUiStatus('down'); lastStatus = 'down'; }
      }
    };
    ping();
    setInterval(ping, 15000);
  })();

  // Hook broadcast button (located near charts toggle)
  (function(){
    const btn = document.getElementById('broadcast-status');
    if(!btn) return;
    btn.addEventListener('click', ()=>{
      if(!client || !connected){ appendSys('[CTRL] SEND aborted: not connected'); return; }
      const payload = {
        type: 'status.request',
        version: '1.0',
        messageId: (crypto && crypto.randomUUID ? crypto.randomUUID() : (Math.random().toString(16).slice(2) + Date.now())),
        timestamp: new Date().toISOString()
      };
      // New routing key scheme: sig.<type>[.<role>[.<instance>]]; broadcast uses only type
      const rk = 'sig.status-request';
      const dest = '/exchange/ph.control/' + rk;
      try{
        // eslint-disable-next-line no-undef
        client.publish({ destination: dest, body: JSON.stringify(payload), headers: { 'content-type': 'application/json' } });
        appendSys(`[CTRL] SEND ${rk} payload=status-request`);
      }catch(e){ appendSys('Broadcast error: ' + (e && e.message ? e.message : String(e))); }
    });
  })();

  // Radar ping visual effect
  (function(){
    const btn = radarBtn;
    if(!btn) return;
    btn.addEventListener('click', ()=>{
      btn.classList.remove('pinging');
      // trigger reflow to restart animation
      void btn.offsetWidth;
      btn.classList.add('pinging');
    });
  })();
  function refreshCharts(){
    const metric = currentMetric;
    if(metric==='tps'){ ['generator','moderator','processor'].forEach(s=> drawChart(s)); }
    if(metric==='latency') drawChart('latency');
    if(metric==='hops') drawChart('hops');
  }

  function updateMetricView(){
    const metric = currentMetric;
    if(chartsTps) chartsTps.style.display = metric==='tps' ? 'grid' : 'none';
    if(chartsLatency) chartsLatency.style.display = metric==='latency' ? 'block' : 'none';
    if(chartsHops) chartsHops.style.display = metric==='hops' ? 'block' : 'none';
    if(chartsTitle){
      chartsTitle.textContent = metric==='tps'
        ? 'RabbitMQ Control Stream — TPS (last 60s)'
        : metric==='latency'
          ? 'End-to-End Latency (last 60s)'
          : 'Per-Hop Count (last 60s)';
    }
    if(chartsEl && chartsEl.style.display !== 'none') refreshCharts();
  }

  // Metric selector dropdown
  (function(){
    const btn = metricBtn;
    const dd = metricDropdown;
    if(!btn || !dd) return;
    let open=false;
    const toggle=(e)=>{ e && e.stopPropagation(); open=!open; dd.style.display=open?'block':'none'; };
    btn.addEventListener('click', toggle);
    document.addEventListener('click', (e)=>{ if(open && !dd.contains(e.target) && e.target!==btn){ open=false; dd.style.display='none'; } });
    dd.querySelectorAll('button[data-metric]').forEach(item=>{
      item.addEventListener('click', ()=>{
        const metric = item.getAttribute('data-metric') || 'tps';
        currentMetric = metric;
        btn.textContent = item.textContent || 'TPS';
        open=false; dd.style.display='none';
        updateMetricView();
      });
    });
    updateMetricView();
  })();

  // Toggle charts visibility
  if(toggleChartsBtn && chartsEl){
    toggleChartsBtn.addEventListener('click', ()=>{
      const show = chartsEl.style.display === 'none';
      chartsEl.style.display = show ? 'block' : 'none';
      if(show) refreshCharts();
    });
  }

  // (removed duplicate legacy broadcast handler)
})();
