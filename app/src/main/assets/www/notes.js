/* PupNotes — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const P = window.Pup || { share() {}, haptic() {} };
  const COLORS = [['#fffbea', 'Crème'], ['#ffe3ef', 'Rose'], ['#e2f8ec', 'Menthe'], ['#e3f0ff', 'Ciel'], ['#fff1cc', 'Miel'], ['#efe6ff', 'Lavande']];
  const KEY = 'pupnotes';
  let notes = [];
  try { notes = JSON.parse(localStorage.getItem(KEY) || '[]'); } catch (e) { notes = []; }
  if (!notes.length && !localStorage.getItem(KEY + '_hello')) {
    notes = [
      { id: 1, t: 'Bienvenue dans ton carnet 🐾', b: 'Touche « Nouvelle note » pour écrire, ou « Liste » pour une liste à cocher.\nÉpingle tes notes importantes avec la punaise-patte 📌', c: '#fffbea', pin: true, at: Date.now() },
      { id: 2, t: 'Courses du chiot', list: [{ x: 'Croquettes', ok: true }, { x: 'Os à mâcher', ok: false }, { x: 'Nouvelle balle qui couine', ok: false }], c: '#ffe3ef', at: Date.now() - 3600e3 },
    ];
    localStorage.setItem(KEY + '_hello', '1');
  }
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(notes)); } catch (e) { } };
  // la corbeille garde les notes 30 jours
  notes = notes.filter((n) => !n.del || Date.now() - n.del < 30 * 864e5); save();
  let filter = 'all', q = '';
  const PINSVG = '<svg viewBox="0 0 24 24"><ellipse cx="12" cy="16" rx="5.4" ry="4.6"/><circle cx="5.6" cy="10.4" r="2.3"/><circle cx="9.4" cy="6.4" r="2.3"/><circle cx="14.6" cy="6.4" r="2.3"/><circle cx="18.4" cy="10.4" r="2.3"/></svg>';
  const fmt = (t) => new Date(t).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' }) + ' · ' + new Date(t).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });

  function toast(msg, undo) {
    let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
    t.innerHTML = `<span>${esc(msg)}</span>${undo ? '<button type="button" data-undo>Annuler</button>' : ''}`; t._undo = undo;
    t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 4000);
  }

  function renderChips() {
    const n = (f) => notes.filter((x) => f(x)).length;
    const C = [['all', `Toutes · ${n((x) => !x.del)}`], ['pin', `📌 Épinglées · ${n((x) => !x.del && x.pin)}`], ['list', `☑︎ Listes · ${n((x) => !x.del && x.list)}`], ['bin', `🗑 Corbeille · ${n((x) => x.del)}`]];
    $('#chips').innerHTML = C.map(([k, l]) => `<button class="chip${filter === k ? ' on' : ''}" data-f="${k}" type="button">${l}</button>`).join('');
  }
  function render() {
    renderChips();
    const ql = q.toLowerCase();
    let L = notes.filter((n) => filter === 'bin' ? n.del : !n.del && (filter !== 'pin' || n.pin) && (filter !== 'list' || n.list));
    if (ql) L = L.filter((n) => ((n.t || '') + ' ' + (n.b || '') + ' ' + (n.list || []).map((i) => i.x).join(' ')).toLowerCase().includes(ql));
    L.sort((a, b) => (b.pin ? 1 : 0) - (a.pin ? 1 : 0) || (b.at || 0) - (a.at || 0));
    $('#grid').innerHTML = L.length ? L.map((n, i) => {
      const rot = ((n.id * 37) % 7 - 3) * .5, tr = ((n.id * 13) % 9 - 4);
      const body = n.list ? `<ul>${n.list.slice(0, 8).map((it) => `<li class="${it.ok ? 'chk' : ''}"><span class="cb">${it.ok ? '✓' : ''}</span>${esc(it.x)}</li>`).join('')}${n.list.length > 8 ? `<li>… +${n.list.length - 8}</li>` : ''}</ul>` : `<p>${esc(n.b || '')}</p>`;
      return `<article class="paper" data-id="${n.id}" style="--pc:${n.c || '#fffbea'};--rot:${rot}deg;--tr:${tr}deg">${n.pin ? `<span class="pin">${PINSVG}</span>` : '<i class="tape"></i>'}${n.t ? `<h4>${esc(n.t)}</h4>` : ''}${body}<span class="date">${n.del ? '🗑 ' : ''}${fmt(n.at || Date.now())}</span></article>`;
    }).join('') : `<div class="empty"><b>${filter === 'bin' ? 'La corbeille est vide 🦴' : q ? 'Le chiot n’a rien trouvé 👃' : 'Aucune note pour l’instant'}</b><span>${filter === 'bin' ? 'Les notes supprimées y restent 30 jours.' : 'Touche « Nouvelle note » pour commencer 🐾'}</span></div>`;
  }

  // ---------------- éditeur
  let E = null, isNew = false;
  function open(n, list) {
    isNew = !n;
    E = n ? JSON.parse(JSON.stringify(n)) : { id: Date.now(), t: '', b: '', c: '#fffbea', pin: false, at: Date.now() };
    if (!n && list) E.list = [{ x: '', ok: false }];
    const ed = $('#ed');
    ed.innerHTML = `<div class="sheet" style="--pc:${E.c}"><i class="holes"></i><div class="in">
        <input class="t" id="et" placeholder="Titre" value="${esc(E.t)}" maxlength="80">
        <div id="ebody"></div>
        <div class="meta">${E.del ? 'Dans la corbeille · ' : ''}Modifiée ${fmt(E.at)}</div>
      </div></div>
      <div class="tools">
        <button type="button" data-t="color" title="Couleur">🎨</button>
        <button type="button" data-t="pin" class="${E.pin ? 'on' : ''}" title="Épingler">📌</button>
        <button type="button" data-t="list" class="${E.list ? 'on' : ''}" title="Liste">☑︎</button>
        <button type="button" data-t="share" title="Partager">📤</button>
        <button type="button" data-t="${E.del ? 'restore' : 'del'}" title="${E.del ? 'Restaurer' : 'Supprimer'}">${E.del ? '♻️' : '🗑'}</button>
        <button type="button" data-t="done" class="done">✓ Ranger</button>
      </div>`;
    ed.classList.remove('hidden');
    renderBody();
    if (isNew) setTimeout(() => (E.list ? $('#ebody input') : $('#et')).focus(), 250);
  }
  function renderBody() {
    const b = $('#ebody');
    if (E.list) {
      b.innerHTML = `<ul class="items">${E.list.map((it, i) => `<li class="${it.ok ? 'chk' : ''}"><span class="cb ${it.ok ? 'chk' : ''}" data-ck="${i}">${it.ok ? '✓' : ''}</span><input data-it="${i}" value="${esc(it.x)}" placeholder="Élément"><button class="x" data-rm="${i}" type="button">✕</button></li>`).join('')}</ul><button class="add" data-add type="button">＋ Ajouter un élément</button>`;
    } else {
      b.innerHTML = `<textarea id="eb" placeholder="Écris ici… 🐾">${esc(E.b || '')}</textarea>`;
      const ta = $('#eb'); const fit = () => { ta.style.height = 'auto'; ta.style.height = Math.max(ta.scrollHeight, window.innerHeight * .6) + 'px'; }; ta.addEventListener('input', fit); setTimeout(fit, 0);
    }
  }
  function collect() {
    E.t = $('#et').value.trim();
    if (E.list) { document.querySelectorAll('[data-it]').forEach((inp) => { E.list[+inp.dataset.it].x = inp.value; }); E.list = E.list.filter((it, i, a) => it.x.trim() || a.length === 1); }
    else E.b = $('#eb').value;
  }
  function close(saveIt = true) {
    if (!E) return false;
    collect();
    const empty = !E.t && !(E.b || '').trim() && !(E.list || []).some((it) => it.x.trim());
    if (saveIt && !empty) {
      const old = notes.find((n) => n.id === E.id);
      if (!old || JSON.stringify(old) !== JSON.stringify(E)) E.at = Date.now();
      notes = notes.filter((n) => n.id !== E.id).concat([E]); save();
    } else if (empty && !isNew) { notes = notes.filter((n) => n.id !== E.id); save(); }
    $('#ed').classList.add('hidden'); $('#ed').innerHTML = ''; E = null; render();
    return true;
  }

  document.addEventListener('input', (e) => { if (e.target.id === 'q') { q = e.target.value; render(); } });
  document.addEventListener('keydown', (e) => {
    if (!E || !E.list || e.key !== 'Enter' || !e.target.dataset.it) return;
    e.preventDefault(); collect(); const i = +e.target.dataset.it; E.list.splice(i + 1, 0, { x: '', ok: false }); renderBody(); $(`[data-it="${i + 1}"]`).focus();
  });
  document.addEventListener('click', (e) => {
    const t = e.target;
    if (t.closest('[data-undo]')) { const tt = $('.toast'); if (tt._undo) tt._undo(); tt.classList.remove('on'); return; }
    const f = t.closest('[data-f]'); if (f) { filter = f.dataset.f; render(); return; }
    if (t.closest('#fabN')) { open(null, false); return; }
    if (t.closest('#fabL')) { open(null, true); return; }
    const card = t.closest('.paper[data-id]'); if (card && !E) { open(notes.find((n) => n.id === +card.dataset.id)); return; }
    if (!E) return;
    const ck = t.closest('[data-ck]'); if (ck) { collect(); const it = E.list[+ck.dataset.ck]; it.ok = !it.ok; P.haptic && P.haptic(); renderBody(); return; }
    const rm = t.closest('[data-rm]'); if (rm) { collect(); E.list.splice(+rm.dataset.rm, 1); if (!E.list.length) E.list.push({ x: '', ok: false }); renderBody(); return; }
    if (t.closest('[data-add]')) { collect(); E.list.push({ x: '', ok: false }); renderBody(); const ins = document.querySelectorAll('[data-it]'); ins[ins.length - 1].focus(); return; }
    const col = t.closest('[data-col]'); if (col) { E.c = col.dataset.col; $('.sheet').style.setProperty('--pc', E.c); $('.colors').remove(); return; }
    const tb = t.closest('[data-t]'); if (!tb) return;
    const k = tb.dataset.t;
    if (k === 'color') { const ex = $('.colors'); if (ex) { ex.remove(); return; } const d = document.createElement('div'); d.className = 'colors'; d.innerHTML = COLORS.map(([c]) => `<i data-col="${c}" class="${E.c === c ? 'on' : ''}" style="background:${c}"></i>`).join(''); $('.tools').appendChild(d); }
    if (k === 'pin') { E.pin = !E.pin; tb.classList.toggle('on', E.pin); }
    if (k === 'list') {
      collect();
      if (E.list) { E.b = E.list.map((it) => (it.ok ? '✓ ' : '• ') + it.x).join('\n'); delete E.list; }
      else { E.list = (E.b || '').split('\n').map((x) => x.replace(/^([•✓\-\*]\s*)/, '')).filter((x) => x.trim()).map((x) => ({ x, ok: false })); if (!E.list.length) E.list = [{ x: '', ok: false }]; delete E.b; }
      tb.classList.toggle('on', !!E.list); renderBody();
    }
    if (k === 'share') { collect(); P.share(E.t || 'Note PupNotes', (E.t ? E.t + '\n\n' : '') + (E.list ? E.list.map((it) => (it.ok ? '☑ ' : '☐ ') + it.x).join('\n') : E.b)); }
    if (k === 'del') { collect(); const id = E.id; E.del = Date.now(); close(); toast('Note mise à la corbeille 🗑', () => { const n = notes.find((x) => x.id === id); if (n) { delete n.del; save(); render(); } }); }
    if (k === 'restore') { delete E.del; close(); toast('Note restaurée ♻️'); }
    if (k === 'done') close();
  });

  window.NotesUI = { on() {}, back() { if (E) { close(); return true; } if (filter !== 'all' || q) { filter = 'all'; q = ''; $('#q').value = ''; render(); return true; } return false; } };
  $('#stamp').innerHTML = PupIcons.icon('paw', 'pink', { shape: 'none' });
  render();
})();
