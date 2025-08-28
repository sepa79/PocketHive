// Minimal STOMP over WebSocket client for RabbitMQ Web-STOMP
(function(){
  const qs = (id) => document.getElementById(id);
  const elUrl = qs('wsurl');
  const elUser = qs('login');
  const elPass = qs('pass');
  const btn = qs('connect');
  const state = qs('state');
  const logEl = qs('log');
  const genEl = qs('gen');
  const modEl = qs('mod');
  const procEl = qs('proc');
  if(!elUrl || !btn) return;

  // Compute default WS URL based on page origin host
  (function setDefaultWsUrl(){
    try {
      const pageHost = window.location && window.location.hostname;
      const isLocalHost = /^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(pageHost || '');
      const scheme = (window.location && window.location.protocol === 'https:') ? 'wss' : 'ws';
      const computed = `${scheme}://${pageHost || 'localhost'}:15674/ws`;
      const current = (elUrl.value || '').trim();
      if(!current || /^(ws|wss):\/\/(localhost|127\.0\.0\.1|\[?::1\]?)/i.test(current)){
        // If user didn't set a custom host, or it's localhost, replace with page host
        if(pageHost && !isLocalHost) elUrl.value = computed;
      }
    } catch(e) { /* noop */ }
  })();

  let ws = null;
  let connected = false;
  let buffer = '';
  let subIds = [];

  function setState(txt){ if(state) state.textContent = txt; }
  function appendLog(line){ if(!logEl) return; const ts=new Date().toISOString(); logEl.textContent += `[${ts}] ${line}\n`; logEl.scrollTop = logEl.scrollHeight; }

  function send(frame){ if(ws && ws.readyState === WebSocket.OPEN){ ws.send(frame); } }

  function buildFrame(command, headers={}, body=''){
    const lines = [command];
    for(const k in headers){ if(headers[k] != null) lines.push(`${k}:${headers[k]}`); }
    lines.push(''); // headers end
    const head = lines.join('\n');
    return head + (body || '') + '\u0000';
  }

  function parseFrames(chunk){
    buffer += chunk;
    const frames = [];
    let idx;
    while((idx = buffer.indexOf('\u0000')) !== -1){
      const raw = buffer.slice(0, idx);
      buffer = buffer.slice(idx+1);
      if(!raw.trim()) continue;
      const parts = raw.split('\n');
      const command = parts.shift();
      const headers = {};
      let line;
      while(parts.length){
        line = parts.shift();
        if(line === '') break; // end headers
        const sep = line.indexOf(':');
        if(sep > -1){ headers[line.slice(0,sep)] = line.slice(sep+1); }
      }
      const body = parts.join('\n');
      frames.push({command, headers, body});
    }
    return frames;
  }

  function subscribe(dest){
    const id = `sub-${Math.random().toString(36).slice(2,8)}`;
    subIds.push(id);
    send(buildFrame('SUBSCRIBE', { id, destination: dest, ack: 'auto' }));
  }

  function doConnect(){
    if(connected){ try{ ws && ws.close(); }catch(e){} return; }
    let url = elUrl.value.trim() || 'ws://localhost:15674/ws';
    // If user left localhost but page is opened from a remote host, swap host
    try{
      const u = new URL(url);
      const pageHost = window.location && window.location.hostname;
      if(pageHost && !/^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(pageHost)){
        if(/^(localhost|127\.0\.0\.1|\[?::1\]?)$/i.test(u.hostname)){
          u.hostname = pageHost; // preserve port and protocol
          url = u.toString();
        }
      }
    }catch(e){ /* keep as-is */ }
    const login = (elUser && elUser.value) || 'guest';
    const passcode = (elPass && elPass.value) || 'guest';
    setState('Connecting...'); btn.disabled = true; btn.textContent = 'Connecting...';
    buffer=''; subIds = [];
    try {
      ws = new WebSocket(url, ['v12.stomp','v11.stomp','v10.stomp']);
    } catch(e){ appendLog('WebSocket init failed: '+e.message); btn.disabled=false; btn.textContent='Connect'; return; }

    ws.addEventListener('open', ()=>{
      const frame = buildFrame('CONNECT', {
        'accept-version': '1.2,1.1,1.0',
        host: '/',
        login, passcode,
        'heart-beat': '10000,10000'
      });
      send(frame);
    });

    ws.addEventListener('message', (ev)=>{
      const frames = typeof ev.data === 'string' ? parseFrames(ev.data) : [];
      for(const f of frames){
        if(f.command === 'CONNECTED'){
          connected = true;
          setState('Connected'); btn.disabled = false; btn.textContent = 'Disconnect';
          appendLog('CONNECTED ' + JSON.stringify(f.headers));
          // Subscribe to TPS events
          subscribe('/exchange/status.exchange/generator.tps');
          subscribe('/exchange/status.exchange/moderator.tps');
          subscribe('/exchange/status.exchange/processor.tps');
        } else if(f.command === 'MESSAGE'){
          let body = f.body || '';
          try {
            const obj = JSON.parse(body);
            if(obj && typeof obj.tps !== 'undefined' && obj.service){
              if(obj.service === 'generator' && genEl) genEl.textContent = String(obj.tps);
              if(obj.service === 'moderator' && modEl) modEl.textContent = String(obj.tps);
              if(obj.service === 'processor' && procEl) procEl.textContent = String(obj.tps);
            }
            appendLog(`MSG ${f.headers.destination || ''} ${body}`);
          } catch(e){
            appendLog(`MSG ${f.headers.destination || ''} ${body}`);
          }
        } else if(f.command === 'ERROR'){
          appendLog('ERROR ' + (f.body || JSON.stringify(f.headers)));
        }
      }
    });

    function cleanup(){
      connected = false; setState('Disconnected'); btn.textContent = 'Connect'; btn.disabled = false;
    }
    ws.addEventListener('error', ()=>{ appendLog('WebSocket error'); });
    ws.addEventListener('close', ()=>{ appendLog('Socket closed'); cleanup(); });
  }

  btn.addEventListener('click', ()=>{
    if(connected){
      // Graceful DISCONNECT
      try{ send(buildFrame('DISCONNECT')); }catch(e){}
      try{ ws && ws.close(); }catch(e){}
    } else {
      doConnect();
    }
  });
})();
