export function renderProcessorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  containerEl.innerHTML = `\n    <div class="card" data-role="processor">\n      <h3>Processor ${instanceId}</h3>\n      <div class="info">\n        <div>IN: <span id="in"></span></div>\n        <div>Routes: <span id="routes"></span></div>\n        <div>Publishes: <span id="pub"></span></div>\n        <div id="cfg"></div>\n      </div>\n      <label>Workers <input id="workers" type="number" min="1" value="1"></label>\n      <label>Mode <select id="mode"><option value="simulation">Simulation</option><option value="real">Real</option></select></label>\n      <div>TPS: <span id="tps">0</span> Errors: <span id="err">0</span></div>\n    </div>`;
  const workers = containerEl.querySelector('#workers');
  const mode = containerEl.querySelector('#mode');
  const tpsEl = containerEl.querySelector('#tps');
  const errEl = containerEl.querySelector('#err');
  const inEl = containerEl.querySelector('#in');
  const routesEl = containerEl.querySelector('#routes');
  const pubEl = containerEl.querySelector('#pub');
  const cfgEl = containerEl.querySelector('#cfg');
  function sendConfig(){
    const data={workers:workers?Number(workers.value):1, mode:mode?mode.value:''};
    const payload={type:'config-update',role:'processor',instance:instanceId,data};
    const rk=`sig.config-update.processor.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body:JSON.stringify(payload)});
  }
  workers && workers.addEventListener('change', sendConfig);
  mode && mode.addEventListener('change', sendConfig);
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
    if(workers && data.workers!=null) workers.value=String(data.workers);
    if(mode && data.mode) mode.value=data.mode;
    if(inEl) inEl.textContent=evt.inQueue&&evt.inQueue.name?evt.inQueue.name:'';
    if(routesEl) routesEl.textContent=evt.inQueue&&Array.isArray(evt.inQueue.routingKeys)?evt.inQueue.routingKeys.join(', '):'';
    if(pubEl) pubEl.textContent=Array.isArray(evt.publishes)?evt.publishes.join(', '):'';
    if(cfgEl){
      cfgEl.innerHTML='';
      const entries=[];
      if(evt.enabled!=null) entries.push(['enabled', evt.enabled]);
      for(const [k,v] of Object.entries(data)) entries.push([k,v]);
      entries.forEach(([k,v])=>{ const d=document.createElement('div'); d.textContent=`${k}: ${v}`; cfgEl.appendChild(d); });
    }
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.processor.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.tps!=null && tpsEl) tpsEl.textContent=String(data.tps); if(data.errors!=null && errEl) errEl.textContent=String(data.errors); }catch(e){}
    });
    client.subscribe(`/exchange/ph.control/ev.status-full.processor.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'processor',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.processor.${instanceId}`, body:JSON.stringify(payload)});
  }
}
