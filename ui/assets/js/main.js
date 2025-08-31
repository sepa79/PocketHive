// Main bootstrap for dynamic panels
const client = new StompJs.Client({ brokerURL: (location.protocol==='https:'?'wss://':'ws://') + location.host + '/ws' });
window.phClient = client;
const panelArea = document.getElementById('hive-panels');
const containers = {};
const panels = {generator:{}, moderator:{}, processor:{}, postprocessor:{}};
const instances = {};
['generator','moderator','processor','postprocessor'].forEach(role=>{
  const div = document.createElement('div');
  if(panelArea) panelArea.appendChild(div);
  containers[role] = div;
});
const loaded = {generator:{}, moderator:{}, processor:{}, postprocessor:{}};
client.onConnect = ()=>{
  // discover services via status-full events
  client.subscribe('/exchange/ph.control/ev.status-full.#', msg=>{
    try{
      const body = JSON.parse(msg.body||'{}');
      const role = body.role || body.name || body.service;
      const inst = body.instance || body.id;
      if(role && inst){
        instances[role] = inst;
        if(containers[role] && !loaded[role][inst]){
          loaded[role][inst]=true;
          initPanel(role, inst);
        }
      }
    }catch(e){ console.error(e); }
  });
  // request current status from all services so panels appear automatically
  try{
    const payload = {type:'status-request', timestamp:new Date().toISOString()};
    client.publish({destination:'/exchange/ph.control/sig.status-request', body:JSON.stringify(payload)});
  }catch(err){ console.error('status-request', err); }
};
client.activate();
function initPanel(role, id){
  const host = containers[role];
  if(!host) return;
  import(`../../modules/${role}.js`).then(mod=>{
    const fn = mod[`render${role.charAt(0).toUpperCase()+role.slice(1)}Panel`];
    if(typeof fn==='function'){
      const el = document.createElement('div');
      el.style.display='none';
      host.appendChild(el);
      panels[role][id] = el;
      fn(el, id);
    }
  }).catch(err=>console.error('module load', err));
}

function showPanel(role){
  const id = instances[role];
  if(!id) return;
  Object.values(panels).forEach(obj=>Object.values(obj).forEach(el=>{ el.style.display='none'; }));
  const el = panels[role] && panels[role][id];
  if(el) el.style.display='block';
  try{
    const payload = {type:'status-request', role, instance:id};
    client.publish({destination:`/exchange/ph.control/sig.status-request.${role}.${id}`, body:JSON.stringify(payload)});
  }catch(err){ console.error('status-request', err); }
}
window.phShowPanel = showPanel;
