// PocketHive – Swarm UI controller (tabs in top menu)
(function(){
  const root = document.querySelector('.ph-bg-bees') || document.body;
  if(!root) return;

  // Navbar removed: rely on the header with logo and external button

  // Modal
  const modal = document.createElement('div');
  modal.className = 'ph-modal hidden';
  modal.innerHTML = `
    <div class="ph-modal-dialog">
      <div class="ph-modal-head">
        <strong>Swarm Background</strong>
        <button id="ph-close" class="ph-x">×</button>
      </div>
      <div class="ph-tabs">
        <button class="ph-tab active" data-tab="bezier">Bezier</button>
        <button class="ph-tab" data-tab="sine">Sine (C64)</button>
        <button class="ph-tab" data-tab="matrix">Matrix down</button>
        <span class="ph-spacer"></span>
        <button id="ph-preset-low"  class="ph-small">Low</button>
        <button id="ph-preset-med"  class="ph-small">Med</button>
        <button id="ph-preset-high" class="ph-small">High</button>
      </div>

      <div class="ph-tabpanes">
        <section class="ph-pane active" data-pane="bezier">
          <div class="grid">
            <label>Kolor
              <select id="v_bezier_color"><option value="amber">Amber</option><option value="green">Matrix Green</option></select>
            </label>
            <label>Gęstość <input id="v_bezier_density" type="range" min="0" max="240" step="10" value="20"></label>
            <label>Glify (szybkość) <input id="v_bezier_glyph" type="range" min="0.3" max="1.5" step="0.05" value="0.75"></label>
            <label>Smuga (ilość) <input id="v_bezier_trailCount" type="range" min="0" max="20" step="1" value="9"></label>
            <label>Smuga (wygaszanie) <input id="v_bezier_trailDecay" type="range" min="0" max="1" step="0.05" value="1"></label>
            <label>Base lag (s) <input id="v_bezier_base" type="range" min="0" max="0.8" step="0.02" value="0.12"></label>
            <label>Step lag (s) <input id="v_bezier_step" type="range" min="0" max="0.25" step="0.01" value="0.06"></label>
            <label>Blob speed (s) <input id="v_bezier_blob" type="range" min="8" max="60" step="1" value="28"></label>
            <label>Dur min (s) <input id="v_bezier_durmin" type="range" min="2" max="30" step="1" value="8"></label>
            <label>Dur max (s) <input id="v_bezier_durmax" type="range" min="4" max="40" step="1" value="16"></label>
          </div>
          <div class="row">
            <button id="v_bezier_apply" class="ph-btn primary">Apply</button>
            <button id="v_bezier_reseed" class="ph-btn">Reseed</button>
          </div>
        </section>

        <section class="ph-pane c64" data-pane="sine">
          <div class="c64-banner">C64 SINE MODE</div>
          <div class="grid">
            <label>Kolor
              <select id="v_sine_color"><option value="amber">Amber</option><option value="green">Matrix Green</option></select>
            </label>
            <label>Gęstość <input id="v_sine_density" type="range" min="0" max="240" step="10" value="40"></label>
            <label>Amplitude (% H) <input id="v_sine_ampfrac" type="range" min="0.05" max="0.49" step="0.01" value="0.42"></label>
            <label>Wavelength (px) <input id="v_sine_wavelength" type="range" min="40" max="800" step="10" value="240"></label>
            <label>Speed (px/s) <input id="v_sine_speed" type="range" min="20" max="600" step="10" value="160"></label>
            <label class="ph-toggle"><input id="v_sine_quantize" type="checkbox" checked> Quantize positions</label>
          </div>
          <div class="row">
            <button id="v_sine_apply" class="ph-btn primary">Apply</button>
            <button id="v_sine_reseed" class="ph-btn">Reseed</button>
          </div>
        </section>

        <section class="ph-pane" data-pane="matrix">
          <div class="grid">
            <label>Kolor
              <select id="v_matrix_color"><option value="amber">Amber</option><option value="green">Matrix Green</option></select>
            </label>
            <label>Gęstość <input id="v_matrix_density" type="range" min="0" max="240" step="10" value="20"></label>
            <label>Glify (szybkość) <input id="v_matrix_glyph" type="range" min="0.3" max="1.5" step="0.05" value="0.75"></label>
            <label>Smuga (ilość) <input id="v_matrix_trailCount" type="range" min="0" max="20" step="1" value="9"></label>
            <label>Smuga (wygaszanie) <input id="v_matrix_trailDecay" type="range" min="0" max="1" step="0.05" value="1"></label>
            <label>Base lag (s) <input id="v_matrix_base" type="range" min="0" max="0.8" step="0.02" value="0.12"></label>
            <label>Step lag (s) <input id="v_matrix_step" type="range" min="0" max="0.25" step="0.01" value="0.06"></label>
            <label>Blob speed (s) <input id="v_matrix_blob" type="range" min="8" max="60" step="1" value="28"></label>
            <label>Vy min (px/s) <input id="v_matrix_vymin" type="range" min="10" max="300" step="5" value="80"></label>
            <label>Vy max (px/s) <input id="v_matrix_vymax" type="range" min="20" max="600" step="5" value="220"></label>
          </div>
          <div class="row">
            <button id="v_matrix_apply" class="ph-btn primary">Apply</button>
            <button id="v_matrix_reseed" class="ph-btn">Reseed</button>
          </div>
        </section>
      </div>
    </div>`;
  root.appendChild(modal);

  // Styles for navbar & modal
  const style = document.createElement('style');
  style.textContent = `
  .ph-navbar{ position:fixed; inset:0 0 auto 0; height:52px; display:flex; align-items:center;
    justify-content:space-between; padding:0 16px; background: rgba(15,17,22,.6); backdrop-filter: blur(8px);
    border-bottom:1px solid rgba(255,255,255,.08); z-index:3; color:#fff; font:600 14px/1 Inter, system-ui, Arial, sans-serif; }
  .ph-nav-left{ letter-spacing:.03em }
  .ph-nav-right{ display:flex; align-items:center; gap:10px; }
  .ph-btn{ appearance:none; border:1px solid rgba(255,255,255,.2); background: rgba(255,255,255,.08); color:#fff;
    padding:8px 12px; border-radius:10px; cursor:pointer; }
  .ph-btn.primary{ border-color: transparent; background:#FFC107; color:#111; font-weight:800; }
  .ph-small{ appearance:none; border:1px solid rgba(255,255,255,.2); background: rgba(255,255,255,.08); color:#fff;
    padding:6px 10px; border-radius:10px; cursor:pointer; font-size:12px; }
  .ph-toggle{ display:inline-flex; align-items:center; gap:8px; padding:6px 10px; border:1px solid rgba(255,255,255,.2); border-radius:10px; background: rgba(255,255,255,.08); font-size:12px; }
  .ph-toggle input{ accent-color:#FFC107; }
  .ph-modal{ position:fixed; inset:0; display:grid; place-items:center; background: rgba(0,0,0,.35);
    z-index:4; }
  .ph-modal.hidden{ display:none; }
  .ph-modal-dialog{ width:min(900px, 92vw); max-height:86vh; overflow:auto; border-radius:16px;
    background: rgba(15,17,22,.85); border:1px solid rgba(255,255,255,.12); color:#fff; box-shadow:0 30px 80px rgba(0,0,0,.45); }
  .ph-modal-head{ display:flex; align-items:center; justify-content:space-between; padding:14px 16px; border-bottom:1px solid rgba(255,255,255,.1); }
  .ph-x{ appearance:none; border:0; background:transparent; color:#fff; font-size:24px; cursor:pointer; }
  .ph-tabs{ display:flex; align-items:center; gap:6px; padding:10px 12px; border-bottom:1px solid rgba(255,255,255,.08); }
  .ph-tab{ appearance:none; border:1px solid rgba(255,255,255,.2); background: rgba(255,255,255,.06); color:#fff;
    padding:6px 10px; border-radius:10px; cursor:pointer; }
  .ph-tab.active{ background:#FFC107; color:#111; border-color:transparent; font-weight:800; }
  .ph-spacer{ flex:1 1 auto; }
  .ph-pane{ display:none; padding:14px; }
  .ph-pane.active{ display:block; }
  /* C64 styling cues for Sine pane */
  .ph-pane.c64{ border:1px solid rgba(65,166,246,.25); background: linear-gradient(180deg, rgba(10,14,22,.85), rgba(8,10,16,.85)); }
  .ph-pane.c64 .c64-banner{ display:inline-block; margin:4px 0 10px; padding:6px 10px; border:1px solid rgba(65,166,246,.5);
    color:#41A6F6; text-shadow:0 0 8px rgba(65,166,246,.55); border-radius:8px; font:700 12px/1.1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing:.08em; }
  .ph-pane.c64 label{ font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
  .ph-pane.c64 input[type="range"]{ accent-color:#41A6F6; }
  .grid{ display:grid; grid-template-columns: repeat(2, minmax(200px, 1fr)); gap:12px 14px; }
  .row{ display:flex; gap:8px; padding:12px 2px; }
  label{ display:flex; align-items:center; justify-content:space-between; gap:12px; }
  input[type="range"]{ width: 180px; }
  select{ appearance:none; background: rgba(255,255,255,.08); color:#fff; border:1px solid rgba(255,255,255,.2); border-radius:10px; padding:6px 10px; }
  `;
  document.head.appendChild(style);

  // Background mode toggle
  const body = document.body;
  const oldBg = document.getElementById('ph-old-bg');
  function applyBgMode(mode){
    const swarmBtn = document.getElementById('ph-open-swarm');
    const swarmBtn2 = document.getElementById('ph-swarm-options');
    if(mode === 'old'){
      body.classList.add('ph-bg-old');
      body.classList.remove('ph-bg-bees');
      if(swarmBtn){ swarmBtn.disabled = true; swarmBtn.style.display = 'none'; }
      if(swarmBtn2){ swarmBtn2.disabled = true; swarmBtn2.style.display = 'none'; }
    } else {
      body.classList.add('ph-bg-bees');
      body.classList.remove('ph-bg-old');
      if(swarmBtn){ swarmBtn.disabled = false; swarmBtn.style.display = ''; }
      if(swarmBtn2){ swarmBtn2.disabled = false; swarmBtn2.style.display = ''; }
    }
    try { localStorage.setItem('ph-bg-mode', mode); } catch(e) {}
  }
  let saved = 'bees';
  try { saved = localStorage.getItem('ph-bg-mode') || 'bees'; } catch(e) {}
  if(oldBg){
    oldBg.checked = (saved === 'old');
    applyBgMode(saved);
    oldBg.addEventListener('change', ()=> applyBgMode(oldBg.checked ? 'old' : 'bees'));
  } else {
    applyBgMode(saved);
  }

  // Open/Close
  const openBtn = document.getElementById('ph-open-swarm');
  if(openBtn) openBtn.addEventListener('click', ()=> modal.classList.remove('hidden'));
  const swarmProxyBtn = document.getElementById('ph-swarm-options');
  if(swarmProxyBtn){ swarmProxyBtn.addEventListener('click', ()=> modal.classList.remove('hidden')); }
  modal.querySelector('#ph-close').addEventListener('click', ()=> modal.classList.add('hidden'));
  modal.addEventListener('click', (e)=>{ if(e.target === modal) modal.classList.add('hidden'); });

  // Tabs
  const tabs = modal.querySelectorAll('.ph-tab');
  const panes = modal.querySelectorAll('.ph-pane');
  tabs.forEach(t => t.addEventListener('click', ()=>{
    tabs.forEach(x=>x.classList.remove('active')); panes.forEach(p=>p.classList.remove('active'));
    t.classList.add('active'); modal.querySelector(`.ph-pane[data-pane="${t.dataset.tab}"]`).classList.add('active');
    autoApply(`v_${t.dataset.tab}`, true);
  }));

  // Helpers
  function val(id){ const el=document.getElementById(id); return el.type==='range'? Number(el.value): el.value; }
  function applyCommon(prefix, includeDensity=false){
    const getEl = (id)=> document.getElementById(id);
    const vEl = getEl(`${prefix}_color`);
    const dEl = getEl(`${prefix}_density`);
    const gEl = getEl(`${prefix}_glyph`);
    const tcEl = getEl(`${prefix}_trailCount`);
    const tdEl = getEl(`${prefix}_trailDecay`);
    const baseEl = getEl(`${prefix}_base`);
    const stepEl = getEl(`${prefix}_step`);
    const blobEl = getEl(`${prefix}_blob`);
    if(vEl) window.PocketHiveBees.setVariant(vEl.value);
    if(includeDensity && dEl) window.PocketHiveBees.setDensity(Number(dEl.value));
    if(gEl) window.PocketHiveBees.setGlyphSpeedFactor(Number(gEl.value));
    if(tcEl || baseEl || stepEl){
      const tc = tcEl? Number(tcEl.value) : undefined;
      const base = baseEl? Number(baseEl.value) : undefined;
      const step = stepEl? Number(stepEl.value) : undefined;
      window.PocketHiveBees.setTrail(tc, base, step);
    }
    if(tdEl) window.PocketHiveBees.setTrailDecay(Number(tdEl.value));
    if(blobEl) window.PocketHiveBees.setBlobSpeed(Number(blobEl.value));
  }

  // Live preview: apply values immediately when controls change
  function autoApply(prefix, includeDensity=false){
    applyCommon(prefix, includeDensity);
    if(prefix.includes('bezier')){
      window.PocketHiveBees.setPattern('bezier');
      window.PocketHiveBees.setBezierParams({ durMin: val('v_bezier_durmin'), durMax: val('v_bezier_durmax') });
    } else if(prefix.includes('sine')){
      window.PocketHiveBees.setPattern('sine');
      const qEl = document.getElementById('v_sine_quantize');
      window.PocketHiveBees.setSineParams({
        ampFrac: val('v_sine_ampfrac'),
        wavelength: val('v_sine_wavelength'),
        speed: val('v_sine_speed'),
        quantize: !!(qEl && qEl.checked)
      });
    } else if(prefix.includes('matrix')){
      window.PocketHiveBees.setPattern('matrix');
      window.PocketHiveBees.setMatrixParams({ vyMin: val('v_matrix_vymin'), vyMax: val('v_matrix_vymax') });
    }
  }

  // Bezier
  // Hide/disable Apply buttons and rely on live preview
  const bezierApply = document.getElementById('v_bezier_apply');
  if(bezierApply){ bezierApply.disabled = true; bezierApply.style.display='none'; }
  // Live preview on change within Bezier pane (density only on change)
  modal.querySelector('[data-pane="bezier"]').addEventListener('input', ()=> autoApply('v_bezier', false));
  modal.querySelector('[data-pane="bezier"]').addEventListener('change', ()=> autoApply('v_bezier', true));
  document.getElementById('v_bezier_reseed').addEventListener('click', ()=> window.PocketHiveBees.reseed());

  // Sine
  const sineApply = document.getElementById('v_sine_apply');
  if(sineApply){ sineApply.disabled = true; sineApply.style.display='none'; }
  // Live preview on change within Sine pane (density only on change)
  modal.querySelector('[data-pane="sine"]').addEventListener('input', ()=> autoApply('v_sine', false));
  modal.querySelector('[data-pane="sine"]').addEventListener('change', ()=> autoApply('v_sine', true));
  document.getElementById('v_sine_reseed').addEventListener('click', ()=> window.PocketHiveBees.reseed());

  // Matrix
  const matrixApply = document.getElementById('v_matrix_apply');
  if(matrixApply){ matrixApply.disabled = true; matrixApply.style.display='none'; }
  // Live preview on change within Matrix pane (density only on change)
  modal.querySelector('[data-pane="matrix"]').addEventListener('input', ()=> autoApply('v_matrix', false));
  modal.querySelector('[data-pane="matrix"]').addEventListener('change', ()=> autoApply('v_matrix', true));
  document.getElementById('v_matrix_reseed').addEventListener('click', ()=> window.PocketHiveBees.reseed());

  // Presets
  function preset(lowMedHigh){
    if(lowMedHigh==='low'){
      window.PocketHiveBees.setDensity(12);
      window.PocketHiveBees.setGlyphSpeedFactor(0.9);
      window.PocketHiveBees.setTrail(6, 0.10, 0.05);
      window.PocketHiveBees.setTrailDecay(0.7);
    }else if(lowMedHigh==='med'){
      window.PocketHiveBees.setDensity(60);
      window.PocketHiveBees.setGlyphSpeedFactor(0.75);
      window.PocketHiveBees.setTrail(9, 0.12, 0.06);
      window.PocketHiveBees.setTrailDecay(1.0);
    }else{
      window.PocketHiveBees.setDensity(140);
      window.PocketHiveBees.setGlyphSpeedFactor(0.6);
      window.PocketHiveBees.setTrail(12, 0.14, 0.06);
      window.PocketHiveBees.setTrailDecay(1.0);
    }
    window.PocketHiveBees.reseed();
  }
  document.getElementById('ph-preset-low').addEventListener('click', ()=> preset('low'));
  document.getElementById('ph-preset-med').addEventListener('click', ()=> preset('med'));
  document.getElementById('ph-preset-high').addEventListener('click', ()=> preset('high'));
})();
