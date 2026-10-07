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
    $('[data-a=pro]').classList.toggle('on', !!st.pro);
    $('[data-a=raw]').hidden = !!st.video || !st.rawOk;
    $('[data-a=raw]').classList.toggle('on', !!st.raw);
    $('[data-a=mic]').innerHTML = I('speaker', st.voice ? 'cyan' : 'chrome', { shape: 'none', muted: !st.voice });
    $('[data-a=mic]').classList.toggle('on', !!st.voice);
    $('#voicechip').hidden = !st.voice || document.body.classList.contains('rec');
    $('#vci').innerHTML = I('speaker', 'cyan', { shape: 'none' });
    renderPro();
    $('[data-a=settings]').innerHTML = I('gear', 'violet', { shape: 'none' });
    $('#gridl').hidden = !st.grid;
    document.body.classList.toggle('video', !!st.video);
    $$('#modes button').forEach((b) => b.classList.toggle('on', (b.dataset.m === 'video') === !!st.video));
    $('#modes').style.display = st.capture ? 'none' : '';
    $('#flip').innerHTML = document.body.classList.contains('rec') ? I('camera', 'chrome', { shape: 'none' }) : I('switchcam', 'cyan', { shape: 'none' });
    renderThumb();
    renderLenses();
  }
  // ------------------------------------------------------------------ MODE PRO
  let dial = 'iso', live = { iso: 0, exp: 0, focus: 0 };
  const AWB = { 1: 'Auto', 5: 'Soleil', 6: 'Nuageux', 8: 'Ombre', 2: 'Tungstène', 3: 'Fluo', 4: 'Fluo chaud', 7: 'Crépuscule' };
  const fmtExp = (ns) => !ns ? '—' : ns >= 1e9 ? (Math.round(ns / 1e8) / 10) + ' s' : '1/' + Math.round(1e9 / ns);
  const fmtFocus = (d) => d <= 0.01 ? '∞' : (1 / d < 1 ? Math.round(100 / d) + ' cm' : (Math.round(10 / d) / 10) + ' m');
  const logPos = (v, a, b) => Math.log(v / a) / Math.log(b / a) * 1000;
  const logVal = (p, a, b) => a * Math.pow(b / a, p / 1000);
  function renderPro() {
    const on = !!st.pro;
    $('#propanel').hidden = !on;
    $('#hud').hidden = !on;
    if (!on) return;
    const expMax = st.video ? Math.min(st.expMax || 1e8, 1e9 / (parseInt((st.quality || '30').split('· ')[1]) || 30)) : st.expMax;
    const D = [
      ['iso', 'ISO', st.isoMan ? st.isoMan : 'A·' + (live.iso || '—'), !!st.isoMan],
      ['exp', 'VITESSE', st.expMan ? fmtExp(st.expMan) : 'A·' + fmtExp(live.exp), !!st.expMan],
      ['focus', 'MAP', st.focusMan >= 0 ? fmtFocus(st.focusMan) : 'AF', st.focusMan >= 0],
      ['awb', 'BLANCS', AWB[st.awb] || 'Auto', st.awb !== 1],
      ['ev', 'EV', (st.ev > 0 ? '+' : '') + (st.ev || 0), !!st.ev],
    ];
    $('#dials').innerHTML = D.map(([k, l, v, man]) => `<button class="dial ${dial === k ? 'sel' : ''} ${man ? 'man' : ''}" data-dial="${k}" type="button"><small>${l}</small><b>${v}</b></button>`).join('');
    let ctl = '';
    if (!st.manual && (dial === 'iso' || dial === 'exp' || dial === 'focus')) ctl = `<small style="color:var(--muted)">Ce capteur ne donne pas le contrôle manuel aux applis.</small>`;
    else if (dial === 'iso') ctl = `<input type="range" id="pr" min="0" max="1000" value="${Math.round(logPos(st.isoMan || live.iso || st.isoMin || 100, st.isoMin || 50, st.isoMax || 3200))}"><button class="ab small ${st.isoMan ? 'glass' : 'green'}" data-auto="iso" type="button">AUTO</button>`;
    else if (dial === 'exp') ctl = `<input type="range" id="pr" min="0" max="1000" value="${Math.round(logPos(st.expMan || live.exp || 1e7, st.expMin || 1e4, expMax || 1e9))}"><button class="ab small ${st.expMan ? 'glass' : 'green'}" data-auto="exp" type="button">AUTO</button>`;
    else if (dial === 'focus') ctl = `<input type="range" id="pr" min="0" max="1000" value="${Math.round((st.focusMan >= 0 ? st.focusMan : live.focus) / (st.focusMin || 10) * 1000)}"><button class="ab small ${st.focusMan >= 0 ? 'glass' : 'green'}" data-auto="focus" type="button">AF</button>`;
    else if (dial === 'awb') ctl = `<div class="wbs">${(st.awbModes || [1]).filter((m) => AWB[m]).map((m) => `<button class="${st.awb === m ? 'on' : ''}" data-awb="${m}" type="button">${AWB[m]}</button>`).join('')}</div>`;
    else if (dial === 'ev') ctl = `<input type="range" id="pr" min="${st.evmin || 0}" max="${st.evmax || 0}" value="${st.ev || 0}"><button class="ab small glass" data-auto="ev" type="button">0</button>`;
    ctl += `<div class="toggles">${`<button class="${st.minimal ? 'on' : ''}" data-tog="minimal" type="button">Brut</button>`}${st.video && st.flatOk ? `<button class="${st.flat ? 'on' : ''}" data-tog="flat" type="button">Plat</button>` : ''}</div>`;
    $('#dialctl').innerHTML = ctl;
    const r = $('#pr');
    if (r) {
      const upd = () => r.style.setProperty('--v', ((r.value - r.min) / (r.max - r.min) * 100) + '%');
      upd();
      r.oninput = () => {
        upd();
        const p = +r.value;
        if (dial === 'iso') { const v = Math.round(logVal(p, st.isoMin || 50, st.isoMax || 3200)); st.isoMan = v; call('setPro', 'iso', String(v)); }
        if (dial === 'exp') { const v = Math.round(logVal(p, st.expMin || 1e4, expMax || 1e9)); st.expMan = v; call('setPro', 'exp', String(v)); }
        if (dial === 'focus') { const v = p / 1000 * (st.focusMin || 10); st.focusMan = v; call('setPro', 'focus', String(v)); }
        if (dial === 'ev') { st.ev = p; call('ev', p); }
        $$('.dial.sel b')[0].textContent = dial === 'iso' ? st.isoMan : dial === 'exp' ? fmtExp(st.expMan) : dial === 'focus' ? fmtFocus(st.focusMan) : (st.ev > 0 ? '+' : '') + st.ev;
      };
    }
    $('#hud').textContent = `ISO ${live.iso || '—'} · ${fmtExp(live.exp)} · ${fmtFocus(live.focus)}${st.raw ? ' · RAW ' + (st.rawRes || '') : ''}`;
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
    if (prim.zmax >= 2) pills.push({ id: prim.id, z: 2, label: '2×', sub: 'num.' });
    if (prim.zmax >= 3) pills.push({ id: prim.id, z: 3, label: '3×', sub: 'num.' });
    if (prim.zmax >= 10 && !st.front) pills.push({ id: prim.id, z: 10, label: '10×', sub: 'num.' });
    const bad = J(localStorage.getItem('pc_badlens'), []) || [];
    side.slice(1).filter((l) => !bad.includes(l.id)).forEach((l) => {
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
    $('#voicechip').hidden = true;
    $('#flip').innerHTML = info.snap === false ? I('switchcam', 'chrome', { shape: 'none' }) : I('camera', 'chrome', { shape: 'none' });
    recPaused = false;
    $('#recchip').hidden = false;
    $('#recchip').classList.remove('paused');
    $('#recsafe').textContent = info.ts ? '🛡️ mode blindé : écrit chaque seconde' : '🛟 sauvegarde en direct';
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
    $('#voicechip').hidden = !st.voice;
    $('#flip').innerHTML = I('switchcam', 'cyan', { shape: 'none' });
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
    if (d.a === 'pro') { call('setPro', 'pro', String(!st.pro)); st.pro = !st.pro; renderTop(); return; }
    if (d.a === 'raw') { call('setPro', 'raw', String(!st.raw)); st.raw = !st.raw; renderTop(); if (st.raw) call('toast', 'RAW activé : chaque photo = JPG + DNG (DCIM/PuppyPhone/RAW)'); return; }
    if (d.a === 'mic') { call('voice', !st.voice); st.voice = !st.voice; renderTop(); return; }
    if (d.dial) { dial = d.dial; renderPro(); return; }
    if (d.auto) { if (d.auto === 'iso') st.isoMan = 0; if (d.auto === 'exp') st.expMan = 0; if (d.auto === 'focus') st.focusMan = -1; if (d.auto === 'ev') { st.ev = 0; call('ev', 0); } else call('setPro', d.auto, d.auto === 'focus' ? '-1' : '0'); renderPro(); return; }
    if (d.awb) { st.awb = +d.awb; call('setPro', 'awb', d.awb); renderPro(); return; }
    if (d.tog) { st[d.tog] = !st[d.tog]; call('setPro', d.tog, String(st[d.tog])); renderPro(); return; }
    if (d.vlang) { localStorage.setItem('pc_vlang', d.vlang); call('voiceLang', d.vlang); openSheet(); return; }
    if (d.a === 'grid') { call('setPref', 'grid', String(!st.grid)); st.grid = !st.grid; renderTop(); return; }
    if (d.a === 'settings') return openSheet();
    if (d.a === 'therm') { call('thermal'); toastLike(); return; }
    if (d.set === 'voice') { call('voice', d.v === 'true'); return; }
    if (d.set) { call('setPref', d.set, d.v); return; }
  });
  $('#shut').onclick = shutter;
  $('#flip').onclick = () => {
    if (document.body.classList.contains('rec')) { haptic(); call('snapshot'); const f = $('#flashfx'); f.classList.remove('go'); void f.offsetWidth; f.classList.add('go'); return; }
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
        <div class="row"><span>Sauvegarde anti-coupure<small>${st.container === 'ts'
            ? 'Blindé (TS) : un seul fichier lisible jusqu\'à la dernière fraction de seconde. H.264 uniquement, fichiers ~40 % plus lourds, format .ts moins compatible (réseaux sociaux).'
            : 'Morceaux de 5 s (MP4) : au pire 5 s perdues, HEVC possible, recollés en un seul MP4 à l\'arrêt.'}</small></span>
          <div class="seg" style="width:170px"><button class="${st.container !== 'ts' ? 'on' : ''}" data-set="container" data-v="mp4" type="button">MP4 5 s</button><button class="${st.container === 'ts' ? 'on' : ''}" data-set="container" data-v="ts" type="button">Blindé</button></div></div>
        <div class="row"><span>Débit maximal<small>+25 % de détails, fichiers plus gros</small></span>${sw('maxbr', st.maxbr)}</div>
        <div class="row"><span>Stabilisation électronique<small>la stabilisation optique reste toujours active ; l'électronique recadre un peu</small></span>${sw('eis', st.eis)}</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('camera', 'amber')}<div><b>Photo</b><small>Pleine résolution du capteur : ${st.photo || '—'} (${st.mp || '?'} Mpx), JPEG qualité 100, traitement « haute qualité ».</small></div></div>
        <div class="row"><span>Zoom photo net<small>en zoom numérique, la photo est prise sur tout le capteur puis recadrée : vrais pixels, aucun détail inventé (photo plus petite mais plus propre)</small></span>${sw('cropzoom', st.cropzoom !== false)}</div>
        <div class="row"><span>Format</span><div class="seg" style="width:170px"><button class="${st.ratio !== '16:9' ? 'on' : ''}" data-set="ratio" data-v="4:3" type="button">4:3</button><button class="${st.ratio === '16:9' ? 'on' : ''}" data-set="ratio" data-v="16:9" type="button">16:9</button></div></div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('wrench', 'cyan')}<div><b>Prise de vue</b></div></div>
        <div class="row"><span>Son du déclencheur</span>${sw('sound', st.sound)}</div>
        <div class="row"><span>Touches de volume = déclencheur</span>${sw('volkey', st.volkey)}</div>
        <div class="row"><span>Grille</span>${sw('grid', st.grid)}</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('speaker', 'cyan')}<div><b>Déclencheur vocal</b><small>Active le micro en haut, puis dis « photo » (clic), « film » (cloche) ou « flash » (lumière). Marche en français, anglais et espagnol : photo/foto/picture · film/vidéo/record/graba · flash/lumière/light/luz. Le micro est pris par la vidéo pendant le tournage : la voix fonctionne avant de filmer.</small></div></div>
        <div class="row"><span>Activé</span>${sw('voice', st.voice)}</div>
        <div class="row"><span>Langue d'écoute</span><div class="seg" style="width:200px">${[['', 'Auto'], ['fr-FR', 'FR'], ['en-US', 'EN'], ['es-ES', 'ES']].map(([t, l]) => `<button class="${(localStorage.getItem('pc_vlang') || '') === t ? 'on' : ''}" data-vlang="${t || navigator.language}" type="button">${l}</button>`).join('')}</div></div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('sparkle', 'amber')}<div><b>Mode Pro</b><small>ISO ${st.isoMin || '?'}–${st.isoMax || '?'} (analogique jusqu'à ${st.isoAnalog || '?'}), vitesse ${fmtExp(st.expMin)} → ${fmtExp(st.expMax)}, mise au point jusqu'à ${st.focusMin ? fmtFocus(st.focusMin) : '?'}. Ce sont les vraies limites du capteur, sans bride ajoutée. RAW : ${st.rawOk ? 'disponible (' + st.rawRes + ')' : 'non proposé par ce capteur'}.</small></div></div>
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
    else if (ev === 'live') { live = J(data, live) || live; if (st.pro) { $('#hud').textContent = `ISO ${live.iso} · ${fmtExp(live.exp)} · ${fmtFocus(live.focus)}${st.raw ? ' · RAW ' + (st.rawRes || '') : ''}`; $$('.dial').forEach((b) => { if (b.dataset.dial === 'iso' && !st.isoMan) $('b', b).textContent = 'A·' + live.iso; if (b.dataset.dial === 'exp' && !st.expMan) $('b', b).textContent = 'A·' + fmtExp(live.exp); }); } }
    else if (ev === 'voice') { const vc = $('#voicechip'); vc.classList.toggle('hear', data === 'hear'); }
    else if (ev === 'voicecmd') { const v = $('#vcmd'); v.textContent = data === 'photo' ? '📸 PHOTO !' : data === 'film' ? '🎬 ÇA TOURNE !' : '🔦 LUMIÈRE !'; v.hidden = false; v.style.animation = 'none'; void v.offsetWidth; v.style.animation = ''; setTimeout(() => { v.hidden = true; }, 1300); }
    else if (ev === 'raw') { call('toast', '🎞️ DNG enregistré'); }
    else if (ev === 'lenserr') { const bad = J(localStorage.getItem('pc_badlens'), []) || []; if (!bad.includes(data)) bad.push(data); localStorage.setItem('pc_badlens', JSON.stringify(bad)); renderLenses(); }
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
    const s = { cam: '0', front: false, video: false, zoom: 1, zmin: 0.5, zmax: 30, flash: 'off', hasFlash: true, ratio: '4:3', quality: '4K · 60 i/s', qualities: ['8k30', '4k60', '4k30', '1080p60', '1080p30'], qsel: '4k60', hevc: true, eis: false, sound: true, volkey: true, grid: true, maxbr: true, photo: '4000×3000', mp: 12, evmin: -8, evmax: 8, ev: 0, hasLast: false, pro: true, manual: true, rawOk: true, raw: true, rawRes: '4032×3024', voice: true, isoMin: 50, isoMax: 3200, isoAnalog: 800, expMin: 1e4, expMax: 1e10, focusMin: 10, awb: 1, awbModes: [1, 2, 3, 5, 6, 8], isoMan: 0, expMan: 0, focusMan: -1, flatOk: true };
    document.body.style.background = 'radial-gradient(circle at 60% 40%,#ffb37a,#c2486e 40%,#2a1650 75%)';
    setTimeout(() => { on('state', JSON.stringify(s)); on('thermal', JSON.stringify({ status: 2, temp: 42.3 })); }, 50);
    return { lenses: () => JSON.stringify([{ id: '0', front: false, eq: 26, zmin: 0.5, zmax: 30, mp: 12, logical: true }, { id: '1', front: true, eq: 26, zmin: 1, zmax: 8, mp: 10 }, { id: '2', front: false, eq: 13, zmin: 1, zmax: 8, mp: 12 }]),
      perms: () => true, state() {}, thermal() {}, haptic() {}, zoom() {}, focus() {}, setPref() {}, selectCam() {}, flash() {}, shutter() {}, recTime: () => 0 };
  }
})();
