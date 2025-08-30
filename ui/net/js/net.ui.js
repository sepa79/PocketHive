// PocketHive – Network UI (v4: logo float controls)
(function(){
  const root = document.querySelector('.ph-bg-net') || document.body;
  if(!root) return;

  const modal = document.createElement('div');
  modal.className = 'ph-modal hidden';
  modal.innerHTML = `
    <div class="ph-modal-dialog">
      <div class="ph-modal-head">
        <strong>Network Background</strong>
        <button id="ph-close" class="ph-x" aria-label="Close">×</button>
      </div>
      <div class="ph-tabs">
        <button class="ph-tab active" data-tab="net">Network</button>
        <span class="ph-spacer"></span>
        <button id="ph-preset-low"  class="ph-small">Low</button>
        <button id="ph-preset-med"  class="ph-small">Med</button>
        <button id="ph-preset-high" class="ph-small">High</button>
      </div>
      <section class="ph-pane active" data-pane="net">
        <div class="grid">
          <label>Kolor
            <select id="net_color">
              <option value="cyan" selected>Cyan (default)</option>
              <option value="amber">Amber</option>
              <option value="green">Matrix Green</option>
            </select>
          </label>
          <label>Gęstość <input id="net_density" type="range" min="0" max="300" step="6" value="54"></label>
          <label>Rozmiar węzła <input id="net_nodesize" type="range" min="1" max="6" step="0.2" value="2.6"></label>
          <label>Link distance (px) <input id="net_linkdist" type="range" min="60" max="360" step="10" value="200"></label>
          <label>K-sąsiedzi <input id="net_k" type="range" min="1" max="10" step="1" value="5"></label>
          <label>Dodatkowe linki / węzeł <input id="net_extra" type="range" min="0" max="6" step="1" value="2"></label>
          <label>Wymuś spójność <input id="net_force" type="checkbox" checked></label>
          <label>Pulse speed <input id="net_pulse" type="range" min="0" max="2" step="0.05" value="0.9"></label>
          <label>Edge thickness <input id="net_edge" type="range" min="0.5" max="3" step="0.05" value="1.0"></label>
          <label>Glow strength <input id="net_glow" type="range" min="0" max="36" step="1" value="14"></label>

          <label>Logo scale <input id="net_logo_scale" type="range" min="0.2" max="2.0" step="0.02" value="1.2"></label>
          <label>Logo opacity <input id="net_logo_op" type="range" min="0.02" max="0.6" step="0.02" value="0.12"></label>
          <label>Logo blend
            <select id="net_logo_blend">
              <option value="normal" selected>normal</option>
              <option value="lighten">lighten</option>
              <option value="screen">screen</option>
            </select>
          </label>

          <label>Float X amp (px) <input id="f_amp_x" type="range" min="0" max="60" step="1" value="8"></label>
          <label>Float Y amp (px) <input id="f_amp_y" type="range" min="0" max="60" step="1" value="12"></label>
          <label>Float Z amp (px) <input id="f_amp_z" type="range" min="0" max="120" step="2" value="30"></label>

          <label>Float X speed <input id="f_spd_x" type="range" min="0.05" max="2.0" step="0.05" value="0.30"></label>
          <label>Float Y speed <input id="f_spd_y" type="range" min="0.05" max="2.0" step="0.05" value="0.45"></label>
          <label>Float Z speed <input id="f_spd_z" type="range" min="0.05" max="2.0" step="0.05" value="0.25"></label>

          <label>Skew amp (deg) <input id="skew_amp" type="range" min="0" max="8" step="0.1" value="1.2"></label>
          <label>Skew speed <input id="skew_spd" type="range" min="0.05" max="2.0" step="0.05" value="0.35"></label>

          <label>Center hole <input id="net_hole" type="range" min="0.10" max="0.45" step="0.01" value="0.26"></label>
        </div>
        <div class="row">
          <button id="net_apply" class="ph-btn primary" style="display:none" disabled>Apply</button>
          <button id="net_reseed" class="ph-btn">Reseed</button>
        </div>
      </section>
    </div>`;
  root.appendChild(modal);

  function open(){ modal.classList.remove('hidden'); }
  function close(){ modal.classList.add('hidden'); }
  modal.querySelector('#ph-close').addEventListener('click', close);
  modal.addEventListener('click', (e)=>{ if(e.target === modal) close(); });
  window.PocketHiveNet = window.PocketHiveNet || {};
  window.PocketHiveNet.openOptions = open;

  const $ = sel => modal.querySelector(sel);
  const val = id => { const el = $('#'+id); return el.type==='range'? Number(el.value) : (el.type==='checkbox'? el.checked : el.value); };

  function applyAll(){
    try{
      window.PocketHiveNet.setTheme(val('net_color'));
      window.PocketHiveNet.setDensity(val('net_density'));
      window.PocketHiveNet.setNodeSize(val('net_nodesize'));
      window.PocketHiveNet.setLinking(val('net_linkdist'), val('net_k'), val('net_extra'), val('net_force'));
      window.PocketHiveNet.setPulseSpeed(val('net_pulse'));
      window.PocketHiveNet.setEdgeThickness(val('net_edge'));
      window.PocketHiveNet.setGlow(val('net_glow'));
      window.PocketHiveNet.setLogoScale(val('net_logo_scale'));
      window.PocketHiveNet.setLogoOpacity(val('net_logo_op'));
      window.PocketHiveNet.setLogoBlend(val('net_logo_blend'));
      window.PocketHiveNet.setLogoFloat(
        {x: val('f_amp_x'), y: val('f_amp_y'), z: val('f_amp_z'), skew: val('skew_amp')},
        {x: val('f_spd_x'), y: val('f_spd_y'), z: val('f_spd_z'), skew: val('skew_spd')}
      );
      window.PocketHiveNet.setCenterHole(val('net_hole'));
    }catch(e){ /* net not ready yet */ }
  }
  // Auto-apply on any change
  modal.addEventListener('input', applyAll);
  modal.addEventListener('change', applyAll);
  // Initial sync
  applyAll();
  $('#net_reseed').addEventListener('click', ()=> window.PocketHiveNet.reseed());

  function preset(level){
    if(level==='low'){
      window.PocketHiveNet.setDensity(30);
      window.PocketHiveNet.setLinking(180, 3, 1, true);
      window.PocketHiveNet.setPulseSpeed(0.8);
      window.PocketHiveNet.setDrift(14, 0.20);
      window.PocketHiveNet.setEdgeThickness(0.9);
    }else if(level==='med'){
      window.PocketHiveNet.setDensity(54);
      window.PocketHiveNet.setLinking(200, 5, 2, true);
      window.PocketHiveNet.setPulseSpeed(0.9);
      window.PocketHiveNet.setDrift(18, 0.22);
      window.PocketHiveNet.setEdgeThickness(1.0);
    }else{
      window.PocketHiveNet.setDensity(120);
      window.PocketHiveNet.setLinking(220, 6, 3, true);
      window.PocketHiveNet.setPulseSpeed(1.1);
      window.PocketHiveNet.setDrift(24, 0.28);
      window.PocketHiveNet.setEdgeThickness(1.2);
    }
    window.PocketHiveNet.reseed();
  }
  document.getElementById('ph-preset-low').addEventListener('click', ()=> preset('low'));
  document.getElementById('ph-preset-med').addEventListener('click', ()=> preset('med'));
  document.getElementById('ph-preset-high').addEventListener('click', ()=> preset('high'));
})();
