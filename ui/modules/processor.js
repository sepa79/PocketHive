import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderProcessorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const common = renderCommonPanel(containerEl, 'processor', instanceId, '');
  function apply(evt){
    if(!evt) return;
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.status-full.processor.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'processor',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.processor.${instanceId}`, body:JSON.stringify(payload)});
  }
}
