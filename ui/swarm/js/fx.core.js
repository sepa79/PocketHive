// PocketHive – New Background FX (subtle particles/waves)
(function(){
  const root = document.body;
  if(!root) return;
  function q(sel){ return document.querySelector(sel); }
  let canvas, ctx, raf=0, particles=[];
  let opts = { count: 80, speed: 0.12, hue: 46, alpha: 0.14 };

  function ensureLayer(){
    if(!q('.ph-bg-fx')) return false;
    let layer = q('.fx-layer');
    if(!layer){
      layer = document.createElement('div'); layer.className = 'fx-layer';
      canvas = document.createElement('canvas'); canvas.className='fx-canvas'; layer.appendChild(canvas);
      root.appendChild(layer);
      ctx = canvas.getContext('2d');
      resize();
      seed(opts.count);
      start();
    }
    return true;
  }
  function destroy(){ cancelAnimationFrame(raf); raf=0; const layer=q('.fx-layer'); if(layer) layer.remove(); particles=[]; }
  function resize(){ if(!canvas) return; const dpr=window.devicePixelRatio||1; canvas.width=innerWidth*dpr; canvas.height=innerHeight*dpr; canvas.style.width=innerWidth+'px'; canvas.style.height=innerHeight+'px'; if(ctx) ctx.setTransform(dpr,0,0,dpr,0,0); }
  function seed(n){ particles = Array.from({length:n}, (_,i)=>({ x:Math.random()*innerWidth, y:Math.random()*innerHeight, r: 0.6+Math.random()*1.8, a: Math.random()*Math.PI*2, v: opts.speed*(0.5+Math.random()), k: i%2?1:-1 })); }
  function step(t){ if(!ctx) return; ctx.clearRect(0,0,innerWidth,innerHeight); const c=`hsla(${opts.hue}, 95%, 55%, ${opts.alpha})`; ctx.fillStyle=c; ctx.strokeStyle=c; for(const p of particles){ p.a += 0.005*p.k; p.x += Math.cos(p.a)*0.3; p.y += Math.sin(p.a*0.7)*0.25; if(p.x<-20) p.x=innerWidth+20; if(p.x>innerWidth+20) p.x=-20; if(p.y<-20) p.y=innerHeight+20; if(p.y>innerHeight+20) p.y=-20; ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI*2); ctx.fill(); }
    raf = requestAnimationFrame(step);
  }
  function start(){ if(!raf) raf = requestAnimationFrame(step); }

  // Public API
  window.PocketHiveFX = {
    activate(){ if(ensureLayer()) return; },
    deactivate(){ destroy(); },
    setIntensity(n){ n=Math.max(0,Math.min(200,Number(n)||0)); opts.count=n; seed(n); },
    setSpeed(s){ s=Math.max(0.02, Math.min(1.0, Number(s)||0.12)); opts.speed=s; particles.forEach(p=> p.v = opts.speed*(0.5+Math.random())); },
    setHue(h){ opts.hue = Math.max(0, Math.min(360, Number(h)||46)); },
    setAlpha(a){ opts.alpha = Math.max(0.02, Math.min(0.6, Number(a)||0.14)); },
  };

  // React to class changes
  const obs = new MutationObserver(()=>{ if(q('.ph-bg-fx')){ ensureLayer(); } else { destroy(); } });
  obs.observe(document.documentElement, {subtree:true, attributes:true, attributeFilter:['class']});
  window.addEventListener('resize', resize, {passive:true});

  // If already in FX mode at load, activate
  if(q('.ph-bg-fx')) ensureLayer();
})();
