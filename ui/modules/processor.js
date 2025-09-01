import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderProcessorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const extra = `
      <div class="section">
        <div class="block-title">Base URL</div>
        <label>Base URL <input id="baseUrl" type="text"></label>
        <div class="controls"><button id="confirmBase">Confirm Changes</button></div>
      </div>`;
  const common = renderCommonPanel(containerEl, 'processor', instanceId, extra);
  const baseUrlInp = containerEl.querySelector('#baseUrl');
  const confirmBase = containerEl.querySelector('#confirmBase');
  function sendConfig(data){
    const payload = {type:'config-update', role:'processor', instance:instanceId, data};
    const rk = `sig.config-update.processor.${instanceId}`;
    client.publish({destination:`/exchange/ph.control/${rk}`, body: JSON.stringify(payload)});
    const srPayload = {type:'status-request', role:'processor', instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.processor.${instanceId}`, body: JSON.stringify(srPayload)});
  }
  confirmBase && confirmBase.addEventListener('click', ()=> sendConfig({baseUrl: baseUrlInp && baseUrlInp.value}));
  function apply(evt){
    if(!evt) return;
    const data = evt.data||{};
    if(data.baseUrl!=null && baseUrlInp) baseUrlInp.value = data.baseUrl;
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.status-full.processor.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'processor',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.processor.${instanceId}`, body:JSON.stringify(payload)});
  }
}
