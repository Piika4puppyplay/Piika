/* PupAgenda — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const P = window.Pup || { setReminders() {}, share() {} };
  const p2 = (n) => String(n).padStart(2, '0');
  const ymd = (d) => `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`;
  const parse = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
  const COLS = ['#ff3fa4', '#29e6ff', '#3dffb0', '#ffb627', '#9b5cff', '#ff5a3c'];
  const REMS = [[-1, 'Aucun'], [0, 'À l’heure'], [10, '10 min'], [60, '1 h'], [1440, '1 jour']];
  const REPS = [['', 'Jamais'], ['w', 'Chaque sem.'], ['m', 'Chaque mois'], ['y', 'Chaque an 🎂']];
  const KEY = 'pupagenda';
  let evs = [];
  try { evs = JSON.parse(localStorage.getItem(KEY) || '[]'); } catch (e) { evs = []; }
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(evs)); } catch (e) { } syncRem(); };
  const today = new Date(); today.setHours(0, 0, 0, 0);
  let view = new Date(today.getFullYear(), today.getMonth(), 1), sel = new Date(today);

  function toast(msg) { let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); } t.textContent = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 3000); }
  /** l'événement tombe-t-il ce jour-là (avec répétitions) ? */
  function on(e, d) {
    const s = parse(e.date); if (d < s) return false;
    if (!e.rep) return +s === +d;
    if (e.rep === 'w') return Math.round((d - s) / 864e5) % 7 === 0;
    if (e.rep === 'm') return d.getDate() === s.getDate();
    if (e.rep === 'y') return d.getDate() === s.getDate() && d.getMonth() === s.getMonth();
    return false;
  }
  const dayEvs = (d) => evs.filter((e) => on(e, d)).sort((a, b) => (a.time || '00:00').localeCompare(b.time || '00:00'));

  function render() {
    $('#rings').innerHTML = '<i></i>'.repeat(8);
    $('#mt').textContent = view.toLocaleDateString('fr-FR', { month: 'long' });
    $('#my').textContent = view.getFullYear();
    const first = new Date(view), start = new Date(first); start.setDate(1 - ((first.getDay() + 6) % 7));
    let h = '';
    for (let i = 0; i < 42; i++) {
      const d = new Date(start); d.setDate(start.getDate() + i);
      if (i >= 35 && d.getMonth() !== view.getMonth()) break;
      const E = dayEvs(d), cls = ['d', d.getMonth() !== view.getMonth() ? 'out' : '', (d.getDay() % 6 === 0) ? 'we' : '', +d === +today ? 'today' : '', +d === +sel ? 'sel' : ''].join(' ');
      h += `<button class="${cls}" data-day="${ymd(d)}" type="button">${d.getDate()}<span class="dots">${E.slice(0, 4).map((e) => `<i style="background:${e.col || COLS[0]}"></i>`).join('')}</span></button>`;
    }
    $('#days').innerHTML = h;
    const isToday = +sel === +today;
    $('#dt').textContent = (isToday ? "Aujourd'hui · " : '') + sel.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
    const L = dayEvs(sel);
    $('#list').innerHTML = L.length ? L.map((e) => `<div class="ev" data-ev="${e.id}" style="--c:${e.col || COLS[0]}"><div class="stub">${e.time ? `<b>${e.time}</b><small>${e.rem >= 0 ? '🔔' : ''}</small>` : '<b>🐾</b><small>JOURNÉE</small>'}</div><div class="bd"><b>${esc(e.t)}</b>${e.note ? `<p>${esc(e.note)}</p>` : ''}<span>${e.rep ? (REPS.find((r) => r[0] === e.rep) || [])[1] + ' · ' : ''}${e.rem >= 0 ? 'Rappel ' + (REMS.find((r) => r[0] === e.rem) || [])[1] : 'Sans rappel'}</span></div></div>`).join('')
      : `<div class="empty"><b>Rien de prévu 🐶</b>Journée libre pour les câlins et les balades. Touche « Nouvel événement » pour ajouter quelque chose.</div>`;
  }

  // ---------------- rappels natifs (prochains 60 jours)
  function syncRem() {
    const out = [], now = Date.now();
    for (let k = 0; k < 60; k++) {
      const d = new Date(today); d.setDate(today.getDate() + k);
      for (const e of evs) {
        if (e.rem == null || e.rem < 0 || !on(e, d)) continue;
        const [hh, mm] = (e.time || '09:00').split(':').map(Number);
        const at = new Date(d); at.setHours(hh, mm, 0, 0);
        const fire = at.getTime() - e.rem * 60000;
        if (fire > now - 60000) out.push({ id: e.id, at: fire, title: e.t, when: (e.time ? `${e.time} · ` : 'Toute la journée · ') + at.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' }) });
      }
    }
    try { P.setReminders(JSON.stringify(out.slice(0, 200))); } catch (e) { }
  }

  // ---------------- éditeur
  let E = null;
  function open(e) {
    E = e ? JSON.parse(JSON.stringify(e)) : { id: Date.now(), t: '', date: ymd(sel), time: '', col: COLS[0], rem: 10, rep: '', note: '' };
    const v = $('#veil');
    v.innerHTML = `<div class="sheet"><h3>${e ? 'Modifier' : 'Nouvel événement'} 📅</h3>
      <label class="fld">🏷️<input id="et" placeholder="Quoi ? (ex. Toilettage, Anniv de Rex)" value="${esc(E.t)}" maxlength="60"></label>
      <div class="two"><label class="fld">📆<input id="ed" type="date" value="${E.date}"></label><label class="fld">⏰<input id="eh" type="time" value="${E.time}"></label></div>
      <div class="lbl2">Rappel</div><div class="seg" id="er">${REMS.map(([v, l]) => `<button type="button" data-rem="${v}" class="${E.rem === v ? 'on' : ''}">${l}</button>`).join('')}</div>
      <div class="lbl2">Répéter</div><div class="seg" id="ep">${REPS.map(([v, l]) => `<button type="button" data-rep="${v}" class="${E.rep === v ? 'on' : ''}">${l}</button>`).join('')}</div>
      <div class="lbl2">Couleur du collier</div><div class="cols" id="ec">${COLS.map((c) => `<i data-col="${c}" class="${E.col === c ? 'on' : ''}" style="--c:${c}"></i>`).join('')}</div>
      <label class="fld">📝<textarea id="en" placeholder="Note (adresse, détails…)">${esc(E.note)}</textarea></label>
      <button class="ab wide green" data-e="save" type="button">✓ Enregistrer</button>
      <div class="nrow">${e ? '<button class="ab red" data-e="del" type="button">Supprimer</button><button class="ab c2" data-e="share" type="button">Partager</button>' : ''}<button class="ab glass" data-e="cancel" type="button">Annuler</button></div></div>`;
    v.classList.remove('hidden');
    if (!e) setTimeout(() => $('#et').focus(), 300);
  }
  function close() { $('#veil').classList.add('hidden'); $('#veil').innerHTML = ''; E = null; }
  function collect() { E.t = $('#et').value.trim() || 'Événement 🐾'; E.date = $('#ed').value || ymd(sel); E.time = $('#eh').value; E.note = $('#en').value.trim(); }

  document.addEventListener('click', (ev) => {
    const t = ev.target;
    const nv = t.closest('[data-nav]'); if (nv) { view = new Date(view.getFullYear(), view.getMonth() + +nv.dataset.nav, 1); render(); return; }
    const dd = t.closest('[data-day]'); if (dd) { sel = parse(dd.dataset.day); if (sel.getMonth() !== view.getMonth()) view = new Date(sel.getFullYear(), sel.getMonth(), 1); render(); return; }
    const ce = t.closest('[data-ev]'); if (ce && !E) { open(evs.find((x) => x.id === +ce.dataset.ev)); return; }
    if (t.closest('#fab')) { open(null); return; }
    if (!E) return;
    if (t === $('#veil')) { close(); return; }
    const r = t.closest('[data-rem]'); if (r) { E.rem = +r.dataset.rem; document.querySelectorAll('[data-rem]').forEach((b) => b.classList.toggle('on', b === r)); return; }
    const rp = t.closest('[data-rep]'); if (rp) { E.rep = rp.dataset.rep; document.querySelectorAll('[data-rep]').forEach((b) => b.classList.toggle('on', b === rp)); return; }
    const c = t.closest('[data-col]'); if (c) { E.col = c.dataset.col; document.querySelectorAll('[data-col]').forEach((b) => b.classList.toggle('on', b === c)); return; }
    const b = t.closest('[data-e]'); if (!b) return;
    const k = b.dataset.e;
    if (k === 'cancel') close();
    if (k === 'save') { collect(); evs = evs.filter((x) => x.id !== E.id).concat([E]); save(); sel = parse(E.date); view = new Date(sel.getFullYear(), sel.getMonth(), 1); close(); render(); toast('📅 C’est noté dans l’agenda 🐾'); }
    if (k === 'del') { evs = evs.filter((x) => x.id !== E.id); save(); close(); render(); toast('Événement effacé 🦴'); }
    if (k === 'share') { collect(); P.share(E.t, `${E.t}\n${parse(E.date).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })}${E.time ? ' à ' + E.time : ''}${E.note ? '\n' + E.note : ''}`); }
  });

  window.AgendaUI = { on(ev) { if (ev === 'resume') render(); }, back() { if (E) { close(); return true; } return false; } };
  render(); syncRem();
})();
