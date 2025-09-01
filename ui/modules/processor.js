import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderProcessorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
      <label>Workers <input id="workers" type="number" min="1" value="1"></label>
      <label>Mode <select id="mode"><option value="simulation">Simulation</option><option value="real">Real</option></select></label>
      <div>TPS: <span id="tps">0</span> Errors: <span id="err">0</span></div>`;
  const common = renderCommonPanel(containerEl, 'processor', instanceId, extra);
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
  function apply(evt){
    if(!evt) return;
    const data=evt.data||{};
    if(workers && data.workers!=null) workers.value=String(data.workers);
    if(mode && data.mode) mode.value=data.mode;
    applyCommonStatus(evt, common);
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
