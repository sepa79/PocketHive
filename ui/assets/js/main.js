// Main bootstrap for dynamic panels
const client = new StompJs.Client({ brokerURL: (location.protocol==='https:'?'wss://':'ws://') + location.host + '/ws' });
window.phClient = client;
const panels = {generator:{}, moderator:{}, processor:{}, postprocessor:{}};
const instances = {};
const loaded = {generator:{}, moderator:{}, processor:{}, postprocessor:{}};

const modal = document.getElementById('panel-modal');
const modalTitle = document.getElementById('panel-title');
const modalBody = document.getElementById('panel-body');
const closeBtn = document.getElementById('panel-close');
if(closeBtn) closeBtn.addEventListener('click', ()=>{ if(modal) modal.style.display='none'; });
if(modal) modal.addEventListener('click', e=>{ if(e.target===modal) modal.style.display='none'; });
client.onConnect = ()=>{
  // discover services via status-full events
  client.subscribe('/exchange/ph.control/ev.status-full.#', msg=>{
    try{
      const body = JSON.parse(msg.body||'{}');
      const role = body.role || body.name || body.service;
      const inst = body.instance || body.id;
      if(role && inst){
        instances[role] = {id:inst, name: body.name || role};
        if(!loaded[role][inst]){
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
  import(`../../modules/${role}.js`).then(mod=>{
    const fn = mod[`render${role.charAt(0).toUpperCase()+role.slice(1)}Panel`];
    if(typeof fn==='function'){
      const el = document.createElement('div');
      panels[role][id] = el;
      fn(el, id);
    }
  }).catch(err=>console.error('module load', err));
}

function showPanel(role){
  const inst = instances[role];
  if(!inst || !modal || !modalBody || !modalTitle) return;
  const el = panels[role] && panels[role][inst.id];
  if(!el) return;
  modalBody.innerHTML='';
  modalBody.appendChild(el);
  modalTitle.textContent = `${inst.name || role} (${inst.id})`;
  modal.style.display='flex';
  try{
    const payload = {type:'status-request', role, instance:inst.id};
    client.publish({destination:`/exchange/ph.control/sig.status-request.${role}.${inst.id}`, body:JSON.stringify(payload)});
  }catch(err){ console.error('status-request', err); }
}
window.phShowPanel = showPanel;
