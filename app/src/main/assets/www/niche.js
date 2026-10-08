/* Ta niche — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const I = PupIcons.icon;
  const ic = (g, c, sh) => I(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const PEL = [['auto', 'Chacun son pelage', '#ff3fa4', '#29e6ff', '#0b0614'], ['rose', 'Néon Rose', '#ff3fa4', '#29e6ff', '#0b0614'], ['cyan', 'Laser Turquoise', '#29e6ff', '#ff3fa4', '#030b12'], ['violet', 'Violet Velours', '#9b5cff', '#29e6ff', '#0b0418'], ['cuir', 'Cuir & Rivets', '#ff3fa4', '#29e6ff', '#120a10'], ['or', 'Or Royal', '#ffb627', '#ff3fa4', '#0e0802'], ['vert', 'Vert Fluo', '#3dffb0', '#9b5cff', '#020e0a']]
    .map(([id, nom, acc, acc2, bg]) => ({ id, nom, acc, acc2, bg }));
  const N = window.Niche || {
    info: () => JSON.stringify({ pelage: 'rose', pelages: PEL, lastBackup: Date.now() - 864e5 * 3, lastBackupName: 'niche_2026-10-06_21h14.puppy', live: false, siesteBright: false, version: 24 }),
    pelage() {}, backup() { const st = ['Réglages des Pup-apps…', 'Fichiers de la niche…', 'Mémoire des pages (playlists, favoris…)…', 'Rangement dans Téléchargements…']; st.forEach((m, i) => setTimeout(() => NicheUI.on('backup', JSON.stringify({ st: 'step', msg: m })), 500 * (i + 1))); setTimeout(() => NicheUI.on('backup', JSON.stringify({ st: 'done', name: 'niche_2026-10-09_02h10.puppy' })), 2600); },
    pickRestore() { setTimeout(() => NicheUI.on('restore', JSON.stringify({ st: 'ready', man: { date: Date.now() - 864e5, device: 'samsung SM-G986B', version: 23 } })), 500); }, cancelRestore() {}, restoreNow() {}, wallpaper() {}, siestePreview() {}, dreamSettings() {}, set() {}, close() {},
  };
  const nc = (fn, ...a) => { try { return N[fn] ? N[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const fmtD = (t) => t ? new Date(t).toLocaleString('fr-FR', { weekday: 'short', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }) : 'jamais';
  const ago = (t) => { if (!t) return ''; const d = (Date.now() - t) / 864e5; return d < 1 ? "aujourd'hui" : d < 2 ? 'hier' : `il y a ${Math.floor(d)} jours`; };
  const PAW = '<svg viewBox="0 0 24 24"><ellipse cx="12" cy="16" rx="5.4" ry="4.6"/><circle cx="5.6" cy="10.4" r="2.3"/><circle cx="9.4" cy="6.4" r="2.3"/><circle cx="14.6" cy="6.4" r="2.3"/><circle cx="18.4" cy="10.4" r="2.3"/></svg>';

  let S = J(nc('info'), {}) || {};
  let bk = { st: 'idle', steps: [], name: '' };

  function toast(msg) {
    let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 3200);
  }

  // terrier avec os, pelle et monticule de terre (dessin original)
  const DIG = `<svg viewBox="0 0 320 150" preserveAspectRatio="xMidYMax meet"><defs>
      <radialGradient id="dg" cx=".5" cy=".2" r=".9"><stop offset="0" stop-color="#8a5a34"/><stop offset=".6" stop-color="#4a2a14"/><stop offset="1" stop-color="#1e0e06"/></radialGradient>
      <linearGradient id="bn" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff"/><stop offset=".6" stop-color="#f3e6d6"/><stop offset="1" stop-color="#c9b39a"/></linearGradient>
      <linearGradient id="mt" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#6b6480"/><stop offset=".4" stop-color="#fbf9ff"/><stop offset="1" stop-color="#7a7290"/></linearGradient></defs>
    <ellipse cx="160" cy="150" rx="190" ry="30" fill="#0a0410"/>
    <path d="M60 150Q90 92 160 88T262 150z" fill="url(#dg)"/>
    <path d="M60 150Q90 92 160 88T262 150" fill="none" stroke="rgba(255,200,150,.35)" stroke-width="2"/>
    <g fill="#2a160a"><circle cx="110" cy="128" r="4"/><circle cx="150" cy="112" r="3"/><circle cx="200" cy="124" r="5"/><circle cx="178" cy="138" r="3"/><circle cx="128" cy="140" r="3"/></g>
    <g class="bone"><g transform="translate(160 104)"><rect x="-30" y="-7" width="60" height="14" rx="7" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/>
      <circle cx="-30" cy="-7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/><circle cx="-30" cy="7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/>
      <circle cx="30" cy="-7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/><circle cx="30" cy="7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/>
      <rect x="-28" y="-3" width="56" height="3" rx="1.5" fill="#fff" opacity=".8"/></g></g>
    <path d="M60 150Q100 118 160 116T262 150z" fill="url(#dg)" opacity=".92"/>
    <g class="shovel"><rect x="246" y="10" width="7" height="70" rx="3" fill="#a0632c" stroke="#3a1a08"/><rect x="238" y="4" width="23" height="8" rx="4" fill="#5c3412" stroke="#2a1206"/>
      <path d="M236 80h27l-3 26q-10 10-21 0z" fill="url(#mt)" stroke="#2a2438" stroke-width="1.5"/></g>
    <g class="dirtfly" fill="#6a4020"><circle cx="230" cy="100" r="4"/><circle cx="222" cy="92" r="3"/><circle cx="238" cy="94" r="2.5"/></g>
    <g transform="translate(26 70)"><ellipse cx="22" cy="58" rx="22" ry="5" fill="#000" opacity=".5"/>
      <path d="M8 10C0 10-3 26 2 36c3 6 10 5 11-1l3-18z" fill="#9c5a24"/><path d="M36 10c8 0 11 16 6 26-3 6-10 5-11-1l-3-18z" fill="#9c5a24"/>
      <ellipse cx="22" cy="26" rx="16" ry="15" fill="#f6cf9a"/><ellipse cx="22" cy="33" rx="9" ry="7" fill="#fff6ea"/>
      <ellipse cx="16" cy="24" rx="3" ry="3.6" fill="#1e0e36"/><ellipse cx="28" cy="24" rx="3" ry="3.6" fill="#1e0e36"/><circle cx="17" cy="22.6" r="1.2" fill="#fff"/><circle cx="29" cy="22.6" r="1.2" fill="#fff"/>
      <ellipse cx="22" cy="30.5" rx="3.6" ry="2.6" fill="#1a0b2e"/><path d="M19.5 34.5q2.5 6 5 0z" fill="#ff5e9e"/>
      <path d="M8 42q14 6 28 0l2 14H6z" fill="#f6cf9a"/><ellipse cx="12" cy="56" rx="6" ry="3.6" fill="#fff6ea"/><ellipse cx="32" cy="56" rx="6" ry="3.6" fill="#fff6ea"/>
      <path d="M6 41q16 7 32 0" stroke="${'#ff3fa4'}" stroke-width="4" fill="none"/><circle cx="22" cy="46" r="3" fill="#ffd34d" stroke="#8a5a00"/></g>
  </svg>`;

  function render() {
    const cur = S.pelage || 'auto', pels = S.pelages && S.pelages.length ? S.pelages : PEL;
    const curP = pels.find((p) => p.id === cur) || pels[0];
    const working = bk.st === 'work';
    if (!$('#s-pel')) init();
    $('#s-pel').innerHTML = `
      <section class="ncard">
        <h2>${ic('paw', 'pink')}Le pelage de la niche<em>${esc(curP.nom)}</em></h2>
        <p class="hint">Une seule couleur pour <b>toutes les Pup-apps</b>, le clavier, la barre de navigation, l'habillage des sites, le fond animé et la sieste. Choisis la médaille que ton chiot portera à son collier.</p>
        <div class="collar"></div>
        <div class="medals">${pels.map((p, i) => `<button class="medal${p.id === cur ? ' on' : ''}${p.id === 'auto' ? ' auto' : ''}" data-pel="${esc(p.id)}" type="button" style="--a:${p.acc};--b:${p.acc2};--dl:${(i * -0.6).toFixed(1)}s"><i class="ring"></i><span class="disc">${PAW}</span><b>${esc(p.nom)}</b></button>`).join('')}</div>
      </section>`;
    { const md = $('.medals'), on = $('.medal.on'); if (md && on) md.scrollLeft = on.offsetLeft - (md.clientWidth - on.clientWidth) / 2; }
    $('#s-bone').innerHTML = `      <section class="ncard">
        <h2>${ic('bone', 'amber')}La cachette à os<em>Sauvegarde</em></h2>
        <div class="dig${working ? ' work' : ''}${bk.st === 'done' ? ' down' : ''}" id="dig">${DIG}</div>
        <p class="hint">Enterre un os pour mettre <b>toute la niche à l'abri</b> : réglages des Pup-apps, clavier, barre, pelage, playlists, favoris, habillage des sites et chien de garde. Le fichier <b>.puppy</b> atterrit dans <b>Téléchargements › PupNiche</b>, garde-le au chaud sur ta carte SD ou ton ordi. Tes mots de passe et connexions aux sites ne sont jamais emportés.</p>
        ${working || bk.steps.length ? `<ul class="steps">${bk.steps.map((s, i) => `<li class="${i < bk.steps.length - 1 || bk.st !== 'work' ? 'done' : ''}">${esc(s)}</li>`).join('')}</ul>` : ''}
        <div class="lastbone">${ic('bone', bk.st === 'done' ? 'green' : 'chrome')}<div><b>${bk.st === 'done' ? 'Os enterré ! Bon chien 🐶' : S.lastBackup ? 'Dernier os enterré ' + ago(S.lastBackup) : "Aucun os enterré pour l'instant"}</b><small>${esc(bk.st === 'done' ? bk.name : S.lastBackup ? fmtD(S.lastBackup) + ' · ' + (S.lastBackupName || '') : 'Pense à en enterrer un avant de changer de téléphone 🐾')}</small></div></div>
        ${S.restoredAt ? `<p class="hint">🦴 Dernier os déterré : ${esc(fmtD(S.restoredAt))}</p>` : ''}
        <div class="nrow"><button class="ab green" data-a="backup" type="button" ${working ? 'disabled' : ''}>${ic('download', 'chrome', 'none')}${working ? 'Je creuse…' : 'Enterrer un os'}</button><button class="ab amber" data-a="restore" type="button" ${working ? 'disabled' : ''}>${ic('upload', 'chrome', 'none')}Déterrer un os</button></div>
      </section>`;
    $('#wl-st').textContent = S.live ? 'Posé ✓' : 'Pas posé';
    $('#wl-bt').innerHTML = ic('sparkle', 'chrome', 'none') + (S.live ? 'Reposer le fond animé' : "Poser sur l'écran d'accueil");
    $('#sb-sw').classList.toggle('on', !!S.siesteBright);
  }
  function init() {
    $('#nbody').innerHTML = '<div id="s-pel"></div><div id="s-bone"></div>' + `      <section class="ncard">
        <h2>${ic('moon', 'violet')}Fond animé « Nuit néon »<em id="wl-st"></em></h2>
        <div class="wallprev"><canvas id="wcv"></canvas><span class="phone">APERÇU EN DIRECT</span></div>
        <p class="hint">Des pattes lumineuses qui montent dans un ciel étoilé, des faisceaux qui balaient et un sol néon rétro, <b>aux couleurs de ton pelage</b>. Il bouge doucement quand tu changes de page et se met en pause écran éteint pour ménager la batterie.</p>
        <button class="ab wide violet" data-a="wall" type="button" id="wl-bt"></button>
      </section>

      <section class="ncard">
        <h2>${ic('battery', 'green')}La sieste du chiot<em>Charge</em></h2>
        <div class="siestprev"><iframe src="sieste.html?apercu=1" tabindex="-1" title="Aperçu de la sieste"></iframe></div>
        <p class="hint">Pendant la charge, ton chiot dort sur son coussin pendant que <b>l'horloge s'allume dans une batterie en verre</b> : rouge quand la gamelle est vide, elle vire au <b>vert</b> en se remplissant jusqu'à 100 %, puis le chiot se réveille avec son os. La scène glisse doucement toute seule pour ne jamais marquer l'écran.</p>
        <ol class="howto"><li>Touche <b>Activer l'écran de veille</b> ci-dessous.</li><li>Choisis <b>« La sieste du chiot »</b> comme économiseur d'écran.</li><li>Règle « Quand démarrer » sur <b>Pendant la charge</b>.</li></ol>
        <p class="hint">🔒 L'écran de verrouillage de One UI reste intact : la sieste s'affiche par-dessus et disparaît dès que tu touches l'écran.</p>
        <div class="row"><span>Écran lumineux pendant la sieste<small>Éteint par défaut : lumière tamisée, plus doux la nuit et pour l'écran</small></span><button class="sw" id="sb-sw" data-sw="siesteBright" type="button"><i></i></button></div>
        <div class="nrow"><button class="ab c2" data-a="sieste" type="button">${ic('eye', 'chrome', 'none')}Voir la sieste</button><button class="ab green" data-a="dream" type="button">${ic('gear', 'chrome', 'none')}Activer l'écran de veille</button></div>
      </section>`;
    startWall();
  }

  function veil(html) { const v = $('#veil'); if (!html) { v.classList.add('hidden'); v.innerHTML = ''; return; } v.innerHTML = `<div class="nwin">${html}</div>`; v.classList.remove('hidden'); }

  document.addEventListener('click', (e) => {
    const m = e.target.closest('[data-pel]');
    if (m) {
      const id = m.dataset.pel; if (id === S.pelage) return;
      S.pelage = id; render(); startWall(); toast('Nouveau pelage ! Toute la niche se rhabille 🐾'); setTimeout(() => nc('pelage', id), 350); return;
    }
    const b = e.target.closest('[data-a],[data-sw]'); if (!b) return;
    const a = b.dataset.a;
    if (a === 'backup') { bk = { st: 'work', steps: [], name: '' }; render(); nc('backup'); }
    if (a === 'restore') nc('pickRestore');
    if (a === 'wall') nc('wallpaper');
    if (a === 'sieste') nc('siestePreview');
    if (a === 'dream') nc('dreamSettings');
    if (a === 'rs-no') { nc('cancelRestore'); veil(); toast("L'os reste enterré, rien n'a bougé 🐶"); }
    if (a === 'rs-go') { veil(`<h3>Le chiot creuse… 🦴</h3><p>La niche redémarre avec ton os. Ne quitte pas, ça prend quelques secondes.</p>`); setTimeout(() => nc('restoreNow'), 600); }
    if (a === 'ok') veil();
    if (b.dataset.sw) { const on = !b.classList.contains('on'); S[b.dataset.sw] = on; nc('set', b.dataset.sw, on); render(); }
  });

  // aperçu du fond animé (même dessin que le vrai fond, en miniature)
  let raf = 0;
  function startWall() {
    cancelAnimationFrame(raf);
    const cv = $('#wcv'); if (!cv) return;
    const pels = S.pelages && S.pelages.length ? S.pelages : PEL, P = pels.find((p) => p.id === S.pelage) || pels[0];
    const acc = P.acc, acc2 = P.acc2, bg = P.bg, dpr = Math.min(2, window.devicePixelRatio || 1);
    const W = cv.width = cv.clientWidth * dpr, H = cv.height = cv.clientHeight * dpr, c = cv.getContext('2d');
    const R = Math.random, stars = Array.from({ length: 50 }, () => [R(), R() * .6, R() * 6.28]);
    const paw = () => ({ x: R() * W, y: R() * H, r: (5 + R() * 9) * dpr, v: (10 + R() * 20) * dpr, ph: R() * 6.28, c: R() < .5 ? acc : acc2, rot: (-25 + R() * 50) * Math.PI / 180 });
    const paws = Array.from({ length: 14 }, paw);
    const al = (hex, a) => hex + Math.round(Math.max(0, Math.min(1, a)) * 255).toString(16).padStart(2, '0');
    let last = performance.now(), t0 = last;
    function frame(now) {
      const dt = Math.min(.05, (now - last) / 1000), t = (now - t0) / 1000; last = now;
      let g = c.createLinearGradient(0, 0, 0, H); g.addColorStop(0, al(acc, .22)); g.addColorStop(.55, bg); g.addColorStop(1, al(acc2, .14));
      c.fillStyle = bg; c.fillRect(0, 0, W, H); c.fillStyle = g; c.fillRect(0, 0, W, H);
      for (const s of stars) { const tw = .5 + .5 * Math.sin(t * 1.6 + s[2]); c.fillStyle = `rgba(255,255,255,${.15 + .55 * tw})`; c.beginPath(); c.arc(s[0] * W, s[1] * H, (.8 + tw) * dpr, 0, 7); c.fill(); }
      for (let i = 0; i < 3; i++) {
        const ang = Math.sin(t * (.18 + i * .07) + i * 2) * .5, bx = W * (.2 + i * .3);
        c.save(); c.translate(bx, -20); c.rotate(ang);
        const col = i === 1 ? acc2 : acc; g = c.createLinearGradient(0, 0, 0, H * .9); g.addColorStop(0, al(col, .45)); g.addColorStop(1, al(col, 0));
        c.fillStyle = g; c.fillRect(-2 * dpr, 0, 4 * dpr, H * .9); g = c.createLinearGradient(0, 0, 0, H * .7); g.addColorStop(0, al(col, .12)); g.addColorStop(1, al(col, 0)); c.fillStyle = g; c.fillRect(-18 * dpr, 0, 36 * dpr, H * .7); c.restore();
      }
      const hor = H * .7; g = c.createLinearGradient(0, hor, 0, H); g.addColorStop(0, al(acc, 0)); g.addColorStop(1, al(acc, .3)); c.fillStyle = g; c.fillRect(0, hor, W, H - hor);
      c.lineWidth = dpr; const sc = (t * .35) % 1;
      for (let i = 0; i < 10; i++) { const k = (i + sc) / 10, y = hor + (H - hor) * k * k; c.strokeStyle = al(acc, .12 + .6 * k); c.beginPath(); c.moveTo(0, y); c.lineTo(W, y); c.stroke(); }
      c.strokeStyle = al(acc2, .3); for (let i = -8; i <= 8; i++) { c.beginPath(); c.moveTo(W / 2 + i * 4 * dpr, hor); c.lineTo(W / 2 + i * W * .16, H); c.stroke(); }
      c.fillStyle = al(acc, .65); c.fillRect(0, hor - dpr, W, 2 * dpr);
      for (const q of paws) {
        q.y -= q.v * dt; if (q.y < -30 * dpr) Object.assign(q, paw(), { y: H + 20 * dpr });
        const x = q.x + Math.sin(t * .9 + q.ph) * 8 * dpr, fade = Math.min(1, Math.max(0, q.y / (H * .25))), r = q.r;
        if (fade < .02) continue;
        c.save(); c.translate(x, q.y); c.rotate(q.rot); c.globalAlpha = .8 * fade;
        g = c.createRadialGradient(0, 0, 0, 0, 0, r * 3.2); g.addColorStop(0, al(q.c, .35)); g.addColorStop(1, al(q.c, 0)); c.fillStyle = g; c.beginPath(); c.arc(0, 0, r * 3.2, 0, 7); c.fill();
        g = c.createRadialGradient(-r * .3, -r * .3, 0, 0, 0, r * 1.4); g.addColorStop(0, '#fff'); g.addColorStop(.55, q.c); g.addColorStop(1, q.c); c.fillStyle = g;
        c.beginPath(); c.ellipse(0, r * .42, r, r * .78, 0, 0, 7); c.fill();
        for (const [px, py] of [[-1.08, -.62], [-.4, -1.22], [.4, -1.22], [1.08, -.62]]) { c.beginPath(); c.arc(px * r, py * r, r * .42, 0, 7); c.fill(); }
        c.restore();
      }
      raf = requestAnimationFrame(frame);
    }
    raf = requestAnimationFrame(frame);
  }

  window.NicheUI = {
    on(ev, data) {
      const o = J(data, {}) || {};
      if (ev === 'resume') { const keep = S.pelage; S = J(nc('info'), S) || S; if (!window.Niche) S.pelage = keep; render(); }
      else if (ev === 'backup') {
        if (o.st === 'step') { bk.steps.push(o.msg); render(); }
        else if (o.st === 'done') { bk.st = 'done'; bk.name = o.name; S = J(nc('info'), S) || S; render(); toast('🦴 Os enterré dans Téléchargements › PupNiche'); }
        else if (o.st === 'error') { bk.st = 'idle'; render(); veil(`<h3>Oups, la pelle a cassé 🥺</h3><p>${esc(o.msg || 'Erreur inconnue')}</p><button class="ab wide" data-a="ok" type="button">Compris</button>`); }
      } else if (ev === 'restore') {
        if (o.st === 'reading') veil('<h3>Je renifle l\'os… 👃</h3><p>Vérification du fichier .puppy</p>');
        else if (o.st === 'cancel') veil();
        else if (o.st === 'error') veil(`<h3>Ce n'est pas un os de la niche 🐶</h3><p>${esc(o.msg || '')}</p><p>Choisis un fichier <b>.puppy</b> créé avec « Enterrer un os ».</p><button class="ab wide" data-a="ok" type="button">Compris</button>`);
        else if (o.st === 'ready') {
          const m = o.man || {};
          veil(`<h3>Déterrer cet os ? 🦴</h3>
            <dl><dt>Enterré le</dt><dd>${esc(fmtD(m.date))}</dd><dt>Téléphone</dt><dd>${esc(m.device || '—')}</dd><dt>Version</dt><dd>v1.0.${esc(m.version || '?')}</dd></dl>
            <p>Les réglages actuels de la niche seront <b>remplacés</b> par ceux de l'os, puis PuppyPhone redémarre tout seul. Tes photos, musiques et SMS ne sont pas touchés.</p>
            <div class="nrow"><button class="ab glass" data-a="rs-no" type="button">Laisser enterré</button><button class="ab amber" data-a="rs-go" type="button">Déterrer !</button></div>`);
        }
      }
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };
  $('#nlogo').innerHTML = ic('bone', 'violet');
  render();
})();
