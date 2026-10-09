/* PupDicta — magnétophone à cassette */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const COLS = ['#ff3fa4', '#29e6ff', '#3dffb0', '#ffb627', '#9b5cff', '#ff5a3c'];
  const P = window.Pup || mock();
  const fmt = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s % 60).padStart(2, '0'); };
  const nice = (n) => String(n || '').replace(/\.(m4a|aac)$/i, '');
  const colOf = (it) => (it && it.col) || COLS[[...String(it && it.name)].reduce((a, c) => a + c.charCodeAt(0), 0) % COLS.length];
  function toast(msg) { let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); } t.textContent = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 2800); }
  let pref = { q: 'std', s: 1 };
  try { pref = Object.assign(pref, JSON.parse(localStorage.getItem('pupdicta') || '{}')); } catch (e) { }
  const savePref = () => { try { localStorage.setItem('pupdicta', JSON.stringify(pref)); } catch (e) { } };

  let items = [], loaded = null, wave = [], st = {}, ps = {}, curPos = 0;

  // ------------------------------------------------------------ VU-mètre
  (function buildVU() {
    const sc = $('#scale'), lb = $('#labels'), cx = 100, cy = 112, R = 84;
    const marks = [[-20, '20'], [-10, '10'], [-7, '7'], [-5, '5'], [-3, '3'], [-2, ''], [-1, '1'], [0, '0'], [1, '1'], [2, ''], [3, '3']];
    let h = '', l = '';
    for (const [db, t] of marks) {
      const a = ang(db) * Math.PI / 180, x1 = cx + Math.sin(a) * R, y1 = cy - Math.cos(a) * R, x2 = cx + Math.sin(a) * (R - (t ? 9 : 6)), y2 = cy - Math.cos(a) * (R - (t ? 9 : 6));
      h += `<line x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}" ${db > 0 ? 'stroke="#d81f3a"' : ''}/>`;
      if (t) { const x3 = cx + Math.sin(a) * (R + 9), y3 = cy - Math.cos(a) * (R + 9) + 3; l += `<text x="${x3.toFixed(1)}" y="${y3.toFixed(1)}" ${db > 0 ? 'fill="#d81f3a"' : ''}>${db > 0 ? '+' + t : db === 0 ? '0' : '−' + t}</text>`; }
    }
    const arc = (d0, d1, r) => { const a0 = ang(d0) * Math.PI / 180, a1 = ang(d1) * Math.PI / 180; return `M${cx + Math.sin(a0) * r} ${cy - Math.cos(a0) * r}A${r} ${r} 0 0 1 ${cx + Math.sin(a1) * r} ${cy - Math.cos(a1) * r}`; };
    h += `<path d="${arc(-20, 3, R)}"/>`;
    sc.innerHTML = h; lb.innerHTML = l; $('#redarc').setAttribute('d', arc(0, 3, R - 3));
  })();
  /** dB → angle (degrés) façon VU : échelle compressée en bas. */
  function ang(db) { const k = Math.pow(10, db / 20), k0 = Math.pow(10, -20 / 20), k1 = Math.pow(10, 3 / 20); return -50 + 100 * (k - k0) / (k1 - k0); }
  let needle = -50, nv = 0;
  const lvlToDb = (v) => v / 255 * 60 - 60 + 9; // l'aiguille vit entre −20 et +3

  // ------------------------------------------------------------ cassette
  let rot = 0, last = performance.now();
  function frame(t) {
    const dt = Math.min(.1, (t - last) / 1000); last = t;
    const recOn = st.rec && !st.paused, playOn = ps.playing;
    // niveau
    let lv = 0;
    if (st.rec) lv = st.paused ? 0 : st.level || 0;
    else if (playOn && wave.length) lv = wave[Math.min(wave.length - 1, Math.floor((ps.pos || 0) / 100))] || 0;
    const target = lv ? Math.max(-50, Math.min(52, ang(lvlToDb(lv)))) : -50;
    nv += (target - needle) * (target > needle ? .5 : .14); needle += nv * .55; nv *= .45; // inertie d'aiguille
    $('#needle').setAttribute('transform', `rotate(${needle.toFixed(2)} 100 112)`);
    // bobines
    const f = st.rec ? Math.min(1, (st.ms || 0) / 2700000) : loaded && loaded.ms ? Math.min(1, ((playOn ? ps.pos : curPos) || 0) / loaded.ms) : 0;
    const rL = 17 + 14 * (1 - f), rR = 17 + 14 * f;
    if (recOn || playOn) rot += dt * 220 * (playOn ? pref.s : 1);
    $('#packL').setAttribute('r', rL.toFixed(1)); $('#packR').setAttribute('r', rR.toFixed(1));
    $('#spkL').setAttribute('transform', `rotate(${rot % 360} 118 107)`); $('#spkR').setAttribute('transform', `rotate(${(rot * rL / rR) % 360} 242 107)`);
    $('#span').setAttribute('d', `M118 ${107 + rL} L242 ${107 + rR}`);
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  // ------------------------------------------------------------ état
  function setDrums(ms) { const s = Math.floor(ms / 1000), m = Math.min(99, Math.floor(s / 60)), d = String(m).padStart(2, '0') + String(s % 60).padStart(2, '0'), D = document.querySelectorAll('#drums i'); for (let i = 0; i < 4; i++) D[i].textContent = d[i]; }
  function poll() {
    try { st = JSON.parse(P.state()); } catch (e) { st = {}; }
    try { ps = JSON.parse(P.pstate()); } catch (e) { ps = {}; }
    if (st.saved) { const n = st.saved; P.clearSaved(); refresh(); const it = items.find((x) => x.name === n); if (it) load(it); toast('📼 Cassette rangée sur l’étagère 🐾'); }
    else if (st.error) { P.clearSaved(); toast(/^↻/.test(st.error) ? st.error + ' 🐾' : '😿 ' + st.error); }
    if (ps.playing) curPos = ps.pos;
    const k = (n) => $(`[data-k="${n}"]`);
    k('rec').classList.toggle('down', !!(st.rec && !st.paused));
    k('pause').classList.toggle('down', !!(st.rec && st.paused) || (!st.rec && loaded && !ps.playing && curPos > 0));
    k('play').classList.toggle('down', !!ps.playing);
    k('play').disabled = !!st.rec;
    $('#recled').classList.toggle('on', !!st.rec); $('#recled').classList.toggle('pa', !!st.paused);
    $('#qual').classList.toggle('off', !!st.rec);
    if (st.rec) { setDrums(st.ms || 0); $('#cnt2').textContent = st.paused ? '⏸ PAUSE' : '● ENREGISTRE'; $('#clbl').textContent = 'Enregistrement…'; $('#tapebar').classList.add('hidden'); setStripe(COLS[0]); }
    else if (loaded) {
      setDrums(curPos); $('#cnt2').textContent = ps.playing ? `▶ LECTURE ×${pref.s}` : curPos > 0 ? '⏸ PAUSE' : fmt(loaded.ms);
      $('#clbl').textContent = nice(loaded.name); $('#tapebar').classList.remove('hidden');
      $('#tpos').textContent = fmt(curPos); $('#tdur').textContent = fmt(loaded.ms);
      $('#head').style.left = (loaded.ms ? Math.min(100, curPos / loaded.ms * 100) : 0) + '%';
    } else { setDrums(0); $('#cnt2').textContent = 'PRÊT'; $('#clbl').textContent = 'Nouvelle cassette'; $('#tapebar').classList.add('hidden'); }
  }
  setInterval(poll, 90);
  function setStripe(c) { $('#stripe').setAttribute('fill', c); $('#stripe2').setAttribute('fill', c); }

  // ------------------------------------------------------------ étagère
  function refresh() {
    try { items = JSON.parse(P.list()); } catch (e) { items = []; }
    $('#cnt').textContent = items.length ? items.length + ' cassette' + (items.length > 1 ? 's' : '') : '';
    $('#list').innerHTML = items.length ? items.map((it, i) => {
      const d = new Date(it.at), nm = (it.marks || []).length;
      return `<div class="tp ${loaded && loaded.name === it.name ? 'on' : ''}" data-i="${i}" style="--c:${colOf(it)}"><span class="mini"></span><div class="tx"><b>${esc(nice(it.name))}</b><span>${fmt(it.ms)} · ${d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })} ${String(d.getHours()).padStart(2, '0')}h${String(d.getMinutes()).padStart(2, '0')}${nm ? ' · 🐾 ' + nm : ''}</span></div><button class="more" data-more="${i}" type="button">⋯</button></div>`;
    }).join('') : `<div class="empty"><b>L’étagère est vide 🐶</b>Appuie sur <b>● ENREG.</b> pour remplir ta première cassette. L’enregistrement continue même écran éteint.</div>`;
    if (loaded && !items.find((x) => x.name === loaded.name)) unload();
  }
  function load(it) {
    if (!loaded || loaded.name !== it.name) { P.stopPlay(); curPos = 0; }
    loaded = it; setStripe(colOf(it));
    try { const m = JSON.parse(P.wave(it.name)); wave = m.wave || []; loaded.marks = m.marks || it.marks || []; } catch (e) { wave = []; }
    drawWave(); refresh();
  }
  function unload() { P.stopPlay(); loaded = null; wave = []; curPos = 0; setStripe(COLS[0]); }
  function drawWave() {
    const c = $('#wv'), r = c.getBoundingClientRect(), dpr = devicePixelRatio || 1;
    c.width = Math.max(1, r.width * dpr); c.height = Math.max(1, r.height * dpr);
    const x = c.getContext('2d'); x.clearRect(0, 0, c.width, c.height);
    if (!loaded) return;
    const n = Math.floor(c.width / (3 * dpr)), mid = c.height / 2;
    x.fillStyle = 'rgba(255,190,90,.85)';
    for (let i = 0; i < n; i++) {
      let v = 0; if (wave.length) { const a = Math.floor(i / n * wave.length), b = Math.max(a + 1, Math.floor((i + 1) / n * wave.length)); for (let j = a; j < b; j++) v = Math.max(v, wave[j] || 0); } else v = 60;
      const h = Math.max(1.5 * dpr, Math.pow(v / 255, 1.6) * (c.height - 8 * dpr));
      x.fillRect(i * 3 * dpr, mid - h / 2, 2 * dpr, h);
    }
    const acc = getComputedStyle(document.body).getPropertyValue('--acc').trim() || '#ff3fa4';
    for (const m of loaded.marks || []) { const px = m / Math.max(1, loaded.ms) * c.width; x.fillStyle = acc; x.fillRect(px - 1 * dpr, 0, 2 * dpr, c.height); x.beginPath(); x.arc(px, 6 * dpr, 4.5 * dpr, 0, 7); x.fill(); }
  }
  addEventListener('resize', drawWave);
  const T = $('#track');
  function seekAt(e) { if (!loaded) return; const r = T.getBoundingClientRect(), k = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)); curPos = Math.round(k * loaded.ms); if (ps.playing) P.seek(curPos); }
  T.addEventListener('pointerdown', (e) => { T.setPointerCapture(e.pointerId); seekAt(e); T._d = 1; });
  T.addEventListener('pointermove', (e) => { if (T._d) seekAt(e); });
  T.addEventListener('pointerup', () => { T._d = 0; });

  // ------------------------------------------------------------ réglages
  function slide(id, attr, val) {
    const R = $('#' + id + ' .rail'), bs = [...R.querySelectorAll('button')], i = Math.max(0, bs.findIndex((b) => b.getAttribute(attr) === String(val)));
    bs.forEach((b, j) => b.classList.toggle('on', j === i));
    const nub = R.querySelector('.nub'); nub.style.width = `calc((100% - 6px) / ${bs.length})`; nub.style.transform = `translateX(${i * 100}%)`;
  }
  slide('qual', 'data-q', pref.q); slide('spd', 'data-s', pref.s);

  // ------------------------------------------------------------ voile
  function veil(h) { const v = $('#veil'); v.innerHTML = h; v.classList.remove('hidden'); }
  function unveil() { $('#veil').classList.add('hidden'); $('#veil').innerHTML = ''; }
  let sel = null;
  function more(it) {
    sel = it;
    veil(`<div class="sheet"><h3>📼 ${esc(nice(it.name))}</h3><p>${fmt(it.ms)} · ${(it.size / 1048576).toFixed(1)} Mo</p>
      <label class="fld">✏️<input id="rn" value="${esc(nice(it.name))}" maxlength="60"></label>
      <div class="cols">${COLS.map((c) => `<i data-col="${c}" class="${colOf(it) === c ? 'on' : ''}" style="--c:${c}"></i>`).join('')}</div>
      ${(it.marks || []).length ? `<div class="marks">${it.marks.map((m) => `<button data-jump="${m}" type="button">🐾 ${fmt(m)}</button>`).join('')}</div>` : ''}
      <div class="grid2"><button class="ab" data-a="share" type="button">📤 Partager</button><button class="ab c2" data-a="export" type="button">🎵 Vers Musique</button>
      <button class="ab green" data-a="ren" type="button">✓ Renommer</button><button class="ab red" data-a="del" type="button">🗑️ Effacer</button></div>
      <button class="ab wide glass" data-a="close" type="button">Fermer</button></div>`);
  }

  // ------------------------------------------------------------ clics
  document.addEventListener('click', (ev) => {
    const t = ev.target, b = (s) => t.closest(s); let e;
    if ((e = b('[data-k]'))) {
      P.haptic(); const k = e.dataset.k;
      if (k === 'rec') { if (st.rec) { if (st.paused) P.resume(); } else { P.stopPlay(); loaded = null; wave = []; curPos = 0; refresh(); P.start(pref.q); } }
      if (k === 'pause') { if (st.rec) st.paused ? P.resume() : P.pause(); else if (ps.playing) P.pauseP(); else if (loaded && curPos > 0) P.play(loaded.name, curPos); }
      if (k === 'stop') { if (st.rec) P.stop(); else if (loaded) { P.stopPlay(); curPos = 0; } }
      if (k === 'play') { if (st.rec) return; if (!loaded) { if (items[0]) load(items[0]); else { toast('L’étagère est vide : enregistre d’abord une cassette 🐶'); return; } } if (!ps.playing) { P.setSpeed(pref.s); P.play(loaded.name, curPos >= (loaded.ms - 300) ? 0 : curPos); } }
      if (k === 'mark') {
        if (st.rec) { P.mark(); toast('🐾 Marque posée à ' + fmt(st.ms)); }
        else if (loaded && (loaded.marks || []).length) { const nx = loaded.marks.find((m) => m > curPos + 1500); curPos = nx != null ? nx : loaded.marks[0]; if (ps.playing) P.seek(curPos); toast('🐾 Saut à ' + fmt(curPos)); }
        else toast('Pendant l’enregistrement, 🐾 pose une marque pour retrouver un passage');
      }
      return;
    }
    if ((e = b('[data-q]'))) { pref.q = e.dataset.q; savePref(); slide('qual', 'data-q', pref.q); toast({ eco: 'Éco : petits fichiers, parfait pour la voix', std: 'Standard : voix claire, taille raisonnable', hifi: 'Hi-Fi stéréo : musique et ambiances' }[pref.q]); return; }
    if ((e = b('[data-s]'))) { pref.s = +e.dataset.s; savePref(); slide('spd', 'data-s', pref.s); P.setSpeed(pref.s); return; }
    if ((e = b('[data-more]'))) { more(items[+e.dataset.more]); return; }
    if ((e = b('[data-i]'))) { if (!st.rec) load(items[+e.dataset.i]); return; }
    if ((e = b('[data-col]'))) { P.setColor(sel.name, e.dataset.col); sel.col = e.dataset.col; document.querySelectorAll('[data-col]').forEach((x) => x.classList.toggle('on', x === e)); if (loaded && loaded.name === sel.name) setStripe(sel.col); refresh(); return; }
    if ((e = b('[data-jump]'))) { load(sel); curPos = +e.dataset.jump; unveil(); P.setSpeed(pref.s); P.play(sel.name, curPos); return; }
    if ((e = b('[data-a]'))) {
      const a = e.dataset.a;
      if (a === 'close') unveil();
      if (a === 'share') P.shareRec(sel.name);
      if (a === 'export') toast(P.export(sel.name) ? '🎵 Copiée dans Musique › PupDicta' : 'Oups, la copie a raté 😿');
      if (a === 'ren') { const n = P.rename(sel.name, $('#rn').value); if (n) { const wasLoaded = loaded && loaded.name === sel.name; unveil(); refresh(); if (wasLoaded) load(items.find((x) => x.name === n)); toast('Étiquette réécrite ✏️'); } else toast('Ce nom est déjà pris ou invalide 🐶'); }
      if (a === 'del') { veil(`<div class="sheet"><h3>Effacer la cassette ? 🗑️</h3><p>« ${esc(nice(sel.name))} » sera effacée pour de bon.</p><div class="grid2"><button class="ab glass" data-a="close" type="button">Garder</button><button class="ab red" data-a="del2" type="button">Effacer</button></div></div>`); }
      if (a === 'del2') { if (loaded && loaded.name === sel.name) unload(); P.del(sel.name); unveil(); refresh(); toast('Cassette effacée 🦴'); }
      return;
    }
    if (t === $('#veil')) unveil();
  });

  window.DictaUI = {
    on(ev, d) {
      if (ev === 'resume') { refresh(); poll(); guard(); }
      if (ev === 'rec') poll();
      if (ev === 'pend') { curPos = 0; }
      if (ev === 'perr') toast('Lecture impossible 😿');
      if (ev === 'perm' && d === 'no') toast('Sans le micro, le chiot ne peut rien entendre 🙉 Autorise-le dans les réglages.');
    },
    back() { if (!$('#veil').classList.contains('hidden')) { unveil(); return true; } return false; },
  };
  function guard() {
    let g = {}; try { g = JSON.parse(P.guard ? P.guard() : '{}'); } catch (e) { }
    const w = [];
    if (g.batt === false) w.push(`<div class="warnbox">🔋 One UI peut couper le magnétophone pour « économiser » la batterie. Laisse PuppyPhone en <b>batterie illimitée</b> :<button class="ab small amber" data-g="batt" type="button">Autoriser</button></div>`);
    if (g.a11y === false) w.push(`<div class="warnbox">🛡️ Active <b>PupNav</b> dans l'accessibilité : la « fausse vidéo » garde le magnétophone éveillé et le relance tout seul après une coupure.<button class="ab small amber" data-g="a11y" type="button">Ouvrir l'accessibilité</button></div>`);
    $('#guard').innerHTML = w.join('');
  }
  document.addEventListener('click', (e) => { const g = e.target.closest('[data-g]'); if (g) { if (g.dataset.g === 'batt') P.battery(); else P.a11y(); } });
  refresh(); poll(); guard();

  // ------------------------------------------------------------ maquette navigateur
  function mock() {
    const L = [{ name: 'Idée de chanson pour Rex.aac', size: 2.1e6, at: Date.now() - 36e5, ms: 184000, marks: [32000, 95000] }, { name: 'Courses de la semaine.m4a', size: .6e6, at: Date.now() - 864e5, ms: 41000, col: '#3dffb0' }, { name: 'Dicta 2026-10-07 21h14.m4a', size: 5.4e6, at: Date.now() - 2 * 864e5, ms: 612000 }];
    let r = { rec: false, paused: false, t0: 0, acc: 0, marks: [] }, p = { name: '', playing: false, pos: 0, t0: 0 };
    const W = Array.from({ length: 1840 }, (_, i) => Math.max(0, Math.min(255, 120 + 90 * Math.sin(i / 7) * Math.sin(i / 53) + (Math.random() * 60 - 30))));
    return {
      state: () => JSON.stringify({ rec: r.rec, paused: r.paused, ms: r.acc + (r.rec && !r.paused ? Date.now() - r.t0 : 0), level: r.rec && !r.paused ? 90 + Math.random() * 140 : 0, marks: r.marks, saved: r.saved || '', error: '' }),
      clearSaved() { r.saved = ''; }, start() { r = { rec: true, paused: false, t0: Date.now(), acc: 0, marks: [] }; }, pause() { r.acc += Date.now() - r.t0; r.paused = true; }, resume() { r.t0 = Date.now(); r.paused = false; },
      stop() { const ms = r.acc + (r.paused ? 0 : Date.now() - r.t0); const n = 'Dicta test.m4a'; L.unshift({ name: n, size: 1e5, at: Date.now(), ms, marks: r.marks }); r = { rec: false, saved: n, marks: [] }; }, mark() { r.marks.push(1); },
      list: () => JSON.stringify(L), wave: (n) => JSON.stringify({ wave: W, marks: (L.find((x) => x.name === n) || {}).marks || [] }), setColor(n, c) { const it = L.find((x) => x.name === n); if (it) it.col = c; },
      rename(n, to) { const it = L.find((x) => x.name === n); it.name = to + '.m4a'; return it.name; }, del(n) { L.splice(L.findIndex((x) => x.name === n), 1); return true; }, shareRec() {}, export: () => 'ok',
      play(n, from) { p = { name: n, playing: true, pos: from >= 0 ? from : p.pos, t0: Date.now() - (from >= 0 ? from : p.pos) }; }, pauseP() { p.pos = Date.now() - p.t0; p.playing = false; }, stopPlay() { p = { name: '', playing: false, pos: 0 }; }, seek(ms) { p.t0 = Date.now() - ms; }, setSpeed() {},
      guard: () => JSON.stringify({ batt: false, a11y: true }), battery() {}, a11y() {}, pstate: () => JSON.stringify({ name: p.name, playing: p.playing, pos: p.playing ? Date.now() - p.t0 : p.pos, dur: 0 }), haptic() {},
    };
  }
})();
