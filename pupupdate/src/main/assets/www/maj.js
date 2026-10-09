/* PupUpdate autonome — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const ic = (g, c, sh) => PupIcons.icon(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const mockR = { pp: { cur: 25, latest: 26, url: 'x', size: 3100000, installed: true, silent: false }, self: { cur: 1, latest: 1 }, notes: [{ v: 26, date: '2026-10-09T01:00:00Z', body: 'Fonds animés OLED : Pup dans l\'espace et Aurore des pattes\n- Sieste vraiment tamisée' }] };
  const U = window.Upd || { info: () => JSON.stringify({ last: mockR, lastCheck: Date.now() - 6e5, auto: true, notify: true, retour: true, ppCur: 25, selfCur: 1, android12: true }), check() { setTimeout(() => UpdUI.on('checked', JSON.stringify(mockR)), 600); }, set() {}, update() { let d = 0; const t = setInterval(() => { d += 400000; UpdUI.on('progress', JSON.stringify({ d: Math.min(d, 3100000), t: 3100000 })); if (d >= 3100000) { clearInterval(t); UpdUI.on('status', '{"st":"done"}'); } }, 120); }, cancel() {}, openPuppy() {}, openPage() {}, close() {} };
  const uc = (fn, ...a) => { try { return U[fn] ? U[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const fmtSize = (b) => (b / 1048576).toFixed(1).replace('.', ',') + ' Mo';
  const fmtD = (t) => t ? new Date(t).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : 'jamais';
  const snd = (n) => { try { window.PupSons && PupSons.play(n); } catch (e) { } };

  function pup(mood) {
    const eyes = mood === 'sad' ? '<path d="M35 36q7-5 13-1M52 35q6-4 13 1" stroke="#3a1a0c" stroke-width="2.6" fill="none" stroke-linecap="round"/><ellipse cx="42" cy="44" rx="5.5" ry="6.5" fill="#1e0e36"/><ellipse cx="58" cy="44" rx="5.5" ry="6.5" fill="#1e0e36"/>'
      : mood === 'busy' ? '<path d="M36 43q6-5 12 0M52 43q6-5 12 0" stroke="#1e0e36" stroke-width="3" fill="none" stroke-linecap="round"/>'
        : '<ellipse cx="42" cy="42" rx="6.2" ry="7.4" fill="#1e0e36"/><ellipse cx="58" cy="42" rx="6.2" ry="7.4" fill="#1e0e36"/><circle cx="44" cy="39" r="2.6" fill="#fff"/><circle cx="60" cy="39" r="2.6" fill="#fff"/>';
    const mouth = mood === 'sad' ? '<path d="M43 63q7-5 14 0" stroke="#3a1a0c" stroke-width="2.4" fill="none" stroke-linecap="round"/>'
      : '<path d="M50 56v4M50 60q-5 6-10 1.5M50 60q5 6 10 1.5" stroke="#3a1a0c" stroke-width="2.3" fill="none" stroke-linecap="round"/><path d="M46.5 63q3.5 11 7 0z" fill="#ff5e9e"/>';
    const bone = mood === 'new' || mood === 'done' ? '<g transform="translate(70 64) rotate(-25)"><rect x="-12" y="-3.5" width="24" height="7" rx="3.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="-12" cy="-3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="-12" cy="3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="12" cy="-3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/><circle cx="12" cy="3.5" r="4.5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.2"/></g>' : '';
    return `<svg class="face" viewBox="0 0 100 80"><defs><radialGradient id="uf" cx=".38" cy=".3" r=".85"><stop offset="0" stop-color="#fff6e8"/><stop offset=".45" stop-color="#f6cf9a"/><stop offset=".8" stop-color="#d9934f"/><stop offset="1" stop-color="#9c5a24"/></radialGradient><radialGradient id="ud" cx=".4" cy=".25" r=".9"><stop offset="0" stop-color="#d98c4a"/><stop offset="1" stop-color="#5a2c0c"/></radialGradient></defs>
      <path d="M30 22C14 20 6 40 11 58c3 11 15 11 18 2l6-26z" fill="url(#ud)"/><path d="M70 22c16-2 24 18 19 36-3 11-15 11-18 2l-6-26z" fill="url(#ud)"/>
      <ellipse cx="50" cy="45" rx="29" ry="26" fill="url(#uf)"/><ellipse cx="50" cy="57" rx="16" ry="12" fill="#fff6ea"/>${eyes}<ellipse cx="50" cy="53.5" rx="6.2" ry="4.6" fill="#1a0b2e"/><ellipse cx="48" cy="51.8" rx="2.4" ry="1.2" fill="#fff" opacity=".7"/>${mouth}
      <ellipse cx="34" cy="53" rx="5" ry="3" fill="#ff7ab8" opacity=".5"/><ellipse cx="66" cy="53" rx="5" ry="3" fill="#ff7ab8" opacity=".5"/>${bone}</svg>`;
  }

  let S = J(uc('info'), {}) || {}, R = S.last || {}, state = S.busy ? 'downloading' : 'idle', which = 'pp', prog = { d: 0, t: 0 }, err = '', checking = false;
  const row = (k, t, sub) => `<div class="row"><span>${t}<small>${sub}</small></span><button class="sw${S[k] !== false ? ' on' : ''}" data-sw="${k}" type="button"><i></i></button></div>`;

  function render() {
    const pp = R.pp || { cur: S.ppCur || 0, installed: (S.ppCur || 0) > 0 }, self = R.self || { cur: S.selfCur || 1 };
    const cur = pp.cur || S.ppCur || 0, latest = pp.latest || 0, hasNew = latest > cur, selfNew = (self.latest || 0) > (self.cur || S.selfCur || 0);
    let mood = hasNew ? 'new' : 'happy', title, sub;
    const busyHere = state === 'downloading' || state === 'installing' || state === 'confirm';
    if (checking) { mood = 'busy'; title = 'Je renifle GitHub…'; sub = 'Recherche de nouvelles versions'; }
    else if (state === 'downloading') { mood = 'busy'; title = which === 'self' ? 'Je rapporte la nouvelle PupUpdate…' : 'Je rapporte la nouvelle version…'; sub = 'Téléchargement en cours'; }
    else if (state === 'installing') { mood = 'busy'; title = 'Installation…'; sub = pp.silent ? 'Mise à jour express, sans confirmation ⚡' : 'Android va te demander de confirmer « Mettre à jour ».'; }
    else if (state === 'confirm') { mood = 'busy'; title = 'Presque fini !'; sub = 'Confirme « Mettre à jour » : tes réglages sont conservés.'; }
    else if (state === 'perm') { mood = 'sad'; title = 'Autorisation nécessaire'; sub = 'Active « Autoriser depuis cette source » pour PupUpdate, puis reviens : l\'installation reprend toute seule.'; }
    else if (state === 'error') { mood = 'sad'; title = 'Oups…'; sub = err || 'Quelque chose a raté.'; }
    else if (state === 'done') { mood = 'done'; title = 'PuppyPhone est à jour ! 🎉'; sub = S.retour !== false ? 'Je te ramène sur l\'accueil PuppyPhone…' : 'Tu peux revenir sur l\'accueil.'; }
    else if (!pp.installed) { mood = 'sad'; title = 'PuppyPhone n\'est pas installé'; sub = latest ? 'Je peux l\'installer pour toi.' : 'Vérifie GitHub pour le récupérer.'; }
    else if (hasNew) { title = 'Nouvelle version disponible ! 🦴'; sub = `v1.0.${latest}${pp.size ? ' · ' + fmtSize(pp.size) : ''}`; }
    else { title = 'La niche est à jour ✓'; sub = `Dernière vérification : ${fmtD(S.lastCheck)}`; }
    const notes = (R.notes || []).filter((n) => n.v > cur).sort((a, b) => b.v - a.v);
    const pct = prog.t ? Math.round(prog.d / prog.t * 100) : 0;
    $('#ubody').innerHTML = `
      <section class="ucard">
        <h2>${ic('paw', 'pink')}PuppyPhone<em>${pp.installed ? 'v1.0.' + cur : 'absent'}</em></h2>
        <div class="vers"><div class="vbox" style="--c:#29e6ff"><small>Ta version</small><b>${pp.installed ? 'v1.0.' + cur : '—'}</b></div><span class="varrow">➜</span><div class="vbox" style="--c:${hasNew ? '#3dffb0' : '#fff'}"><small>Sur GitHub</small><b>${latest ? 'v1.0.' + latest : '—'}</b></div></div>
        <div class="pup">${pup(mood)}<div class="msg"><b>${esc(title)}</b><span>${esc(sub)}</span></div></div>
        ${state === 'downloading' ? `<div class="pbar"><i style="width:${pct}%"></i><span>${pct} % · ${fmtSize(prog.d)} / ${fmtSize(prog.t || 0)}</span></div><button class="ab wide red" data-a="cancel" type="button">Annuler</button>` : ''}
        ${(hasNew || (!pp.installed && latest)) && !busyHere && state !== 'done' ? `<button class="ab wide green" data-a="update" type="button">${ic('download', 'chrome', 'none')}${pp.installed ? 'Télécharger et installer' : 'Installer PuppyPhone'}</button>` : ''}
        ${pp.installed ? (pp.silent ? `<div class="express"><span class="bolt">⚡</span><span><b>Mises à jour express activées</b> : PupUpdate installe PuppyPhone sans te demander de confirmer, puis te ramène sur l'accueil.</span></div>`
          : S.android12 ? `<div class="express wait"><span class="bolt">🐾</span><span>La <b>première</b> mise à jour faite par PupUpdate demande encore ta confirmation. Ensuite, Android le reconnaît comme gardien de PuppyPhone et les suivantes se font <b>sans confirmation</b>.</span></div>` : '') : ''}
        <div class="urow"><button class="ab small c2" data-a="check" type="button" ${checking ? 'disabled' : ''}>${ic('refresh', 'chrome', 'none')}Vérifier</button>${pp.installed ? `<button class="ab small violet" data-a="puppy" type="button">${ic('home', 'chrome', 'none')}Ouvrir PuppyPhone</button>` : ''}<button class="ab small glass" data-a="page" type="button">${ic('external', 'chrome', 'none')}GitHub</button></div>
      </section>
      ${selfNew ? `<section class="ucard"><h2>${ic('download', 'green')}Nouvelle PupUpdate<em>v1.${self.latest}</em></h2><p style="margin:0;font-size:13px;color:#eadcf7">Le chien de garde a aussi une nouvelle version. PupUpdate se fermera le temps de s'installer, rouvre-le ensuite.</p><button class="ab wide green" data-a="self" type="button" ${busyHere ? 'disabled' : ''}>${ic('download', 'chrome', 'none')}Mettre à jour PupUpdate</button></section>` : ''}
      ${notes.length ? `<section class="ucard notes"><h3>Nouveautés à venir 🐾</h3>${notes.map((n) => { const body = String(n.body || '').split('\n---')[0].trim(); const lines = body.split('\n'); const t = lines.shift() || ''; return `<div class="note"><div class="nv"><span>v1.0.${n.v}</span><span>${n.date ? new Date(n.date).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short' }) : ''}</span></div><b>${esc(t)}</b>${lines.join('\n').trim() ? `<p>${esc(lines.join('\n').trim())}</p>` : ''}</div>`; }).join('')}</section>` : ''}
      <section class="ucard">
        <div class="sep"><span class="apps">${ic('download', 'green')}<i>⇄</i>${ic('paw', 'pink')}</span><span>PupUpdate est une <b>appli à part</b> : quand Android remplace PuppyPhone, c'est PuppyPhone qui redémarre, pas PupUpdate. Il peut donc te ramener tout seul sur l'accueil.</span></div>
        ${row('retour', 'Me ramener sur PuppyPhone', 'Rouvre l\'accueil PuppyPhone dès que la mise à jour est finie')}
        ${row('auto', 'Vérifier tout seul', 'Toutes les 6 h environ, quand le réseau est là')}
        ${row('notify', 'Me prévenir par notification', '« 🦴 Nouvelle version de PuppyPhone »')}
        <p style="margin:0;font-size:12.5px;color:var(--muted)">🔒 Android vérifie toujours que chaque nouvelle version est signée avec la même clé que celle installée. Tes réglages, playlists et autorisations sont conservés.</p>
      </section>`;
  }

  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-a],[data-sw]'); if (!b) return;
    const a = b.dataset.a, pp = R.pp || {}, self = R.self || {};
    if (a === 'check') { checking = true; render(); uc('check'); }
    if (a === 'update') { which = 'pp'; state = 'downloading'; err = ''; prog = { d: 0, t: pp.size || 0 }; render(); uc('update', 'pp', pp.url, pp.size || 0); }
    if (a === 'self') { which = 'self'; state = 'downloading'; err = ''; prog = { d: 0, t: self.size || 0 }; render(); uc('update', 'self', self.url, self.size || 0); }
    if (a === 'cancel') { uc('cancel'); state = 'idle'; render(); }
    if (a === 'puppy') uc('openPuppy');
    if (a === 'page') uc('openPage');
    if (b.dataset.sw) { const on = !b.classList.contains('on'); S[b.dataset.sw] = on; uc('set', b.dataset.sw, on); render(); }
  });

  window.UpdUI = {
    on(ev, data) {
      if (ev === 'checked') { checking = false; const r = J(data, {}) || {}; if (r.err) { state = 'error'; err = r.err; } else { if (state === 'error') state = 'idle'; R = r; } S = J(uc('info'), S) || S; if (!r.err) R = r; render(); }
      else if (ev === 'progress') { prog = J(data, prog); state = 'downloading'; render(); }
      else if (ev === 'status') {
        const o = J(data, {}) || {};
        if (o.st === 'done') { state = 'done'; snd('wouf2'); }
        else if (o.st === 'selfdone') state = 'idle';
        else if (o.st === 'error' && /Annulé/.test(o.msg || '')) state = 'idle';
        else { state = o.st; if (o.st === 'error') snd('couine'); }
        err = o.msg || ''; render();
      }
      else if (ev === 'resume') { S = J(uc('info'), S) || S; if (S.last && S.last.pp) R = S.last; if (state === 'done') state = 'idle'; render(); }
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };
  $('#ulogo').innerHTML = ic('download', 'green');
  render();
  checking = true; render(); uc('check');
})();
