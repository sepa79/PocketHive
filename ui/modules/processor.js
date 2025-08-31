export function renderProcessorPanel(containerEl, instanceId){
  const client = window.phClient;
  containerEl.innerHTML = `\n    <div class="card" data-role="processor">\n      <h3>Processor ${instanceId}</h3>\n      <label>Workers <input id="workers" type="number" min="1" value="1"></label>\n      <label>Mode <select id="mode"><option value="simulation">Simulation</option><option value="real">Real</option></select></label>\n      <div>TPS: <span id="tps">0</span> Errors: <span id="err">0</span></div>\n    </div>`;
  const workers = containerEl.querySelector('#workers');
  const mode = containerEl.querySelector('#mode');
  const tpsEl = containerEl.querySelector('#tps');
  const errEl = containerEl.querySelector('#err');
  function sendConfig(){
    const data={workers:workers?Number(workers.value):1, mode:mode?mode.value:''};
    const payload={type:'config-update',role:'processor',instance:instanceId,data};
    const rk=`sig.config-update.processor.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body:JSON.stringify(payload)});
  }
  workers && workers.addEventListener('change', sendConfig);
  mode && mode.addEventListener('change', sendConfig);
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.processor.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.tps!=null && tpsEl) tpsEl.textContent=String(data.tps); if(data.errors!=null && errEl) errEl.textContent=String(data.errors); }catch(e){}
    });
    const payload={type:'status-request',role:'processor',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.processor.${instanceId}`, body:JSON.stringify(payload)});
  }
}
