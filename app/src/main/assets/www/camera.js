/* PupCamera — interface. Rien ne s'arrête à cause de la chaleur : on affiche juste le chiot qui transpire. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon;
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Cam;
  const C = window.Cam || mock();
  const call = (fn, ...a) => { try { return C[fn] ? C[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const root = document.documentElement.style;
  root.setProperty('--acc', '#ff3fa4'); root.setProperty('--acc2', '#29e6ff');

  let st = {}, lenses = J(call('lenses'), []) || [], timer = +(localStorage.getItem('pc_timer') || 0), therm = { status: 0, temp: -1 };
  let recTimer = null, counting = false, lastLevel = 0;
  const haptic = () => call('haptic');
  const QL = { '8k30': '8K · 30', '4k60': '4K · 60', '4k30': '4K · 30', '1080p60': '1080p · 60', '1080p30': '1080p · 30' };
  const fmt = (ms) => { const s = Math.floor(ms / 1000); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : String(m).padStart(2, '0')) + ':' + String(x).padStart(2, '0'); };

  // ------------------------------------------------------------------ rendu
  function renderTop() {
    const f = st.flash || 'off';
    const fb = $('[data-a=flash]');
    fb.hidden = st.hasFlash === false;
    fb.innerHTML = I('bolt', f === 'off' ? 'chrome' : 'amber', { shape: 'none', off: f === 'off', auto: f === 'auto' });
    fb.classList.toggle('on', f !== 'off');
    $('[data-a=timer]').innerHTML = I('timer', timer ? 'cyan' : 'chrome', { shape: 'none', n: timer || '' });
    $('[data-a=timer]').hidden = !!st.video;
    $('[data-a=ratio]').textContent = st.video ? (st.quality || '').replace(' i/s', '').replace(' · ', '·') : (st.ratio || '4:3');
    $('[data-a=grid]').innerHTML = I('grid', st.grid ? 'cyan' : 'chrome', { shape: 'none' });
    $('[data-a=grid]').classList.toggle('on', !!st.grid);
    $('[data-a=settings]').innerHTML = I('gear', 'violet', { shape: 'none' });
    $('#gridl').hidden = !st.grid;
    document.body.classList.toggle('video', !!st.video);
    $$('#modes button').forEach((b) => b.classList.toggle('on', (b.dataset.m === 'video') === !!st.video));
    $('#modes').style.display = st.capture ? 'none' : '';
    $('#flip').innerHTML = I('switchcam', 'cyan', { shape: 'none' });
    renderThumb();
    renderLenses();
  }
  function renderThumb() {
    const t = $('#thumb');
    if (document.body.classList.contains('rec')) { t.innerHTML = I(recPaused ? 'play' : 'pause', 'chrome', { shape: 'none' }); return; }
    t.innerHTML = st.hasLast && !MOCK ? `<img src="https://pupcam.local/last?t=${Date.now()}" alt="">` : I('gallery', 'violet', { shape: 'none' });
  }
  function lensPills() {
    const side = lenses.filter((l) => !!l.front === !!st.front);
    if (!side.length) return [];
    const prim = side[0];
    const pills = [];
    const r = (z) => Math.round(z * 10) / 10;
    if (prim.zmin < 0.95) pills.push({ id: prim.id, z: prim.zmin, label: String(r(prim.zmin)).replace(/^0/, ''), sub: 'ultra' });
    pills.push({ id: prim.id, z: 1, label: '1×', sub: prim.mp ? prim.mp + ' Mpx' : '' });
    if (prim.zmax >= 2) pills.push({ id: prim.id, z: 2, label: '2' });
    if (prim.zmax >= 3) pills.push({ id: prim.id, z: 3, label: '3', sub: 'télé' });
    if (prim.zmax >= 10 && !st.front) pills.push({ id: prim.id, z: 10, label: '10' });
    side.slice(1).forEach((l) => {
      const ratio = prim.eq && l.eq ? l.eq / prim.eq : 1;
      pills.push({ id: l.id, z: 1, phys: true, label: (Math.round(ratio * 10) / 10 + '').replace(/^0/, '') + '×', sub: (l.eq || '?') + ' mm' });
    });
    return pills;
  }
  function renderLenses() {
    const pills = lensPills();
    let best = -1, bd = 1e9;
    pills.forEach((p, i) => { if (p.id !== st.cam) return; const d = Math.abs(Math.log((st.zoom || 1) / p.z)); if (d < bd) { bd = d; best = i; } });
    $('#lenses').innerHTML = pills.map((p, i) => `<button class="lens ${i === best ? 'on' : ''} ${p.phys ? 'phys' : ''}" data-lens="${i}" type="button">${i === best && Math.abs((st.zoom || 1) - p.z) > 0.05 && !p.phys ? (Math.round(st.zoom * 10) / 10) + '×' : p.label}${p.sub ? `<small>${p.sub}</small>` : ''}</button>`).join('');
    $('#lenses').hidden = !pills.length;
    $('#lenses')._pills = pills;
  }
  function renderTherm() {
    const t = therm.temp, s = therm.status || 0;
    let lv = 0;
    if (s >= 1 || t >= 38) lv = 1;
    if (s >= 2 || t >= 41) lv = 2;
    if (s >= 3 || t >= 44) lv = 3;
    if (s >= 4 || t >= 47) lv = 4;
    const el = $('#therm');
    el.hidden = lv === 0;
    el.className = 'therm l' + lv;
    $('#thi').innerHTML = I('pupface', lv >= 3 ? 'red' : lv >= 2 ? 'amber' : 'pink', { shape: 'none', level: lv });
    $('#tht').textContent = t > 0 ? Math.round(t) + '°' : '';
    if (lv >= 3 && lastLevel < 3) haptic();
    lastLevel = lv;
  }

  // ------------------------------------------------------------------ déclencheur
  let recPaused = false;
  function shutter() {
    if (counting) return;
    haptic();
    if (!st.video && timer && !document.body.classList.contains('rec')) {
      counting = true;
      let n = timer;
      const c = $('#count');
      c.hidden = false; c.textContent = n;
      const iv = setInterval(() => {
        n--;
        if (n <= 0) { clearInterval(iv); c.hidden = true; counting = false; fire(); }
        else c.textContent = n;
      }, 1000);
      return;
    }
    fire();
  }
  function fire() {
    call('shutter');
    if (!st.video) { const f = $('#flashfx'); f.classList.remove('go'); void f.offsetWidth; f.classList.add('go'); }
    if (MOCK && st.video) on('rec', JSON.stringify({ q: '4K · 60 i/s', codec: 'HEVC', mbps: 80 }));
  }
  function startRecUi(info) {
    document.body.classList.add('rec');
    recPaused = false;
    $('#recchip').hidden = false;
    $('#recchip').classList.remove('paused');
    $('#recsafe').textContent = '🛟 sauvegarde en direct';
    $('#recq').textContent = info.q ? info.q.replace(' i/s', '') + ' · ' + info.codec + ' · ' + info.mbps + ' Mb/s' : '';
    $('#lenses').style.visibility = 'hidden';
    $('#modes').style.visibility = 'hidden';
    renderThumb();
    clearInterval(recTimer);
    const t0 = Date.now();
    recTimer = setInterval(() => { $('#rect').textContent = fmt(MOCK ? Date.now() - t0 : +call('recTime') || 0); }, 400);
  }
  function stopRecUi() {
    document.body.classList.remove('rec');
    $('#recchip').hidden = true;
    $('#lenses').style.visibility = '';
    $('#modes').style.visibility = '';
    clearInterval(recTimer);
    renderThumb();
  }

  // ------------------------------------------------------------------ gestes sur l'aperçu
  const pv = $('#pv');
  const ptrs = new Map();
  let g = null, ringT = null, zbT = null;
  pv.addEventListener('pointerdown', (e) => {
    pv.setPointerCapture(e.pointerId);
    ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (ptrs.size === 2) { const [a, b] = [...ptrs.values()]; g = { pinch: true, d: Math.hypot(a.x - b.x, a.y - b.y), z: st.zoom || 1 }; }
    else g = { x: e.clientX, y: e.clientY, t: Date.now() };
  });
  pv.addEventListener('pointermove', (e) => {
    if (!ptrs.has(e.pointerId) || !g) return;
    ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (g.pinch && ptrs.size >= 2) {
      const [a, b] = [...ptrs.values()];
      const z = Math.max(st.zmin || 1, Math.min(st.zmax || 8, g.z * Math.hypot(a.x - b.x, a.y - b.y) / g.d));
      st.zoom = z;
      call('zoom', z);
      const zb = $('#zbub'); zb.hidden = false; zb.textContent = (Math.round(z * 10) / 10) + '×';
      clearTimeout(zbT); zbT = setTimeout(() => { zb.hidden = true; }, 900);
      renderLenses();
    }
  });
  const up = (e) => {
    if (!ptrs.has(e.pointerId)) return;
    ptrs.delete(e.pointerId);
    if (!g) return;
    if (g.pinch) { if (!ptrs.size) g = null; return; }
    const dx = e.clientX - g.x, dy = e.clientY - g.y;
    if (Math.abs(dx) > 90 && Math.abs(dx) > Math.abs(dy) * 1.5 && !document.body.classList.contains('rec') && !st.capture) setMode(dx < 0);
    else if (Math.hypot(dx, dy) < 12) tapFocus(e.clientX, e.clientY);
    g = null;
  };
  pv.addEventListener('pointerup', up);
  pv.addEventListener('pointercancel', up);
  function tapFocus(x, y) {
    const r = pv.getBoundingClientRect();
    const nx = (x - r.left) / r.width, ny = (y - r.top) / r.height;
    call('focus', nx, ny);
    const ring = $('#ring');
    ring.hidden = true; void ring.offsetWidth;
    ring.style.left = (x - r.left) + 'px'; ring.style.top = (y - r.top) + 'px'; ring.hidden = false;
    const evs = $('#evs');
    if ((st.evmax || 0) > 0) {
      evs.hidden = false;
      evs.style.left = Math.min(r.width - 40, x - r.left + 60) + 'px';
      evs.style.top = (y - r.top + 75) + 'px';
      const ev = $('#ev'); ev.min = st.evmin; ev.max = st.evmax; ev.value = st.ev || 0;
      ev.style.setProperty('--v', ((ev.value - ev.min) / (ev.max - ev.min) * 100) + '%');
      $('#evi').innerHTML = I('sun', 'amber', { shape: 'none' });
    }
    clearTimeout(ringT);
    ringT = setTimeout(() => { ring.hidden = true; evs.hidden = true; }, 3500);
    haptic();
  }
  $('#ev').addEventListener('input', (e) => {
    const v = +e.target.value; st.ev = v; call('ev', v);
    e.target.style.setProperty('--v', ((v - e.target.min) / (e.target.max - e.target.min) * 100) + '%');
    clearTimeout(ringT); ringT = setTimeout(() => { $('#ring').hidden = true; $('#evs').hidden = true; }, 3500);
  });
  $('#evs').addEventListener('pointerdown', (e) => e.stopPropagation());

  function setMode(video) { if (!!st.video === video) return; haptic(); call('setMode', video); if (MOCK) { st.video = video; renderTop(); } }

  // ------------------------------------------------------------------ boutons
  document.addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    const d = b.dataset;
    if (d.m) return setMode(d.m === 'video');
    if (d.lens != null) {
      const p = $('#lenses')._pills[+d.lens];
      haptic();
      call('selectCam', p.id, p.z);
      st.zoom = p.z; if (p.id !== st.cam) st.cam = p.id;
      renderLenses();
      return;
    }
    if (d.a === 'flash') { const order = st.video ? ['off', 'torch'] : ['off', 'auto', 'on', 'torch']; const n = order[(order.indexOf(st.flash || 'off') + 1) % order.length]; st.flash = n; call('flash', n); renderTop(); return; }
    if (d.a === 'timer') { timer = timer === 0 ? 3 : timer === 3 ? 10 : 0; localStorage.setItem('pc_timer', timer); renderTop(); return; }
    if (d.a === 'ratio') { if (st.video) openSheet(); else call('setPref', 'ratio', st.ratio === '4:3' ? '16:9' : '4:3'); return; }
    if (d.a === 'grid') { call('setPref', 'grid', String(!st.grid)); st.grid = !st.grid; renderTop(); return; }
    if (d.a === 'settings') return openSheet();
    if (d.a === 'therm') { call('thermal'); toastLike(); return; }
    if (d.set) { call('setPref', d.set, d.v); return; }
  });
  $('#shut').onclick = shutter;
  $('#flip').onclick = () => {
    if (document.body.classList.contains('rec')) return;
    const other = lenses.find((l) => !!l.front !== !!st.front);
    if (!other) return;
    haptic();
    $('#flip').classList.remove('spin'); void $('#flip').offsetWidth; $('#flip').classList.add('spin');
    call('selectCam', other.id, 1);
  };
  $('#thumb').onclick = () => {
    if (document.body.classList.contains('rec')) { call('pauseRec'); return; }
    if (st.hasLast) call('openLast'); else call('gallery');
  };
  function toastLike() {
    const s = $('#shin');
    $('#sheet').hidden = false;
    s.innerHTML = `<div class="secth">${I('pupface', 'amber', { shape: 'none', level: Math.max(1, lastLevel) })}<span class="chrome">Température</span></div>
      <div class="cp"><div class="row"><span>Batterie</span><b style="font-family:var(--f-num);font-size:20px">${therm.temp > 0 ? therm.temp.toFixed(1) + ' °C' : '—'}</b></div>
      <div class="row"><span>État thermique Android</span><b style="font-family:var(--f-num)">${['normal', 'tiède', 'chaud', 'très chaud', 'critique', 'urgence', 'arrêt'][therm.status || 0]}</b></div></div>
      <div class="ok">🔥 PupCamera ne coupe jamais rien à cause de la chaleur. Tu filmes jusqu'à ce que TU décides d'arrêter, puis laisse refroidir 🐾</div>`;
  }

  // ------------------------------------------------------------------ réglages
  function openSheet() {
    const sw = (k, on) => `<button class="sw ${on ? 'on' : ''}" data-set="${k}" data-v="${!on}" type="button"><i></i></button>`;
    const qs = st.qualities || [];
    $('#shin').innerHTML = `
      <div class="secth">${I('gear', 'violet')}<span class="chrome">RÉGLAGES PUPCAMERA</span></div>
      <div class="cp">
        <div class="cp-h">${I('film', 'red')}<div><b>Qualité vidéo</b><small>Seules les qualités que ton téléphone accepte vraiment sont proposées. En cours : ${st.quality || '—'}</small></div></div>
        <div class="choices c3">${qs.map((q) => `<button class="choice ${st.qsel === q ? 'on' : ''}" data-set="quality" data-v="${q}" type="button"><b>${QL[q] || q}</b><small>${q.startsWith('8k') ? 'max' : q === '4k60' ? 'défaut' : ''}</small></button>`).join('') || '<small>Ouvre la caméra pour détecter.</small>'}</div>
        <div class="row"><span>Codec<small>${st.hevc ? 'HEVC : même qualité, fichiers 2× plus légers' : 'H.264 : lisible partout'}</small></span>
          <div class="seg" style="width:170px"><button class="${st.hevc ? 'on' : ''}" data-set="hevc" data-v="true" type="button">HEVC</button><button class="${st.hevc ? '' : 'on'}" data-set="hevc" data-v="false" type="button">H.264</button></div></div>
        <div class="row"><span>Débit maximal<small>+25 % de détails, fichiers plus gros</small></span>${sw('maxbr', st.maxbr)}</div>
        <div class="row"><span>Stabilisation électronique<small>la stabilisation optique reste toujours active ; l'électronique recadre un peu</small></span>${sw('eis', st.eis)}</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('camera', 'amber')}<div><b>Photo</b><small>Pleine résolution du capteur : ${st.photo || '—'} (${st.mp || '?'} Mpx), JPEG qualité 100, traitement « haute qualité ».</small></div></div>
        <div class="row"><span>Format</span><div class="seg" style="width:170px"><button class="${st.ratio !== '16:9' ? 'on' : ''}" data-set="ratio" data-v="4:3" type="button">4:3</button><button class="${st.ratio === '16:9' ? 'on' : ''}" data-set="ratio" data-v="16:9" type="button">16:9</button></div></div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('wrench', 'cyan')}<div><b>Prise de vue</b></div></div>
        <div class="row"><span>Son du déclencheur</span>${sw('sound', st.sound)}</div>
        <div class="row"><span>Touches de volume = déclencheur</span>${sw('volkey', st.volkey)}</div>
        <div class="row"><span>Grille</span>${sw('grid', st.grid)}</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('pupface', 'amber', { shape: 'none', level: 2 })}<div><b>Chaleur</b><small>Aucune coupure : PupCamera ne s'arrête jamais à cause de la température. Le chiot en haut transpire pour te prévenir, c'est tout. (Seul Android lui-même peut ralentir ou éteindre le téléphone en dernier recours.)</small></div></div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('apps', 'violet')}<div><b>Capteurs détectés</b><small>Ce que ton téléphone laisse voir aux applis.</small></div></div>
        <table class="lt">${lenses.map((l) => `<tr><td>#${l.id}</td><td>${l.front ? 'avant' : 'arrière'}</td><td>${l.eq || '?'} mm</td><td>${l.mp || '?'} Mpx</td><td>${Math.round(l.zmin * 10) / 10}–${Math.round(l.zmax)}×${l.logical ? ' · multi' : ''}</td></tr>`).join('')}</table>
      </div>
      <button class="ab glass wide" data-close="1" type="button">Fermer</button>`;
    $('#sheet').hidden = false;
  }
  $('#sheet').addEventListener('click', (e) => { if (e.target.id === 'sheet' || e.target.closest('[data-close]')) $('#sheet').hidden = true; });

  // ------------------------------------------------------------------ natif → interface
  function on(ev, data) {
    if (ev === 'state') {
      st = J(data, st) || st;
      if (!$('#sheet').hidden && $('#shin .secth') && !$('#shin').textContent.includes('Température')) openSheet();
      renderTop();
    } else if (ev === 'thermal') { therm = J(data, therm) || therm; renderTherm(); }
    else if (ev === 'saved') { st.hasLast = true; renderThumb(); $('#thumb').classList.remove('pop'); void $('#thumb').offsetWidth; $('#thumb').classList.add('pop'); }
    else if (ev === 'rec') startRecUi(J(data, {}));
    else if (ev === 'recstop') stopRecUi();
    else if (ev === 'paused') { recPaused = data === '1'; $('#recchip').classList.toggle('paused', recPaused); renderThumb(); }
    else if (ev === 'seg') { $('#recsafe').textContent = '🛟 ' + fmt(+data * 5000) + ' sécurisées'; }
    else if (ev === 'merge') { const m = $('#merge'); if (data === 'done') m.hidden = true; else { m.hidden = false; m.textContent = '🐾 Assemblage de la vidéo… ' + data + ' %'; } }
    else if (ev === 'key') shutter();
    else if (ev === 'perm') { $('#perm').hidden = data === 'ok'; lenses = J(call('lenses'), []) || []; call('state'); }
    else if (ev === 'orient') { const r = +data; const rot = r === 90 ? -90 : r === 270 ? 90 : r === 180 ? 180 : 0; $$('.tb2 .pi,.thumb,.flip .pi,.lens,.therm span').forEach((el) => { el.style.transition = 'transform .3s'; el.style.transform = `rotate(${rot}deg)`; }); }
  }
  window.CamUI = {
    on,
    layout(top, h, st2, sb) {
      root.setProperty('--pvt', top + 'px'); root.setProperty('--pvh', h + 'px');
      root.setProperty('--st', Math.max(st2, 20) + 'px'); root.setProperty('--sb', Math.max(sb, 0) + 'px');
    },
    back() {
      if (!$('#sheet').hidden) { $('#sheet').hidden = true; return true; }
      if (document.body.classList.contains('rec')) { call('shutter'); return true; }
      return false;
    },
  };

  $('#permi').innerHTML = I('camera', 'amber');
  $('#permb').onclick = () => call('askPerms');
  if (call('perms') === false) $('#perm').hidden = false;
  call('state');
  call('thermal');
  renderTop();

  function mock() {
    const s = { cam: '0', front: false, video: false, zoom: 1, zmin: 0.5, zmax: 30, flash: 'off', hasFlash: true, ratio: '4:3', quality: '4K · 60 i/s', qualities: ['8k30', '4k60', '4k30', '1080p60', '1080p30'], qsel: '4k60', hevc: true, eis: false, sound: true, volkey: true, grid: true, maxbr: true, photo: '4000×3000', mp: 12, evmin: -8, evmax: 8, ev: 0, hasLast: false };
    document.body.style.background = 'radial-gradient(circle at 60% 40%,#ffb37a,#c2486e 40%,#2a1650 75%)';
    setTimeout(() => { on('state', JSON.stringify(s)); on('thermal', JSON.stringify({ status: 2, temp: 42.3 })); }, 50);
    return { lenses: () => JSON.stringify([{ id: '0', front: false, eq: 26, zmin: 0.5, zmax: 30, mp: 12, logical: true }, { id: '1', front: true, eq: 26, zmin: 1, zmax: 8, mp: 10 }, { id: '2', front: false, eq: 13, zmin: 1, zmax: 8, mp: 12 }]),
      perms: () => true, state() {}, thermal() {}, haptic() {}, zoom() {}, focus() {}, setPref() {}, selectCam() {}, flash() {}, shutter() {}, recTime: () => 0 };
  }
})();
