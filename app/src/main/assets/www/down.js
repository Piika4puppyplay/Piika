/* PupDown — 2 comptes à rebours + tirelire. Tout reste sur le téléphone (localStorage). */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const ic = (g, c, sh) => PupIcons.icon(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const P = window.Pup || { haptic() {}, toast() {}, share() {} };
  const hap = () => { try { P.haptic('tap'); } catch (e) {} };
  const snd = (n) => { try { window.PupSons && PupSons.play(n); } catch (e) {} };
  const pad = (n) => (n < 10 ? '0' : '') + n;
  const EMO = ['🎉', '🎸', '🏖️', '🎄', '✈️', '🎂', '💍', '🎓', '🏁', '🎁', '🌙', '🦴', '🐶', '❤️', '💸', '🎯'];
  const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
  const WD = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];
  const fmtMoney = (n) => (Math.round(n * 100) / 100).toFixed(2).replace('.', ',') + ' €';

  let S = load();
  function load() {
    try { const o = JSON.parse(localStorage.getItem('pupdown')); if (o && o.timers) return o; } catch (e) {}
    const now = Date.now();
    return { timers: [
      { id: 't1', name: 'Mon festival', emoji: '🎸', start: now, target: now + 1000 * 3600 * 24 * 60, pig: { goal: 400, saved: 0, lost: 0 } },
    ] };
  }
  function save() { try { localStorage.setItem('pupdown', JSON.stringify(S)); } catch (e) {} }

  // ---------------- rendu des cartes ----------------
  function parts(ms) {
    ms = Math.max(0, ms);
    const d = Math.floor(ms / 864e5), h = Math.floor(ms / 36e5) % 24, m = Math.floor(ms / 6e4) % 60, s = Math.floor(ms / 1e3) % 60;
    return { d, h, m, s };
  }
  function pct(t) { const span = t.target - t.start; if (span <= 0) return 100; return Math.max(0, Math.min(100, (Date.now() - t.start) / span * 100)); }

  function cardHTML(t, i) {
    const remain = t.target - Date.now(), done = remain <= 0, p = parts(remain), pr = pct(t);
    const prr = Math.round(pr);
    const date = new Date(t.target);
    const when = date.toLocaleDateString('fr-FR', { weekday: 'short', day: '2-digit', month: 'long', year: 'numeric' }) + ' · ' + pad(date.getHours()) + 'h' + pad(date.getMinutes());
    let pig = '';
    if (t.pig) {
      const g = t.pig.goal || 0, saved = t.pig.saved || 0, lost = t.pig.lost || 0;
      const gp = g > 0 ? Math.min(100, Math.round(saved / g * 100)) : 0;
      pig = `<div class="pig">
        <div class="pigh">${ic('bone', 'amber')}<b>Tirelire</b></div>
        <div class="pigstat">
          <div class="save"><small>Épargné</small><b>${fmtMoney(saved)}</b></div>
          <div class="goalv"><small>Objectif</small><b>${g > 0 ? gp + ' %' : '—'}</b></div>
          <div class="lost"><small>Argent perdu</small><b>${fmtMoney(lost)}</b></div>
        </div>
        <div class="pigbtns">
          <button class="ab green" data-a="add" data-i="${i}" type="button">＋ Déposer</button>
          <button class="ab red" data-a="take" data-i="${i}" type="button">− Retirer</button>
          <button class="ab small glass" data-a="goal" data-i="${i}" type="button">🎯</button>
        </div>
        ${g > 0 ? `<div class="piggoal">Objectif : <b>${fmtMoney(g)}</b> · reste ${fmtMoney(Math.max(0, g - saved))}</div>` : ''}
      </div>`;
    }
    return `<section class="tcard${done ? ' done' : ''}" style="--c:${i % 2 ? 'var(--acc2)' : 'var(--acc)'}">
      <div class="thead">
        <span class="temoji">${esc(t.emoji || '⏳')}</span>
        <div class="tname"><b>${esc(t.name || 'Sans nom')}</b><small>${esc(when)}</small></div>
        <button class="tedit" data-a="edit" data-i="${i}" type="button">${ic('wrench', 'chrome', 'none')}</button>
      </div>
      <div class="doneban">🎉 C'est le jour J ! 🦴</div>
      <div class="clockrow">
        <div class="unit"><b data-u="d">${p.d}</b><small>jours</small></div>
        <div class="unit"><b data-u="h">${pad(p.h)}</b><small>heures</small></div>
        <div class="unit"><b data-u="m">${pad(p.m)}</b><small>min</small></div>
        <div class="unit"><b data-u="s">${pad(p.s)}</b><small>sec</small></div>
      </div>
      <div class="pbox">
        <div class="pmeta"><span>Progression</span><b data-p="n">${prr} %</b></div>
        <div class="ptrack"><div class="pfill" data-p="f" style="width:calc(${pr}% )"></div><span class="pbone" data-p="b" style="left:${pr}%">🦴</span></div>
      </div>
      ${pig}
    </section>`;
  }

  function render() {
    const canAdd = S.timers.length < 2;
    $('#dbody').innerHTML = (S.timers.length ? S.timers.map(cardHTML).join('')
      : `<div class="empty">Aucun compte à rebours pour l'instant.<br>Ajoute ton premier objectif 🦴</div>`)
      + (canAdd ? `<button class="addt" data-a="new" type="button">${ic('bone', 'chrome', 'none')}Ajouter un compte à rebours</button>` : '');
  }

  // mise à jour légère chaque seconde (sans tout re-rendre)
  function tick() {
    const cards = document.querySelectorAll('.tcard');
    S.timers.forEach((t, i) => {
      const c = cards[i]; if (!c) return;
      const remain = t.target - Date.now(), done = remain <= 0;
      if (done !== c.classList.contains('done')) { render(); return; }
      if (done) return;
      const p = parts(remain), pr = pct(t);
      const set = (u, v) => { const el = c.querySelector(`[data-u="${u}"]`); if (el && el.textContent !== v) el.textContent = v; };
      set('d', String(p.d)); set('h', pad(p.h)); set('m', pad(p.m)); set('s', pad(p.s));
      const f = c.querySelector('[data-p="f"]'), b = c.querySelector('[data-p="b"]'), n = c.querySelector('[data-p="n"]');
      if (f) f.style.width = pr + '%'; if (b) b.style.left = pr + '%'; if (n) n.textContent = Math.round(pr) + ' %';
    });
  }

  // ---------------- feuille générique ----------------
  const sheet = $('#sheet');
  function openSheet(html) { sheet.innerHTML = `<div class="dbox"><div class="dgrab"></div>${html}</div>`; sheet.classList.remove('hidden'); snd('pop'); }
  function closeSheet() { sheet.classList.add('hidden'); sheet.innerHTML = ''; }
  sheet.addEventListener('click', (e) => { if (e.target === sheet) closeSheet(); });

  // ---------------- éditeur de chrono ----------------
  let draft = null;
  function editTimer(i) {
    const isNew = i == null;
    const now = Date.now();
    draft = isNew
      ? { id: 't' + now, name: '', emoji: '🎯', start: now, target: now + 864e5 * 30, pig: S.timers.length === 0 ? { goal: 0, saved: 0, lost: 0 } : null, _new: true }
      : JSON.parse(JSON.stringify(S.timers[i]));
    draft._i = i;
    renderEditor();
  }
  function renderEditor() {
    const d = new Date(draft.target);
    const dstr = d.toLocaleDateString('fr-FR', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' });
    const tstr = pad(d.getHours()) + ' h ' + pad(d.getMinutes());
    openSheet(`
      <h3>${draft._new ? 'Nouveau compte à rebours' : 'Modifier'}</h3>
      <p class="sub">Choisis la date et l'heure de ton objectif.</p>
      <div class="fld"><label>Nom</label><input type="text" id="enm" maxlength="40" value="${esc(draft.name)}" placeholder="Festival, vacances, anniversaire…"></div>
      <div class="fld"><label>Icône</label><div class="emojipick" id="emo">${EMO.map((e) => `<button type="button" data-e="${e}" class="${e === draft.emoji ? 'sel' : ''}">${e}</button>`).join('')}</div></div>
      <div class="fld"><label>Échéance</label><div class="dtbtn">
        <button type="button" data-a="pickdate">📅<small>Date</small><b>${esc(dstr)}</b></button>
        <button type="button" data-a="picktime">🕐<small>Heure</small><b>${esc(tstr)}</b></button>
      </div></div>
      ${draft.pig ? `<div class="warn">🐷 Cette tirelire est sur ce chrono. Les retraits deviennent de l'« argent perdu » définitif.</div>` : ''}
      <div class="dactions">
        <button class="ab glass" data-a="cancel" type="button">Annuler</button>
        <button class="ab green" data-a="saveT" type="button">${ic('check', 'chrome', 'none')}Enregistrer</button>
      </div>
      ${!draft._new ? `<div class="delrow"><button data-a="del" type="button">Supprimer ce compte à rebours</button></div>` : ''}
    `);
    const nm = $('#enm'); if (nm) nm.addEventListener('input', () => { draft.name = nm.value; });
  }

  // ---------------- calendrier ----------------
  function pickDate() {
    let view = new Date(draft.target); view.setDate(1);
    const sel = new Date(draft.target);
    const renderCal = () => {
      const y = view.getFullYear(), mo = view.getMonth();
      const first = new Date(y, mo, 1); let off = (first.getDay() + 6) % 7; // lundi=0
      const days = new Date(y, mo + 1, 0).getDate();
      const today = new Date(); today.setHours(0, 0, 0, 0);
      let cells = '';
      for (let k = 0; k < off; k++) cells += `<div class="cald out"></div>`;
      for (let day = 1; day <= days; day++) {
        const dd = new Date(y, mo, day); dd.setHours(0, 0, 0, 0);
        const isSel = dd.getFullYear() === sel.getFullYear() && dd.getMonth() === sel.getMonth() && dd.getDate() === sel.getDate();
        const isToday = dd.getTime() === today.getTime();
        const past = dd.getTime() < today.getTime();
        cells += `<div class="cald${isSel ? ' sel' : ''}${isToday ? ' today' : ''}${past ? ' past' : ''}" data-day="${day}">${day}</div>`;
      }
      openSheet(`
        <h3>📅 Choisir la date</h3>
        <div class="cal">
          <div class="calnav"><button data-a="pm" type="button">‹</button><b>${MONTHS[mo]} ${y}</b><button data-a="nm" type="button">›</button></div>
          <div class="calgrid">${WD.map((w) => `<div class="calw">${w}</div>`).join('')}${cells}</div>
        </div>
        <div class="dactions"><button class="ab glass" data-a="backedit" type="button">Retour</button></div>`);
      sheet.querySelector('[data-a="pm"]').onclick = () => { view.setMonth(mo - 1); renderCal(); };
      sheet.querySelector('[data-a="nm"]').onclick = () => { view.setMonth(mo + 1); renderCal(); };
      sheet.querySelectorAll('.cald[data-day]').forEach((el) => el.onclick = () => {
        if (el.classList.contains('past')) return;
        hap();
        const nd = new Date(draft.target);
        nd.setFullYear(y, mo, +el.dataset.day);
        draft.target = nd.getTime(); renderEditor();
      });
      sheet.querySelector('[data-a="backedit"]').onclick = renderEditor;
    };
    renderCal();
  }

  // ---------------- horloge ----------------
  function pickTime() {
    let mode = 'h';
    const cur = new Date(draft.target);
    let hh = cur.getHours(), mm = cur.getMinutes();
    const renderClock = () => {
      let nums = '';
      const R = 110, Rin = 70;
      if (mode === 'h') {
        // anneau externe : 12 (midi) et 1..11 ; anneau interne : 00 (minuit) et 13..23
        for (let n = 0; n < 12; n++) { const a = (n * 30 - 90) * Math.PI / 180; const x = 125 + Math.cos(a) * R, y = 125 + Math.sin(a) * R; const hv = n === 0 ? 12 : n; nums += `<div class="num${hh === hv ? ' on' : ''}" style="left:${x}px;top:${y}px">${hv}</div>`; }
        for (let n = 0; n < 12; n++) { const a = (n * 30 - 90) * Math.PI / 180; const x = 125 + Math.cos(a) * Rin, y = 125 + Math.sin(a) * Rin; const hv = n === 0 ? 0 : n + 12; nums += `<div class="num${hh === hv ? ' on' : ''}" style="left:${x}px;top:${y}px;font-size:12px;opacity:.85">${pad(hv)}</div>`; }
      } else {
        for (let n = 0; n < 12; n++) { const a = (n * 30 - 90) * Math.PI / 180; const x = 125 + Math.cos(a) * R, y = 125 + Math.sin(a) * R; const v = n * 5; nums += `<div class="num${mm === v ? ' on' : ''}" style="left:${x}px;top:${y}px">${pad(v)}</div>`; }
      }
      const ang = mode === 'h' ? (hh % 12) * 30 : mm * 6;
      const len = mode === 'h' ? (hh === 0 || hh >= 13 ? 62 : 100) : 100;
      openSheet(`
        <h3>🕐 Choisir l'heure</h3>
        <div class="clockpick">
          <div class="cdisp"><span class="${mode === 'h' ? 'on' : ''}" data-a="mh">${pad(hh)}</span><span>:</span><span class="${mode === 'm' ? 'on' : ''}" data-a="mm">${pad(mm)}</span></div>
          <div class="cface" id="cf">${nums}<div class="hand" style="height:${len}px;transform:translateX(-50%) rotate(${ang}deg)"></div><div class="hub"></div></div>
          <div class="cmode"><button class="${mode === 'h' ? 'on' : ''}" data-a="mh" type="button">Heures</button><button class="${mode === 'm' ? 'on' : ''}" data-a="mm" type="button">Minutes</button></div>
        </div>
        <div class="dactions"><button class="ab glass" data-a="backedit" type="button">Retour</button><button class="ab green" data-a="oktime" type="button">${ic('check', 'chrome', 'none')}OK</button></div>`);
      const cf = $('#cf');
      const fromPoint = (clientX, clientY) => {
        const r = cf.getBoundingClientRect();
        const dx = clientX - (r.left + 125), dy = clientY - (r.top + 125);
        let deg = Math.atan2(dy, dx) * 180 / Math.PI + 90; if (deg < 0) deg += 360;
        const dist = Math.hypot(dx, dy);
        if (mode === 'h') { const slot = Math.round(deg / 30) % 12; const inner = dist < 92; hh = inner ? (slot === 0 ? 0 : slot + 12) : (slot === 0 ? 12 : slot); }
        else { mm = (Math.round(deg / 6) % 60); }
        renderClock();
      };
      let drag = false;
      cf.addEventListener('pointerdown', (e) => { drag = true; cf.setPointerCapture(e.pointerId); fromPoint(e.clientX, e.clientY); hap(); });
      cf.addEventListener('pointermove', (e) => { if (drag) fromPoint(e.clientX, e.clientY); });
      cf.addEventListener('pointerup', () => { drag = false; if (mode === 'h') { mode = 'm'; renderClock(); } });
      sheet.querySelectorAll('[data-a="mh"]').forEach((b) => b.onclick = () => { mode = 'h'; renderClock(); });
      sheet.querySelectorAll('[data-a="mm"]').forEach((b) => b.onclick = () => { mode = 'm'; renderClock(); });
      sheet.querySelector('[data-a="backedit"]').onclick = applyAndEdit;
      sheet.querySelector('[data-a="oktime"]').onclick = applyAndEdit;
    };
    const applyAndEdit = () => { const nd = new Date(draft.target); nd.setHours(hh, mm, 0, 0); draft.target = nd.getTime(); renderEditor(); };
    renderClock();
  }

  // ---------------- tirelire : montants ----------------
  function amountSheet(kind, i) {
    const t = S.timers[i]; if (!t || !t.pig) return;
    const take = kind === 'take';
    openSheet(`
      <h3>${take ? '− Retirer de la tirelire' : '＋ Déposer dans la tirelire'}</h3>
      <p class="sub">${take ? 'Épargné : ' + fmtMoney(t.pig.saved) : 'Chaque dépôt te rapproche de ton objectif 🦴'}</p>
      <div class="fld"><label>Montant (€)</label><input type="number" id="amt" inputmode="decimal" step="0.01" min="0" placeholder="0,00"></div>
      ${take ? `<div class="warn">⚠️ Un retrait est <b>définitif</b> : l'argent retiré devient de l'« argent perdu » cumulé. On ne peut ni le modifier ni l'annuler.</div>` : ''}
      <div class="dactions"><button class="ab glass" data-a="cancel" type="button">Annuler</button>
      <button class="ab ${take ? 'red' : 'green'}" data-a="${take ? 'dotake' : 'doadd'}" data-i="${i}" type="button">${take ? 'Confirmer le retrait' : 'Déposer'}</button></div>`);
    setTimeout(() => { const a = $('#amt'); if (a) a.focus(); }, 60);
  }
  function goalSheet(i) {
    const t = S.timers[i]; if (!t || !t.pig) return;
    openSheet(`
      <h3>🎯 Objectif d'épargne</h3>
      <p class="sub">Le montant que tu veux atteindre avant le jour J.</p>
      <div class="fld"><label>Objectif (€)</label><input type="number" id="amt" inputmode="decimal" step="1" min="0" value="${t.pig.goal || ''}" placeholder="400"></div>
      <div class="dactions"><button class="ab glass" data-a="cancel" type="button">Annuler</button>
      <button class="ab amber" data-a="dogoal" data-i="${i}" type="button">Enregistrer l'objectif</button></div>`);
    setTimeout(() => { const a = $('#amt'); if (a) a.focus(); }, 60);
  }
  const amt = () => { const v = parseFloat(($('#amt') || {}).value); return isNaN(v) || v < 0 ? 0 : Math.round(v * 100) / 100; };

  // ---------------- clics ----------------
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-a],[data-e]'); if (!b) return;
    const a = b.dataset.a, i = b.dataset.i != null ? +b.dataset.i : null;
    if (b.dataset.e) { draft.emoji = b.dataset.e; renderEditor(); return; }
    if (a === 'new') { hap(); editTimer(null); }
    else if (a === 'edit') editTimer(i);
    else if (a === 'cancel') closeSheet();
    else if (a === 'pickdate') pickDate();
    else if (a === 'picktime') pickTime();
    else if (a === 'saveT') {
      if (!draft.name.trim()) draft.name = draft.emoji + ' Objectif';
      if (draft.target <= Date.now()) { P.toast('La date est déjà passée 🐾'); return; }
      const copy = { id: draft.id, name: draft.name.trim(), emoji: draft.emoji, start: draft._new ? Date.now() : draft.start, target: draft.target, pig: draft.pig };
      if (draft._new) S.timers.push(copy); else S.timers[draft._i] = copy;
      save(); render(); closeSheet(); snd('wouf2');
    }
    else if (a === 'del') {
      if (b.dataset.sure) { S.timers.splice(draft._i, 1); save(); render(); closeSheet(); }
      else { b.dataset.sure = '1'; b.textContent = 'Appuie encore pour confirmer'; }
    }
    else if (a === 'add') amountSheet('add', i);
    else if (a === 'take') amountSheet('take', i);
    else if (a === 'goal') goalSheet(i);
    else if (a === 'doadd') { const v = amt(); if (v > 0) { S.timers[i].pig.saved = Math.round((S.timers[i].pig.saved + v) * 100) / 100; save(); render(); snd('wouf2'); } closeSheet(); }
    else if (a === 'dotake') {
      let v = amt(); const pig = S.timers[i].pig; if (v > pig.saved) v = pig.saved;
      if (v > 0) { pig.saved = Math.round((pig.saved - v) * 100) / 100; pig.lost = Math.round((pig.lost + v) * 100) / 100; save(); render(); snd('couine'); }
      closeSheet();
    }
    else if (a === 'dogoal') { S.timers[i].pig.goal = amt(); save(); render(); closeSheet(); }
  });

  window.DownUI = { on() {}, back() { if (!sheet.classList.contains('hidden')) { closeSheet(); return true; } return false; } };
  $('#dlogo').innerHTML = ic('bone', 'pink');
  render();
  setInterval(tick, 1000);
})();
