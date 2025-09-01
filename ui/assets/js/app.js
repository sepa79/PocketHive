import { initTabs } from './features/tabs.js';
import { initConnectionDropdown } from './features/connectionDropdown.js';
import { startHealthPing } from './features/healthPing.js';
import { setupBroadcast } from './features/broadcast.js';
import { initMetricSelector } from './features/metricSelector.js';
import { initBuzzMenu } from './menus/buzz.js';
import { initHiveMenu } from './menus/hive.js';
import { initNectarMenu } from './menus/nectar.js';

// Minimal STOMP over WebSocket client for RabbitMQ Web-STOMP
(function(){
  const PH_CONN_KEY = 'pockethive.conn';
  const PH_CFG_URL = '/config.json';
  let PH_CONFIG = null;
  let DEMO = false;
  const qs = id => document.getElementById(id);
  const elUrl = qs('wsurl');
  const elUser = qs('login');
  const elPass = qs('pass');
  const btn = qs('connect');
  const stateLbl = qs('state');
  const uiStatus = qs('status-ui');
  const wsStatus = qs('status-ws');
  const appState = { currentMetric: 'tps' };

  let client = null;
  let connected = false;

  const { appendLog, appendSys } = initBuzzMenu({ getClient: () => client, isConnected: () => connected });
  window.phAppendSys = appendSys;
  window.phAppendLog = appendLog;
  if (window.phClient && typeof window.phClient.publish === 'function') {
    const origPublish = window.phClient.publish.bind(window.phClient);
    window.phClient.publish = (params) => {
      try {
        origPublish(params);
        const dest = (params && params.destination) || '';
        let payloadType = '';
        try { payloadType = JSON.parse(params.body || '{}').type || ''; } catch {}
        const rk = dest.replace('/exchange/ph.control/', '');
        appendSys(`[BUZZ] SEND ${rk}${payloadType ? ` payload=${payloadType}` : ''}`);
      } catch (e) {
        appendSys('Publish error: ' + (e && e.message ? e.message : String(e)));
      }
    };
  }
  const hive = initHiveMenu();
  const nectar = initNectarMenu(appState);

  initTabs(hive.redrawHive);

  const LOG_EVENTS_RAW = true;
  const LOG_STOMP_DEBUG = true;
  const HAS_CONN = !!(elUrl && btn);

  function setState(txt){ if(stateLbl) stateLbl.textContent = txt; }
  function setUiStatus(state){ if(!uiStatus) return; uiStatus.dataset.state = state || 'connecting'; uiStatus.setAttribute('aria-label', `UI ${state||''}`.trim()); uiStatus.title = `UI: ${state}`; }
  function setWsStatus(state){ if(!wsStatus) return; wsStatus.dataset.state = state || 'idle'; wsStatus.setAttribute('aria-label', `WS ${state||''}`.trim()); wsStatus.title = `WebSocket: ${state}`; }

  function loadConn(){ try{ const raw = localStorage.getItem(PH_CONN_KEY); if(!raw) return null; return JSON.parse(raw); }catch{ return null; } }
  function saveConn(obj){ try{ localStorage.setItem(PH_CONN_KEY, JSON.stringify(obj)); }catch{} }

  async function loadConfig(){
    try{
      const res = await fetch(PH_CFG_URL, {cache:'no-store'});
      if(res.ok){
        PH_CONFIG = await res.json();
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

  async function setDefaultWsUrl(){
    try{
      const pageHost = (window.location && window.location.host) || '';
      const hostOnly = (window.location && window.location.hostname) || '';
      const isLocalHost = /^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(hostOnly);
      const scheme = (window.location && window.location.protocol === 'https:') ? 'wss' : 'ws';
      const wsPath = (PH_CONFIG && PH_CONFIG.stomp && PH_CONFIG.stomp.wsPath) || '/ws';
      const sameOrigin = `${scheme}://${pageHost}${wsPath.startsWith('/')?wsPath:'/'+wsPath}`;
      const current = (elUrl.value || '').trim();
      if(!current || /^(ws|wss):\/\/(localhost|127\.0\.0\.1|\[?::1\]?)(:?\d+)?\/ws?$/i.test(current)){
        elUrl.value = sameOrigin;
        appendSys(`Default WS URL set: ${elUrl.value}`);
      }
      const stored = loadConn();
      if(stored){
        if(stored.url) elUrl.value = stored.url;
        if(elUser && stored.login) elUser.value = stored.login;
        if(elPass && stored.pass) elPass.value = stored.pass;
        appendSys(`Stored connection loaded: url=${stored.url||'(empty)'} login=${stored.login||'(empty)'} vhost=${stored.vhost||'/'}`);
      }
    }catch{ /* noop */ }
  }

  startHealthPing({ appendSys, setUiStatus, demo: DEMO });
  setupBroadcast(() => client, appendSys, () => connected);
  initMetricSelector(appState, nectar.updateMetricView);
  initConnectionDropdown();

  (function(){
    const btn = document.getElementById('menu-btn');
    const dd = document.getElementById('menu-dropdown');
    if(!btn || !dd) return;
    let open=false;
    const toggle=(e)=>{ e && e.stopPropagation(); open=!open; dd.style.display=open?'block':'none'; };
    btn.addEventListener('click', toggle);
    document.addEventListener('click', (e)=>{ if(open && !dd.contains(e.target) && e.target!==btn){ open=false; dd.style.display='none'; } });
  })();

  (async () => {
    try{
      const el = document.getElementById('ph-version');
      if(!el) return;
      const r = await fetch('./VERSION', {cache:'no-store'});
      if(r.ok){ const txt = (await r.text()).trim(); if(txt) el.textContent = 'v'+txt; }
    }catch{}
  })();

  function doConnect(){
    appendSys(`doConnect invoked (connected=${connected})`);
    if(connected){
      try{ if(client) client.deactivate(); }catch{}
      return;
    }
    let url = elUrl.value.trim() || `ws://${(window.location && window.location.host) || 'localhost:8088'}/ws`;
    try{
      const u = new URL(url);
      const pageHost = (window.location && window.location.host) || '';
      const hostOnly = (window.location && window.location.hostname) || '';
      if(pageHost && !/^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(hostOnly)){
        if(/^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(u.hostname)){
          url = `${u.protocol}//${pageHost}${u.pathname || '/ws'}`;
        }
      }
      url = `${u.protocol}//${u.host}${u.pathname||'/ws'}`;
    }catch{}
    client = new StompJs.Client({
      brokerURL: url,
      debug: (s) => { if(LOG_STOMP_DEBUG) appendSys(`[STOMP] ${s}`); }
    });
    client.onConnect = () => {
      connected = true;
      setState('Connected');
      btn.textContent = 'Disconnect';
      setWsStatus('open');
      const subs = (PH_CONFIG && Array.isArray(PH_CONFIG.subscriptions) && PH_CONFIG.subscriptions.length)
        ? PH_CONFIG.subscriptions
        : ['sig.config-update.#','sig.status-request.#','ev.#'];
      subs.forEach(d => {
        try{
          client.subscribe(`/exchange/ph.control/${d}`, (message) => {
            const body = message.body || '';
            const dest = message.headers.destination || '';
            try{
              const obj = JSON.parse(body);
              if(dest.includes('/ev.metric.')){
                const m = dest.match(/\/ev\.metric\.([^/]+)/);
                const metricName = (obj.metric || obj.name || (m && m[1]) || '').toLowerCase();
                const val = obj.value ?? (obj.data && obj.data.value) ?? obj.v;
                if(typeof val === 'number'){
                  if(metricName==='latency' || metricName==='end_to_end_latency' || metricName==='e2e') nectar.addPoint('latency', val);
                  if(metricName==='hops' || metricName==='hop' || metricName==='hopcount') nectar.addPoint('hops', val);
                }
              } else if(obj){
                const svc = obj.role || obj.name || obj.service;
                if(!svc) return;
                const node = hive.ensureNode(svc);
                const tpsVal = (obj && obj.data && typeof obj.data.tps==='number') ? obj.data.tps : (typeof obj.tps==='number' ? obj.tps : undefined);
                if(node){ node.last = Date.now(); if(typeof tpsVal==='number') node.tps = tpsVal; }
                const changed = hive.updateQueues(svc, obj);
                if(changed) hive.rebuildEdgesFromQueues();
                try{
                  const role = svc;
                  const inst = obj.instance || '–';
                  const ev = obj.event || 'status';
                  const kind = obj.kind || (typeof tpsVal!=='undefined' ? 'status-delta' : 'status');
                  const queues = obj.queues || {};
                  const qin = Array.isArray(queues.in) ? queues.in.length : (obj.inQueue ? 1 : 0);
                  const qout = Array.isArray(queues.out) ? queues.out.length : (Array.isArray(obj.publishes) ? obj.publishes.length : 0);
                  appendSys(`[BUZZ] RECV ${ev}/${kind} role=${role} inst=${inst} tps=${typeof tpsVal==='number'?tpsVal:'–'} in=${qin} out=${qout}`);
                }catch{}
                hive.redrawHive();
                if(typeof tpsVal !== 'undefined'){
                  nectar.updateTps(svc, tpsVal);
                  nectar.addPoint(svc, tpsVal);
                }
              }
              if(LOG_EVENTS_RAW) appendLog(`EVENT RAW ${dest} ${body}`);
            } catch(e){
              if(LOG_EVENTS_RAW) appendLog(`EVENT RAW ${dest} ${body}`);
            }
          }, { ack:'auto' });
          appendSys(`[BUZZ] SUB ${d}`);
        }catch(e){ appendSys('Subscribe error: ' + (e && e.message ? e.message : String(e))); }
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
      try{ client && client.deactivate(); }catch{}
    } else {
      appendSys('User action: Connect');
      doConnect();
    }
  });

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

  if(HAS_CONN){
    if(elUrl){ elUrl.addEventListener('change', ()=> { appendSys(`User set WebSocket URL: ${elUrl.value.trim()||'(empty)'}`); saveConn({ url: elUrl.value||'', login: (elUser&&elUser.value)||'', pass: (elPass&&elPass.value)||'', vhost: '/' }); }); }
    if(elUser){ elUser.addEventListener('change', ()=> { appendSys(`User set username: ${elUser.value||'(empty)'}`); saveConn({ url: (elUrl&&elUrl.value)||'', login: elUser.value||'', pass: (elPass&&elPass.value)||'', vhost: '/' }); }); }
    if(elPass){ elPass.addEventListener('change', ()=> { appendSys(`User updated password (len=${(elPass.value||'').length})`); saveConn({ url: (elUrl&&elUrl.value)||'', login: (elUser&&elUser.value)||'', pass: elPass.value||'', vhost: '/' }); }); }
  }
})();
