import { renderCommonPanel, applyCommonStatus } from './common.js';

export function renderModeratorPanel(containerEl, instanceId, initial){
  const client = window.phClient;
  const common = renderCommonPanel(containerEl, 'moderator', instanceId, '');
  function apply(evt){
    if(!evt) return;
    applyCommonStatus(evt, common);
  }
  if(client){
    client.subscribe(`/exchange/ph.control/ev.status-full.moderator.${instanceId}`, msg=>{ try{ apply(JSON.parse(msg.body||'{}')); }catch(e){} });
    apply(initial);
    const payload={type:'status-request',role:'moderator',instance:instanceId};
    client.publish({destination:`/exchange/ph.control/sig.status-request.moderator.${instanceId}`, body:JSON.stringify(payload)});
  }
}
