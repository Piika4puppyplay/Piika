/* PupUpdate — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const I = PupIcons.icon;
  const ic = (g, c, sh) => I(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const U = window.Upd || { info: () => JSON.stringify({ current: 20, latest: 22, auto: true, notify: true, lastCheck: Date.now() - 3e5, url: 'x', size: 2950000, notes: [{ v: 22, date: '2026-10-09T00:30:00Z', body: "PuppyInternet : éditeur des chiots + dictionnaire puppy personnalisable\n\n---\nTélécharge" }, { v: 21, date: '2026-10-09T00:05:00Z', body: 'PupDeco : chiots redessinés en skeuomorphique' }] }), check() { setTimeout(() => UpdUI.on('checked', U.info()), 600); }, set() {}, update() { let d = 0; const t = setInterval(() => { d += 300000; UpdUI.on('progress', JSON.stringify({ d: Math.min(d, 2950000), t: 2950000 })); if (d >= 2950000) { clearInterval(t); UpdUI.on('status', '{"st":"confirm"}'); } }, 120); }, cancel() {}, openPage() {}, close() {} };
  const uc = (fn, ...a) => { try { return U[fn] ? U[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const fmtSize = (b) => (b / 1048576).toFixed(1).replace('.', ',') + ' Mo';
  const fmtD = (t) => t ? new Date(t).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : 'jamais';

  // chiot (original) : content, qui attend, qui travaille, triste
  function pup(mood) {
    const eyes = mood === 'sad' ? '<path d="M35 36q7-5 13-1M52 35q6-4 13 1" stroke="#3a1a0c" stroke-width="2.6" fill="none" stroke-linecap="round"/><ellipse cx="42" cy="44" rx="5.5" ry="6.5" fill="#1e0e36"/><ellipse cx="58" cy="44" rx="5.5" ry="6.5" fill="#1e0e36"/>'
      : mood === 'busy' ? '<path d="M36 43q6-5 12 0M52 43q6-5 12 0" stroke="#1e0e36" stroke-width="3" fill="none" stroke-linecap="round"/>'
        : '<ellipse cx="42" cy="42" rx="6.2" ry="7.4" fill="#1e0e36"/><ellipse cx="58" cy="42" rx="6.2" ry="7.4" fill="#1e0e36"/><circle cx="44" cy="39" r="2.6" fill="#fff"/><circle cx="60" cy="39" r="2.6" fill="#fff"/>';
    const mouth = mood === 'sad' ? '<path d="M43 63q7-5 14 0" stroke="#3a1a0c" stroke-width="2.4" fill="none" stroke-linecap="round"/>'
      : '<path d="M50 56v4M50 60q-5 6-10 1.5M50 60q5 6 10 1.5" stroke="#3a1a0c" stroke-width="2.3" fill="none" stroke-linecap="round"/><path d="M46.5 63q3.5 11 7 0z" fill="#ff5e9e"/>';
    const extra = mood === 'new' ? '<g transform="translate(70 64) rotate(-25)"><rect x="-12" y="-3.5" width="24" height="7" rx="3.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="-12" cy="-3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="-12" cy="3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="12" cy="-3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="12" cy="3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/></g>' : '';
    return `<svg class="face" viewBox="0 0 100 80"><defs><radialGradient id="uf" cx=".38" cy=".3" r=".85"><stop offset="0" stop-color="#fff6e8"/><stop offset=".45" stop-color="#f6cf9a"/><stop offset=".8" stop-color="#d9934f"/><stop offset="1" stop-color="#9c5a24"/></radialGradient><radialGradient id="ud" cx=".4" cy=".25" r=".9"><stop offset="0" stop-color="#d98c4a"/><stop offset="1" stop-color="#5a2c0c"/></radialGradient></defs>
      <path d="M30 22C14 20 6 40 11 58c3 11 15 11 18 2l6-26z" fill="url(#ud)"/><path d="M70 22c16-2 24 18 19 36-3 11-15 11-18 2l-6-26z" fill="url(#ud)"/>
      <ellipse cx="50" cy="45" rx="29" ry="26" fill="url(#uf)"/><ellipse cx="50" cy="57" rx="16" ry="12" fill="#fff6ea"/>${eyes}<ellipse cx="50" cy="53.5" rx="6.2" ry="4.6" fill="#1a0b2e"/><ellipse cx="48" cy="51.8" rx="2.4" ry="1.2" fill="#fff" opacity=".7"/>${mouth}
      <ellipse cx="34" cy="53" rx="5" ry="3" fill="#ff7ab8" opacity=".5"/><ellipse cx="66" cy="53" rx="5" ry="3" fill="#ff7ab8" opacity=".5"/>${extra}</svg>`;
  }

  let S = J(uc('info'), {}) || {}, state = S.busy ? 'downloading' : 'idle', prog = { d: 0, t: 0 }, err = '', checking = false;
  function render() {
    const cur = S.current || 0, latest = Math.max(S.latest || 0, 0), hasNew = latest > cur;
    let mood = hasNew ? 'new' : 'happy', title, sub;
    if (checking) { mood = 'busy'; title = 'Je renifle GitHub…'; sub = 'Recherche de nouvelles versions'; }
    else if (state === 'downloading') { mood = 'busy'; title = 'Je rapporte la nouvelle version…'; sub = 'Téléchargement en cours'; }
    else if (state === 'installing' || state === 'confirm') { mood = 'busy'; title = 'Presque fini !'; sub = 'Android te demande de confirmer « Mettre à jour ». Tes réglages sont conservés.'; }
    else if (state === 'perm') { mood = 'sad'; title = 'Autorisation nécessaire'; sub = 'Active « Autoriser depuis cette source » pour PuppyPhone, puis reviens ici : l\'installation reprend toute seule.'; }
    else if (state === 'error') { mood = 'sad'; title = 'Oups…'; sub = err || 'Quelque chose a raté.'; }
    else if (hasNew) { title = 'Nouvelle version disponible ! 🦴'; sub = `v1.0.${latest}${S.size ? ' · ' + fmtSize(S.size) : ''}`; }
    else { title = 'La niche est à jour ✓'; sub = `Dernière vérification : ${fmtD(S.lastCheck)}`; }
    const notes = (S.notes || []).filter((n) => n.v > cur).sort((a, b) => b.v - a.v);
    const pct = prog.t ? Math.round(prog.d / prog.t * 100) : 0;
    $('#ubody').innerHTML = `
      <section class="ucard">
        <div class="vers"><div class="vbox" style="--c:#29e6ff"><small>Ta version</small><b>v1.0.${cur}</b></div><span class="varrow">➜</span><div class="vbox" style="--c:${hasNew ? '#3dffb0' : '#fff'}"><small>Sur GitHub</small><b>${latest ? 'v1.0.' + latest : '—'}</b></div></div>
        <div class="pup">${pup(mood)}<div class="msg"><b>${esc(title)}</b><span>${esc(sub)}</span></div></div>
        ${state === 'downloading' ? `<div class="pbar"><i style="width:${pct}%"></i><span>${pct} % · ${fmtSize(prog.d)} / ${fmtSize(prog.t || S.size || 0)}</span></div><button class="ab wide red" data-a="cancel" type="button">Annuler</button>` : ''}
        ${hasNew && (state === 'idle' || state === 'error' || state === 'perm') ? `<button class="ab wide green" data-a="update" type="button">${ic('download', 'chrome', 'none')}Télécharger et installer</button>` : ''}
        <div class="urow"><button class="ab small c2" data-a="check" type="button" ${checking ? 'disabled' : ''}>${ic('refresh', 'chrome', 'none')}Vérifier</button><button class="ab small glass" data-a="page" type="button">${ic('external', 'chrome', 'none')}Page GitHub</button></div>
      </section>
      ${!S.standalone ? `<section class="ucard reco"><div class="pup">${pup('new')}<div class="msg"><b>Nouveau : l'appli PupUpdate séparée 🦴</b><span>Ici, Android ferme PuppyPhone pendant sa mise à jour et te renvoie sur One UI. L'appli <b>PupUpdate séparée</b> installe PuppyPhone de l'extérieur, puis <b>te ramène toute seule sur l'accueil</b>. Sur Android 12 et plus, après la première fois, les mises à jour se font même <b>sans confirmation</b>.</span></div></div>
        ${S.updUrl ? `<button class="ab wide green" data-a="standalone" type="button" ${state === 'downloading' ? 'disabled' : ''}>${ic('download', 'chrome', 'none')}Installer l'appli PupUpdate</button>` : `<p style="margin:0;font-size:12.5px;color:var(--muted)">Touche « Vérifier » pour la trouver sur GitHub.</p>`}</section>` : ''}
      ${notes.length ? `<section class="ucard notes"><h3>Nouveautés à venir 🐾</h3>${notes.map((n) => { const body = String(n.body || '').split('\n---')[0].trim(); const lines = body.split('\n'); const t = lines.shift() || ''; return `<div class="note"><div class="nv"><span>v1.0.${n.v}</span><span>${n.date ? new Date(n.date).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short' }) : ''}</span></div><b>${esc(t.startsWith('Télécharge') ? 'Nouvelle version' : t)}</b>${lines.join('\n').trim() ? `<p>${esc(lines.join('\n').trim())}</p>` : ''}</div>`; }).join('')}</section>` : ''}
      <section class="ucard">
        <div class="row"><span>Vérifier tout seul<small>Toutes les 6 h quand l'écran d'accueil s'ouvre</small></span><button class="sw${S.auto !== false ? ' on' : ''}" data-sw="auto" type="button"><i></i></button></div>
        <div class="row"><span>Me prévenir par notification<small>« 🦴 Nouvelle version de PuppyPhone »</small></span><button class="sw${S.notify !== false ? ' on' : ''}" data-sw="notify" type="button"><i></i></button></div>
        <p style="margin:0;font-size:12.5px;color:var(--muted)">🔒 L'installation passe toujours par la confirmation d'Android, qui vérifie que la nouvelle version est bien signée avec la même clé que la tienne. Tes réglages, playlists et autorisations sont conservés.</p>
      </section>`;
  }

  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-a],[data-sw]'); if (!b) return;
    const a = b.dataset.a;
    if (a === 'check') { checking = true; render(); uc('check'); }
    if (a === 'update') { state = 'downloading'; err = ''; prog = { d: 0, t: S.size || 0 }; render(); uc('update', S.url, S.size || 0); }
    if (a === 'cancel') { uc('cancel'); state = 'idle'; render(); }
    if (a === 'standalone') { state = 'downloading'; err = ''; prog = { d: 0, t: S.updSize || 0 }; render(); uc('installStandalone', S.updUrl, S.updSize || 0); }
    if (a === 'page') uc('openPage');
    if (b.dataset.sw) { const on = !b.classList.contains('on'); S[b.dataset.sw] = on; uc('set', b.dataset.sw, on); render(); }
  });

  window.UpdUI = {
    on(ev, data) {
      if (ev === 'checked') { checking = false; const r = J(data, {}) || {}; if (r.err) { state = 'error'; err = r.err; } else if (state === 'error') state = 'idle'; S = J(uc('info'), S) || S; render(); }
      else if (ev === 'progress') { prog = J(data, prog); state = 'downloading'; render(); }
      else if (ev === 'status') { const o = J(data, {}) || {}; if (o.st === 'standalone') { S.standalone = true; state = 'idle'; render(); return; } state = o.st === 'done' ? 'idle' : o.st === 'error' && /Annulé/.test(o.msg || '') ? 'idle' : o.st; err = o.msg || ''; render(); }
      else if (ev === 'resume') { S = J(uc('info'), S) || S; render(); }
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };
  $('#ulogo').innerHTML = ic('download', 'green');
  render();
  checking = true; render(); uc('check');
})();
