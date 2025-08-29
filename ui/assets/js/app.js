// Minimal STOMP over WebSocket client for RabbitMQ Web-STOMP
(function(){
  const PH_CONN_KEY = 'pockethive.conn';
  const PH_CFG_URL = '/config.json';
  let PH_CONFIG = null;
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
  const wsStatus = qs('status-ws');
  const chartsEl = qs('charts');
  const toggleChartsBtn = qs('toggle-charts');
  const canvases = {
    generator: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-gen')),
    moderator: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-mod')),
    processor: /** @type {HTMLCanvasElement|null} */(document.getElementById('chart-proc'))
  };
  const series = { generator: [], moderator: [], processor: [] }; // {t:number,v:number}
  const WINDOW_MS = 60_000; // 60s window
  let rafPending = { generator:false, moderator:false, processor:false };
  if(!elUrl || !btn) return;

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
  function appendLog(line){ if(!logEl) return; const ts=new Date().toISOString(); logEl.textContent += `[${ts}] ${line}\n`; logEl.scrollTop = logEl.scrollHeight; }
  function appendSys(line){ if(!sysEl) return; const ts=new Date().toISOString(); sysEl.textContent += `[${ts}] ${line}\n`; sysEl.scrollTop = sysEl.scrollHeight; }

  function setUiStatus(state){
    if(!uiStatus) return;
    const map = { healthy:'🟢 UI', unhealthy:'🔴 UI', down:'⚫ UI', connecting:'🟡 UI' };
    uiStatus.textContent = map[state] || '⚪ UI';
  }
  function setWsStatus(state){
    if(!wsStatus) return;
    const map = { connected:'🟢 WS', connecting:'🔄 WS', error:'🔴 WS', closed:'⚫ WS', idle:'⚪ WS' };
    wsStatus.textContent = map[state] || '⚪ WS';
  }

  // Charts helpers
  function addPoint(svc, v){
    const now = Date.now();
    const arr = series[svc];
    arr.push({t:now, v: Number(v)||0});
    // trim window
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
    ctx.strokeStyle = svc==='generator' ? '#4CAF50' : svc==='moderator' ? '#FFC107' : '#03A9F4';
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
    const login = (elUser && elUser.value) || 'guest';
    const passcode = (elPass && elPass.value) || 'guest';
    const vhost = (PH_CONFIG && PH_CONFIG.stomp && PH_CONFIG.stomp.vhost) || '/';
    // Persist connection for other pages (e.g., /generator)
    saveConn({ url, login, pass: passcode, vhost: '/' });
    setState('Connecting...'); btn.disabled = true; btn.textContent = 'Connecting...';
    setWsStatus('connecting');
    // eslint-disable-next-line no-undef
    client = new StompJs.Client({
      brokerURL: url,
      connectHeaders: { login, passcode, host: vhost },
      reconnectDelay: 0,
      debug: (s) => { appendSys(`[STOMP] ${s}`); }
    });
    client.onConnect = (frame) => {
      connected = true;
      setState('Connected'); btn.disabled = false; btn.textContent = 'Disconnect';
      setWsStatus('connected');
      appendSys('CONNECTED ' + JSON.stringify(frame.headers || {}));
      const subs = (PH_CONFIG && Array.isArray(PH_CONFIG.subscriptions) && PH_CONFIG.subscriptions.length)
        ? PH_CONFIG.subscriptions
        : ['/queue/ph.control'];
      if(!(PH_CONFIG && Array.isArray(PH_CONFIG.subscriptions) && PH_CONFIG.subscriptions.length)){
        appendSys('No subscriptions from config; using fallback [/queue/ph.control]');
      } else {
        appendSys('Subscribing to configured destinations: ' + subs.join(', '));
      }
      subs.forEach(d => {
        try{
          client.subscribe(d, (message) => {
            const body = message.body || '';
            try{
              const obj = JSON.parse(body);
              if(obj && typeof obj.tps !== 'undefined' && obj.service){
                if(obj.service === 'generator' && genEl) genEl.textContent = String(obj.tps);
                if(obj.service === 'moderator' && modEl) modEl.textContent = String(obj.tps);
                if(obj.service === 'processor' && procEl) procEl.textContent = String(obj.tps);
                if(obj.service === 'generator') addPoint('generator', obj.tps);
                if(obj.service === 'moderator') addPoint('moderator', obj.tps);
                if(obj.service === 'processor') addPoint('processor', obj.tps);
              }
              appendLog(`MSG ${message.headers.destination || ''} ${body}`);
            } catch(e){
              appendLog(`MSG ${message.headers.destination || ''} ${body}`);
            }
          }, { ack:'auto' });
          appendSys('SUBSCRIBE ' + d);
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

  btn.addEventListener('click', ()=>{
    if(connected){
      appendSys('User action: Disconnect');
      try{ client && client.deactivate(); }catch(e){}
    } else {
      appendSys('User action: Connect');
      doConnect();
    }
  });

  // Initialize: load config, set defaults
  (async () => { await loadConfig(); await setDefaultWsUrl(); })();

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

  // Log user edits to connection fields (mask secrets)
  if(elUrl){ elUrl.addEventListener('change', ()=> { appendSys(`User set WebSocket URL: ${elUrl.value.trim()||'(empty)'}`); saveConn({ url: elUrl.value||'', login: (elUser&&elUser.value)||'', pass: (elPass&&elPass.value)||'', vhost: '/' }); }); }
  if(elUser){ elUser.addEventListener('change', ()=> { appendSys(`User set username: ${elUser.value||'(empty)'}`); saveConn({ url: (elUrl&&elUrl.value)||'', login: elUser.value||'', pass: (elPass&&elPass.value)||'', vhost: '/' }); }); }
  if(elPass){ elPass.addEventListener('change', ()=> { appendSys(`User updated password (len=${(elPass.value||'').length})`); saveConn({ url: (elUrl&&elUrl.value)||'', login: (elUser&&elUser.value)||'', pass: elPass.value||'', vhost: '/' }); }); }

  // Periodic healthcheck of UI reverse proxy
  (function startHealthPing(){
    if(!window.fetch) return; // older browsers
    let lastStatus = null;
    const ping = async () => {
      try{
        const res = await fetch('/healthz', {cache:'no-store'});
        const ok = res.ok;
        const txt = await res.text().catch(()=> '');
        const status = ok && /ok/i.test(txt) ? 'healthy' : `unhealthy(${res.status})`;
        if(status !== lastStatus){ appendSys(`UI health: ${status}`); setUiStatus(/healthy/.test(status)?'healthy':'unhealthy'); lastStatus = status; }
      }catch(e){
        if(lastStatus !== 'down'){ appendSys('UI health: down'); setUiStatus('down'); lastStatus = 'down'; }
      }
    };
    ping();
    setInterval(ping, 15000);
  })();

  // Toggle charts visibility
  if(toggleChartsBtn && chartsEl){
    toggleChartsBtn.addEventListener('click', ()=>{
      const show = chartsEl.style.display === 'none';
      chartsEl.style.display = show ? 'block' : 'none';
      // redraw on show
      if(show){ ['generator','moderator','processor'].forEach(s=> drawChart(s)); }
    });
  }
})();
