import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderPostprocessorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
      <div>Hop Latency: <span id="hop">0</span> ms</div>
      <div>Total Latency: <span id="total">0</span> ms</div>
      <div>Errors: <span id="err">0</span></div>`;
  const common = renderCommonPanel(containerEl, 'postprocessor', instanceId, extra);
  const hopEl = containerEl.querySelector('#hop');
  const totalEl = containerEl.querySelector('#total');
  const errEl = containerEl.querySelector('#err');
  function apply(evt){
    if(!evt) return;
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.metric.postprocessor.${instanceId}`, msg=>{
      try{ const data=JSON.parse(msg.body||'{}').data||{}; if(data.hopLatencyMs!=null && hopEl) hopEl.textContent=String(data.hopLatencyMs); if(data.totalLatencyMs!=null && totalEl) totalEl.textContent=String(data.totalLatencyMs); if(data.errors!=null && errEl) errEl.textContent=String(data.errors); }catch(e){}
    });
    client.subscribe(`/exchange/ph.control/ev.status-full.postprocessor.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'postprocessor',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.postprocessor.${instanceId}`, body:JSON.stringify(payload)});
  }
}
