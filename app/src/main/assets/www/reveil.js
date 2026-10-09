/* PupRéveil — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const ic = (g, c, sh) => PupIcons.icon(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const p2 = (n) => String(n).padStart(2, '0');
  const DAYS = ['L', 'M', 'M', 'J', 'V', 'S', 'D'], DAYN = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'];
  const SONS0 = [['wouf', 'Wouf wouf du matin'], ['jappe', 'Chiot impatient'], ['grelot', 'Grelot de la médaille'], ['couine', 'Câlin qui couine'], ['systeme', 'Sonnerie du téléphone']];
  let mock = [{ id: 1, h: 7, m: 30, days: [true, true, true, true, true, false, false], label: 'Debout pour le boulot', on: true, sound: 'wouf', vib: true, snooze: 5, douceur: true }, { id: 2, h: 10, m: 0, days: [false, false, false, false, false, true, true], label: 'Grasse mat’ du week-end', on: false, sound: 'grelot', vib: true, snooze: 10, douceur: true }];
  const R = window.Rev || {
    info: () => JSON.stringify({ alarms: mock, nextAt: Date.now() + 5.3 * 3600e3, sons: SONS0, exact: true }),
    save(j) { const o = JSON.parse(j); if (!o.id) o.id = Date.now() % 1e6; mock = mock.filter((x) => x.id !== o.id).concat([o]); return R.info(); },
    del(id) { mock = mock.filter((x) => x.id !== id); return R.info(); }, test() {}, preview() {}, stopPreview() {}, exactSettings() {}, close() {},
  };
  const rc = (fn, ...a) => { try { return R[fn] ? R[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  let S = J(rc('info'), {}) || {};

  function toast(msg) { let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); } t.textContent = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 3000); }
  function nextOf(a, from = Date.now()) {
    const d = a.days || [], any = d.some(Boolean); const c = new Date(from); c.setSeconds(0, 0); c.setHours(a.h, a.m);
    for (let k = 0; k < 8; k++) { if (c.getTime() > from && (!any || d[(c.getDay() + 6) % 7])) return c.getTime(); c.setDate(c.getDate() + 1); }
    return 0;
  }
  const inTxt = (t) => { const mn = Math.round((t - Date.now()) / 60000), h = Math.floor(mn / 60), m = mn % 60; return h ? `${h} h ${p2(m)}` : `${m} min`; };
  function daysTxt(d) {
    d = d || []; const n = d.filter(Boolean).length;
    if (!n) return 'Une seule fois'; if (n === 7) return 'Tous les jours';
    if (n === 5 && d.slice(0, 5).every(Boolean)) return 'En semaine'; if (n === 2 && d[5] && d[6]) return 'Le week-end';
    return d.map((v, i) => v ? DAYN[i].slice(0, 3) + '.' : '').filter(Boolean).join(' ');
  }

  function nixie() {
    const n = new Date(), s = p2(n.getHours()) + p2(n.getMinutes());
    $('#nixie').innerHTML = `<span class="tube"><b>${s[0]}</b></span><span class="tube"><b>${s[1]}</b></span><span class="ncol"><i></i><i></i></span><span class="tube"><b>${s[2]}</b></span><span class="tube"><b>${s[3]}</b></span>`;
    const al = (S.alarms || []).filter((a) => a.on !== false).map((a) => [nextOf(a), a]).filter((x) => x[0]).sort((x, y) => x[0] - y[0])[0];
    $('#rnext').innerHTML = al ? `⏰ Prochain réveil dans <b>${inTxt(al[0])}</b> · ${new Date(al[0]).toLocaleDateString('fr-FR', { weekday: 'long' })} ${p2(al[1].h)}:${p2(al[1].m)}` : '😴 Aucun réveil : grasse mat’ autorisée';
  }

  const SLEEPY = `<svg viewBox="0 0 220 120"><defs><radialGradient id="sp" cx=".38" cy=".3" r=".85"><stop offset="0" stop-color="#fff6e8"/><stop offset=".45" stop-color="#f6cf9a"/><stop offset=".8" stop-color="#d9934f"/><stop offset="1" stop-color="#9c5a24"/></radialGradient></defs>
    <ellipse cx="110" cy="100" rx="96" ry="16" fill="#ff3fa4" opacity=".55"/><ellipse cx="110" cy="96" rx="92" ry="14" fill="#ff7ab8"/>
    <ellipse cx="130" cy="78" rx="62" ry="26" fill="url(#sp)"/><path d="M58 52c-16 2-20 26-12 38 4 6 12 4 13-2l4-24z" fill="#8a4a1c"/><path d="M100 52c16 2 18 26 10 38-4 6-12 4-13-2l-3-24z" fill="#8a4a1c"/>
    <ellipse cx="80" cy="66" rx="30" ry="27" fill="url(#sp)"/><ellipse cx="80" cy="78" rx="16" ry="11" fill="#fff6ea"/>
    <path d="M66 62q6 5 12 0M84 62q6 5 12 0" stroke="#2b1206" stroke-width="2.6" fill="none" stroke-linecap="round"/><ellipse cx="80" cy="73" rx="6" ry="4" fill="#1a0b2e"/>
    <text x="120" y="34" font-family="Bungee" font-size="16" fill="#fff" opacity=".9">z</text><text x="136" y="20" font-family="Bungee" font-size="22" fill="#fff" opacity=".6">Z</text></svg>`;

  function render() {
    const al = (S.alarms || []).slice().sort((a, b) => a.h * 60 + a.m - (b.h * 60 + b.m));
    $('#rbody').innerHTML = `${S.exact === false ? `<div class="warnbox">⚠️ Android doit autoriser PupRéveil à sonner à l'heure exacte.<button class="ab small amber" data-a="exact" type="button">Autoriser</button></div>` : ''}
      ${al.length ? al.map((a) => {
        const on = a.on !== false, t = on ? nextOf(a) : 0;
        return `<section class="acard ${on ? 'on' : 'off'}" data-edit="${a.id}">
          <div class="atop">
            <div class="flip"><span class="flap"><b>${p2(a.h)}</b></span><span class="colon">:</span><span class="flap"><b>${p2(a.m)}</b></span></div>
            <div class="ainfo"><b>${esc(a.label || 'Réveil puppy')}</b><small>${esc(daysTxt(a.days))} · ${esc((S.sons || SONS0).find((s) => s[0] === a.sound)?.[1] || 'Wouf wouf du matin')}</small>${on && t ? `<div class="in">dans ${inTxt(t)}</div>` : ''}</div>
            <button class="sw${on ? ' on' : ''}" data-tog="${a.id}" type="button"><i></i></button>
          </div>
          <div class="days">${DAYS.map((d, i) => `<span class="tag${(a.days || [])[i] ? ' on' : ''}"><i class="ring"></i><span>${d}</span></span>`).join('')}</div>
        </section>`;
      }).join('') : `<div class="empty">${SLEEPY}<b>Aucun réveil pour l'instant</b><span>Le chiot fait la grasse mat'… Touche l'os pour créer ton premier réveil 🐾</span></div>`}`;
    nixie();
  }

  // ---------------- éditeur
  let E = null;
  function wheel(id, max, val) {
    const items = Array.from({ length: max }, (_, i) => `<div data-v="${i}">${p2(i)}</div>`).join('');
    return `<div class="wheel" id="${id}"><div class="pad"></div>${items}<div class="pad"></div></div>`;
  }
  function setWheel(id, v) { const w = $('#' + id); w.scrollTop = v * 62; }
  function readWheel(id) { const w = $('#' + id); return Math.round(w.scrollTop / 62); }
  function openEditor(a) {
    E = a ? JSON.parse(JSON.stringify(a)) : { id: 0, h: 7, m: 0, days: [true, true, true, true, true, false, false], label: '', on: true, sound: 'wouf', vib: true, snooze: 5, douceur: true };
    if (!E.days) E.days = [false, false, false, false, false, false, false];
    const v = $('#veil');
    v.innerHTML = `<div class="sheet"><i class="grip"></i><h3>${a ? 'Modifier le réveil' : 'Nouveau réveil'} ⏰</h3>
      <div class="wheels"><i class="band"></i>${wheel('wh', 24)}<span class="wsep">:</span>${wheel('wm', 60)}</div>
      <div class="days" id="edays"></div>
      <div class="chips"><button class="chip" data-preset="sem" type="button">En semaine</button><button class="chip" data-preset="we" type="button">Week-end</button><button class="chip" data-preset="all" type="button">Tous les jours</button><button class="chip" data-preset="one" type="button">Une seule fois</button></div>
      <label class="lbl">🏷️<input id="elabel" maxlength="40" placeholder="Nom du réveil (ex. Promenade du matin)" value="${esc(E.label)}"></label>
      <div class="sons" id="esons"></div>
      <div class="row"><span>Encore … de sieste<small>Le bouton « snooze » de l'écran de réveil</small></span></div>
      <div class="seg" id="esnz"></div>
      <div class="row"><span>Réveil en douceur<small>Le son monte petit à petit pendant 30 s</small></span><button class="sw${E.douceur !== false ? ' on' : ''}" data-esw="douceur" type="button"><i></i></button></div>
      <div class="row"><span>Vibreur<small>Le téléphone vibre en même temps</small></span><button class="sw${E.vib !== false ? ' on' : ''}" data-esw="vib" type="button"><i></i></button></div>
      <button class="ab wide green" data-e="save" type="button">${ic('check', 'chrome', 'none')}Enregistrer</button>
      <div class="nrow">${a ? `<button class="ab red" data-e="del" type="button">${ic('trash', 'chrome', 'none')}Supprimer</button>` : ''}<button class="ab c2" data-e="test" type="button">${ic('timer', 'chrome', 'none')}Tester dans 10 s</button><button class="ab glass" data-e="cancel" type="button">Annuler</button></div>
    </div>`;
    v.classList.remove('hidden');
    renderEd();
    requestAnimationFrame(() => { setWheel('wh', E.h); setWheel('wm', E.m); });
  }
  function renderEd() {
    $('#edays').innerHTML = DAYS.map((d, i) => `<button class="tag${E.days[i] ? ' on' : ''}" data-day="${i}" type="button"><i class="ring"></i><span>${d}</span></button>`).join('');
    $('#esons').innerHTML = (S.sons || SONS0).map(([k, l]) => `<div class="son${E.sound === k ? ' on' : ''}" data-son="${k}"><i class="rad"></i><b>${esc(l)}</b><button class="play" data-play="${k}" type="button">▶</button></div>`).join('');
    $('#esnz').innerHTML = [5, 10, 15].map((n) => `<button type="button" data-snz="${n}" class="${E.snooze === n ? 'on' : ''}">${n} min</button>`).join('');
  }
  function closeEditor() { rc('stopPreview'); $('#veil').classList.add('hidden'); $('#veil').innerHTML = ''; E = null; }
  function collect() { E.h = readWheel('wh'); E.m = readWheel('wm'); E.label = $('#elabel').value.trim(); return E; }

  document.addEventListener('click', (e) => {
    const t = e.target;
    const tog = t.closest('[data-tog]');
    if (tog) { e.stopPropagation(); const a = S.alarms.find((x) => x.id === +tog.dataset.tog); if (a) { a.on = !(a.on !== false); S = J(rc('save', JSON.stringify(a)), S) || S; render(); toast(a.on ? `⏰ Réveil ${p2(a.h)}:${p2(a.m)} activé 🐾` : 'Réveil en pause 😴'); } return; }
    const ed = t.closest('[data-edit]'); if (ed && !E) { openEditor(S.alarms.find((x) => x.id === +ed.dataset.edit)); return; }
    if (t.closest('#fab')) { openEditor(null); return; }
    if (t.closest('[data-a="exact"]')) { rc('exactSettings'); return; }
    if (!E) return;
    if (t === $('#veil')) { closeEditor(); return; }
    const d = t.closest('[data-day]'); if (d) { const i = +d.dataset.day; E.days[i] = !E.days[i]; renderEd(); return; }
    const pr = t.closest('[data-preset]'); if (pr) { const k = pr.dataset.preset; E.days = [0, 1, 2, 3, 4, 5, 6].map((i) => k === 'all' ? true : k === 'sem' ? i < 5 : k === 'we' ? i > 4 : false); renderEd(); return; }
    const pl = t.closest('[data-play]'); if (pl) { e.stopPropagation(); rc('preview', pl.dataset.play); return; }
    const sn = t.closest('[data-son]'); if (sn) { E.sound = sn.dataset.son; renderEd(); return; }
    const sz = t.closest('[data-snz]'); if (sz) { E.snooze = +sz.dataset.snz; renderEd(); return; }
    const sw = t.closest('[data-esw]'); if (sw) { const k = sw.dataset.esw; E[k] = !(E[k] !== false); sw.classList.toggle('on', E[k]); return; }
    const b = t.closest('[data-e]'); if (!b) return;
    const k = b.dataset.e;
    if (k === 'cancel') closeEditor();
    if (k === 'save') { const a = collect(); a.on = true; S = J(rc('save', JSON.stringify(a)), S) || S; closeEditor(); render(); const n = nextOf(a); toast(n ? `⏰ Réveil dans ${inTxt(n)} 🐾` : '⏰ Réveil enregistré'); }
    if (k === 'del') { S = J(rc('del', E.id), S) || S; closeEditor(); render(); toast('Réveil supprimé 🦴'); }
    if (k === 'test') { const a = collect(); a.on = true; S = J(rc('save', JSON.stringify(a)), S) || S; rc('test', (S.alarms.find((x) => x.h === a.h && x.m === a.m && x.label === a.label) || a).id); closeEditor(); render(); toast('Le réveil va sonner dans 10 secondes… éteins l’écran pour voir 🐾'); }
  });

  window.RevUI = {
    on(ev) { if (ev === 'resume') { S = J(rc('info'), S) || S; render(); } },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };
  $('#rlogo').innerHTML = ic('clock', 'amber');
  document.querySelector('.fab .fbone').innerHTML = `<svg viewBox="0 0 340 64" preserveAspectRatio="none"><defs><linearGradient id="fb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff"/><stop offset=".6" stop-color="#f6eadb"/><stop offset="1" stop-color="#cdb594"/></linearGradient></defs>
    <g fill="url(#fb)" stroke="#8a6a4a" stroke-width="2"><circle cx="28" cy="20" r="19"/><circle cx="28" cy="44" r="19"/><circle cx="312" cy="20" r="19"/><circle cx="312" cy="44" r="19"/></g>
    <rect x="22" y="13" width="296" height="38" rx="16" fill="url(#fb)"/><rect x="44" y="18" width="252" height="6" rx="3" fill="#fff" opacity=".85"/></svg>`;
  render();
  setInterval(nixie, 15000);
})();
