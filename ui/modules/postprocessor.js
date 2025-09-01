export function renderPostprocessorPanel(containerEl, instanceId){
  const client = window.phClient;
  containerEl.innerHTML = `\n    <div class="card" data-role="postprocessor">\n      <h3>PostProcessor ${instanceId}</h3>\n      <div class="info">\n        <div>IN: <span id="in"></span></div>\n        <div>Routes: <span id="routes"></span></div>\n        <div>Publishes: <span id="pub"></span></div>\n        <div id="cfg"></div>\n      </div>\n      <div>Hop Latency: <span id="hop">0</span> ms</div>\n      <div>Total Latency: <span id="total">0</span> ms</div>\n      <div>Errors: <span id="err">0</span></div>\n      <button id="reset">Reset</button>\n    </div>`;
  const hopEl = containerEl.querySelector('#hop');
  const totalEl = containerEl.querySelector('#total');
  const errEl = containerEl.querySelector('#err');
  const resetBtn = containerEl.querySelector('#reset');
  const inEl = containerEl.querySelector('#in');
  const routesEl = containerEl.querySelector('#routes');
  const pubEl = containerEl.querySelector('#pub');
  const cfgEl = containerEl.querySelector('#cfg');
  if(resetBtn){
    resetBtn.addEventListener('click', ()=>{
      const payload={type:'reset',role:'postprocessor',instance:instanceId};
      client.publish({destination:`/exchange/ph.control/sig.reset.postprocessor.${instanceId}`, body:JSON.stringify(payload)});
    });
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.postprocessor.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.hopLatencyMs!=null && hopEl) hopEl.textContent=String(data.hopLatencyMs); if(data.totalLatencyMs!=null && totalEl) totalEl.textContent=String(data.totalLatencyMs); if(data.errors!=null && errEl) errEl.textContent=String(data.errors); }catch(e){}
    });
    client.subscribe(`/exchange/ph.control/ev.status-full.postprocessor.${instanceId}`, msg=>{
      try{
        const evt=JSON.parse(msg.body||'{}');
        const data=evt.data||{};
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
      }catch(e){}
    });
    const payload={type:'status-request',role:'postprocessor',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.postprocessor.${instanceId}`, body:JSON.stringify(payload)});
  }
}
