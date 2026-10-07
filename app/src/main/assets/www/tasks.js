/* PupTasks — « 🐾 Les dernières balades » */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon, PAL = PupIcons.PAL;
  const ic = (g, c, sh) => I(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Tasks;
  const T = window.Tasks || mockT();
  const tc = (fn, ...a) => { try { return T[fn] ? T[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const haptic = () => tc('haptic');
  const fmtMem = (b) => (b / 1073741824).toFixed(1).replace('.', ',') + ' Go';
  function ago(t) {
    const s = Math.max(0, (Date.now() - t) / 1000);
    if (s < 50) return 'à l\'instant';
    if (s < 3600) return `il y a ${Math.round(s / 60)} min`;
    if (s < 86400) return `il y a ${Math.round(s / 3600)} h`;
    return `il y a ${Math.round(s / 86400)} j`;
  }
  const COLS = ['pink', 'cyan', 'violet', 'amber', 'green', 'orange', 'blue'];
  const colorOf = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return PAL[COLS[h % COLS.length]][1]; };

  let cards = [];

  // ------------------------------------------------------------------ mémoire
  function ram() {
    const m = J(tc('mem'), {}) || {};
    if (!m.total) { $('#ram').hidden = true; return m; }
    const used = m.total - m.avail;
    $('#ram').innerHTML = `${ic('bowl', 'pink')}<div><b><span>Gamelle RAM</span><span>${fmtMem(m.avail)} libres / ${fmtMem(m.total)}</span></b><div class="g"><i style="width:${(used / m.total * 100).toFixed(1)}%"></i></div></div>`;
    return m;
  }

  // ------------------------------------------------------------------ cartes
  function load() {
    const p = J(tc('perms'), {}) || {};
    ram();
    const deck = $('#deck');
    if (!p.usage) {
      cards = [];
      deck.innerHTML = `<div class="empty3">${ic('clock', 'pink')}<b>Je ne sens aucune piste…</b><p>Pour lister tes dernières balades, Android demande l'autorisation « <b>Accès aux données d'utilisation</b> » pour PuppyPhone.</p><button class="ab wide" id="gousage" type="button">${ic('check', 'chrome', 'none')}Autoriser</button></div>`;
      $('#gousage').onclick = () => tc('openUsage');
      $('#dots').innerHTML = ''; $('#tsub').textContent = 'Autorisation requise';
      return;
    }
    cards = J(tc('list'), []) || [];
    if (cards.length && !cards.some((c) => c.current) && Date.now() - cards[0].t < 90000) cards[0].current = true;
    $('#tsub').textContent = cards.length ? `${cards.length} balade${cards.length > 1 ? 's' : ''} · glisse une carte vers le haut pour la fermer` : 'PupTasks';
    if (!cards.length) {
      deck.innerHTML = `<div class="empty3">${ic('niche', 'pink')}<b>Tout le monde est à la niche</b><p>Aucune appli récente. Va te balader un peu 🐾</p></div>`;
      $('#dots').innerHTML = '';
      return;
    }
    deck.innerHTML = cards.map((c, i) => {
      const iconUrl = MOCK ? '' : `https://puptasks.local/icon?k=${encodeURIComponent(c.key)}`;
      const img = iconUrl ? `<img src="${iconUrl}" alt="">` : `<span class="gi">${ic(c.glyph || 'apps', c.color || 'pink')}</span>`;
      const ac = c.self ? PAL[c.color || 'pink'][1] : colorOf(c.pkg);
      return `<div class="card${c.current ? ' cur' : ''}" data-i="${i}" style="--ac:${ac}"><div class="cframe"><div class="cin">
        <i class="rv a"></i><i class="rv b"></i><i class="rv c"></i><i class="rv d"></i>
        <div class="chead">${img}<span class="tx"><b>${esc(c.name)}</b><small>${c.current ? 'Balade en cours' : 'Utilisée ' + ago(c.t)}</small></span></div>
        <div class="cbody"><div class="bigic">${img}</div>
          ${c.current ? `<span class="badge2">${ic('paw', 'chrome', 'none').replace('class="pi"', 'class="pi" style="width:16px;height:16px"')}Balade en cours</span>` : ''}
          ${c.self ? `<span class="mem">Module PupOS${c.running ? ' · en mémoire' : ''}</span>` : `<span class="mem">${esc(c.pkg)}</span>`}
        </div><div class="swipehint">↑ glisser pour fermer</div></div></div></div>`;
    }).join('');
    $('#dots').innerHTML = cards.slice(0, 14).map(() => '<i></i>').join('');
    bind();
    requestAnimationFrame(() => { const first = cards.findIndex((c) => !c.current); const target = cards.length > 1 && cards[0].current ? 1 : 0; scrollTo(target, false); tilt(); });
  }

  function scrollTo(i, smooth) {
    const el = $$('.card')[i]; if (!el) return;
    const deck = $('#deck');
    deck.scrollTo({ left: el.offsetLeft - (deck.clientWidth - el.clientWidth) / 2, behavior: smooth ? 'smooth' : 'auto' });
  }

  // effet 3D selon la position
  function tilt() {
    const deck = $('#deck'), mid = deck.scrollLeft + deck.clientWidth / 2;
    let best = 0, bd = 1e9;
    $$('.card', deck).forEach((c, i) => {
      if (c.classList.contains('drag') || c.classList.contains('gone')) return;
      const cx = c.offsetLeft + c.offsetWidth / 2, d = (cx - mid) / deck.clientWidth;
      if (Math.abs(d) < bd) { bd = Math.abs(d); best = i; }
      const k = Math.max(-1, Math.min(1, d * 1.6));
      c.style.transform = `translateZ(${-Math.abs(k) * 120}px) rotateY(${-k * 28}deg) scale(${1 - Math.abs(k) * .08})`;
      c.style.zIndex = String(100 - Math.round(Math.abs(d) * 10));
      c.style.opacity = String(1 - Math.min(.45, Math.abs(k) * .35));
    });
    $$('#dots i').forEach((d, i) => d.classList.toggle('on', i === best));
  }
  $('#deck').addEventListener('scroll', () => requestAnimationFrame(tilt), { passive: true });

  function bind() {
    $$('.card').forEach((card) => {
      let y0 = 0, x0 = 0, dy = 0, mode = null, t0 = 0;
      card.onpointerdown = (e) => { y0 = e.clientY; x0 = e.clientX; dy = 0; mode = null; t0 = Date.now(); };
      card.onpointermove = (e) => {
        if (!e.buttons && e.pointerType === 'mouse') return;
        const ddx = e.clientX - x0, ddy = e.clientY - y0;
        if (!mode) { if (Math.abs(ddy) > 12 && Math.abs(ddy) > Math.abs(ddx)) { mode = 'v'; card.setPointerCapture(e.pointerId); card.classList.add('drag'); } else if (Math.abs(ddx) > 12) mode = 'h'; }
        if (mode === 'v') { dy = Math.min(40, ddy); card.style.transform = `translateY(${dy}px) rotate(${dy / -30}deg) scale(${1 + dy / 2000})`; card.style.opacity = String(1 + dy / 600); }
      };
      const end = () => {
        if (mode === 'v') {
          card.classList.remove('drag');
          if (dy < -110) dismiss(card); else { card.style.opacity = ''; tilt(); }
        } else if (!mode && Date.now() - t0 < 500) { haptic(); openCard(card); }
        mode = null;
      };
      card.onpointerup = end; card.onpointercancel = () => { mode = null; card.classList.remove('drag'); tilt(); };
    });
  }

  function openCard(card) {
    const c = cards[+card.dataset.i]; if (!c) return;
    card.style.transition = 'transform .25s ease-in'; card.style.transform = 'scale(1.12)';
    setTimeout(() => tc('open', c.key), 140);
  }

  function dismiss(card) {
    const c = cards[+card.dataset.i]; if (!c) return;
    haptic();
    card.classList.add('gone');
    card.style.transform = 'translateY(-120vh) rotate(-8deg)';
    tc('close', c.key);
    setTimeout(() => {
      card.style.width = '0'; card.style.margin = '0'; card.style.transition = 'width .25s, margin .25s';
      setTimeout(() => { load(); }, 260);
    }, 300);
  }

  // ------------------------------------------------------------------ « Rentrer à la niche »
  $('#purge').onclick = () => {
    if (!cards.length) { tc('home'); return; }
    haptic();
    const before = (J(tc('mem'), {}) || {}).avail || 0;
    const btn = $('#purge'); btn.classList.add('chew');
    $$('.card').forEach((c, i) => setTimeout(() => { c.classList.add('gone'); c.style.transform = 'translateY(-120vh) rotate(' + (i % 2 ? 8 : -8) + 'deg)'; }, i * 60));
    cards.forEach((c) => tc('close', c.key));
    setTimeout(() => {
      const after = (J(tc('mem'), {}) || {}).avail || 0;
      const freed = Math.max(0, after - before);
      toast(`Tout le monde à la niche 🐾 ${cards.length} balade${cards.length > 1 ? 's' : ''} fermée${cards.length > 1 ? 's' : ''}${freed > 20e6 ? ' · ' + Math.round(freed / 1048576) + ' Mo libérés' : ''}`, 'niche');
      ram();
      setTimeout(() => tc('home'), 900);
    }, 650 + cards.length * 60);
  };
  $('#bhome').onclick = () => { haptic(); tc('home'); };
  $('#bclose').onclick = () => { haptic(); tc('exit'); };
  $('#tset').onclick = () => openSettings();

  // ------------------------------------------------------------------ réglages PupNav
  function openSettings() {
    const p = document.createElement('div'); p.className = 'panel';
    p.innerHTML = `<div class="phd"><button class="hbtn" data-close type="button">${ic('left', 'chrome', 'none')}</button><span style="width:40px;height:40px;flex:none">${ic('niche', 'pink')}</span><div class="tx"><b>PupNav & PupTasks</b><small>Barre de navigation et multitâche</small></div></div><div class="pbd"></div>`;
    $('#layer').appendChild(p);
    $('[data-close]', p).onclick = () => { p.remove(); load(); };
    paintSettings(p);
    p.paint = () => paintSettings(p);
  }
  function paintSettings(p) {
    const pm = J(tc('perms'), {}) || {}, s = J(tc('settings'), {}) || {};
    const st = (ok, a, b) => `<span class="st ${ok ? 'ok' : 'no'}">${ok ? a || 'ACTIVÉ' : b || 'À ACTIVER'}</span>`;
    const samsung = /samsung/i.test(pm.brand || '');
    $('.pbd', p).innerHTML = `
      <div class="pcard"><h3>${ic('clock', 'pink')}Liste des balades ${st(pm.usage)}</h3><p>Autorisation « Accès aux données d'utilisation » : sert à ranger les cartes de la plus récente à la plus ancienne.</p>${pm.usage ? '' : `<button class="ab small" data-o="usage" type="button">Ouvrir le réglage</button>`}</div>
      <div class="pcard"><h3>${ic('paw', 'cyan')}Service PupNav (accessibilité) ${st(pm.a11y)}</h3>
        <p>Recommandé. Le service permet au bouton <b>Retour 🐾</b> de marcher dans toutes les applis, et cache la barre quand le clavier s'ouvre. Il ne lit pas le contenu de tes écrans.</p>
        ${pm.a11y ? '' : `<ol>${pm.sdk >= 33 ? `<li>Appuie sur « Infos de l'appli », puis sur <b>⋮</b> en haut à droite → <b>Autoriser les paramètres restreints</b> (obligatoire pour une appli installée en APK).</li>` : ''}<li>Ouvre « Accessibilité »${samsung ? ' → <b>Applications installées</b>' : ''} → <b>PupNav</b> → active.</li></ol>
        <div style="display:flex;gap:8px">${pm.sdk >= 33 ? `<button class="ab small glass" data-o="info" type="button">Infos de l'appli</button>` : ''}<button class="ab small c2" data-o="a11y" type="button">Accessibilité</button></div>`}</div>
      <div class="pcard"><h3>${ic('apps', 'violet')}Affichage par-dessus les applis ${st(pm.overlay)}</h3><p>Mode « overlay » (SYSTEM_ALERT_WINDOW) : la barre s'affiche même si le service d'accessibilité est coupé (le bouton Retour a quand même besoin de PupNav).</p>${pm.overlay ? '' : `<button class="ab small violet" data-o="overlay" type="button">Autoriser</button>`}</div>
      <div class="pcard"><h3>${ic('niche', 'pink')}PupNavigationBar</h3>
        <div class="choices2">
          <button class="ch2${s.style === 'off' ? ' on' : ''}" data-set="style" data-v="off" type="button"><span class="mock"></span>Aucune</button>
          <button class="ch2${s.style === 'float' ? ' on' : ''}" data-set="style" data-v="float" type="button"><span class="mock"><i style="width:60%;border-radius:7px"></i></span>Flottante</button>
          <button class="ch2${s.style === 'full' ? ' on' : ''}" data-set="style" data-v="full" type="button"><span class="mock"><i style="width:100%;border-radius:2px"></i></span>Pleine largeur</button>
        </div>
        <p style="font-size:12px;opacity:.85">Flottante : appui long puis glisse pour la déplacer. Pleine largeur : appui long sur la niche = PupTasks.</p>
        <div class="lbl">Ordre des boutons</div>
        <div class="seg" style="margin-bottom:10px"><button class="${s.order === 'samsung' ? 'on' : ''}" data-set="order" data-v="samsung" type="button">🦴 · 🛖 · 🐾 (Samsung)</button><button class="${s.order !== 'samsung' ? 'on' : ''}" data-set="order" data-v="android" type="button">🐾 · 🛖 · 🦴</button></div>
        <div class="lbl">Hauteur</div><div class="rng"><input type="range" min="40" max="72" step="2" value="${s.height}" data-r="height"><b>${s.height} dp</b></div>
        <div class="lbl">Opacité</div><div class="rng"><input type="range" min="30" max="100" step="5" value="${s.alpha}" data-r="alpha"><b>${s.alpha} %</b></div>
      </div>
      <div class="pcard"><h3>${ic('bone', 'gold')}Poignée de bord</h3>
        <div class="row"><span>Trait néon sur le bord<small>Glisse vers l'intérieur pour ouvrir PupTasks, depuis n'importe quelle appli</small></span><button class="sw${s.edge ? ' on' : ''}" data-sw="edge" type="button"><i></i></button></div>
        <div class="seg" style="margin:6px 0 10px"><button class="${s.edgeSide === 'left' ? 'on' : ''}" data-set="edgeSide" data-v="left" type="button">Bord gauche</button><button class="${s.edgeSide !== 'left' ? 'on' : ''}" data-set="edgeSide" data-v="right" type="button">Bord droit</button></div>
        <div class="lbl">Position verticale</div><div class="rng"><input type="range" min="-300" max="300" step="10" value="${s.edgeY}" data-r="edgeY"><b>${s.edgeY}</b></div>
      </div>
      <div class="pcard"><h3>${ic('info', 'blue')}Astuce One UI</h3><p>Pour que seule la barre puppyplay soit visible : Paramètres → Affichage → Barre de navigation → <b>Gestes de balayage</b>, puis désactive « <b>Indice gestuel</b> ». Le geste Samsung (glisser depuis le bas) reste actif en dessous.</p><p>Tu peux aussi ajouter la tuile <b>PupTasks</b> dans les réglages rapides (crayon ✏️ du volet de notifications).</p></div>`;
    $$('input[type=range]', p).forEach((r) => {
      const paint = () => { r.style.setProperty('--v', ((r.value - r.min) / (r.max - r.min) * 100) + '%'); r.nextElementSibling.textContent = r.value + (r.dataset.r === 'height' ? ' dp' : r.dataset.r === 'alpha' ? ' %' : ''); };
      paint(); r.oninput = paint;
      r.onchange = () => tc('set', JSON.stringify({ [r.dataset.r]: +r.value }));
    });
    $('.pbd', p).onclick = (e) => {
      const o = e.target.closest('[data-o]'); if (o) { ({ usage: () => tc('openUsage'), a11y: () => tc('openA11y'), info: () => tc('openAppInfo'), overlay: () => tc('openOverlay') })[o.dataset.o](); return; }
      const b = e.target.closest('[data-set]');
      if (b) {
        haptic();
        if (b.dataset.set === 'style' && b.dataset.v !== 'off' && !pm.a11y && !pm.overlay) toast('Active d\'abord PupNav (accessibilité) ou l\'affichage par-dessus les applis', 'info');
        tc('set', JSON.stringify({ [b.dataset.set]: b.dataset.v })); paintSettings(p); return;
      }
      const sw = e.target.closest('[data-sw]');
      if (sw) { haptic(); const on = !sw.classList.contains('on'); tc('set', JSON.stringify({ [sw.dataset.sw]: on })); paintSettings(p); }
    };
  }

  function toast(msg, g) {
    const t = document.createElement('div'); t.className = 'toast';
    t.innerHTML = `${ic(g || 'paw', 'pink')}<span>${esc(msg)}</span>`;
    $('#toasts').appendChild(t);
    setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 320); }, 2400);
  }

  window.TasksUI = {
    on(ev, data) {
      if (ev === 'resume') { const pnl = $('.panel'); if (pnl && pnl.paint) pnl.paint(); else load(); }
      if (ev === 'reload') { if (data === 'settings' && !$('.panel')) openSettings(); load(); }
    },
    back() { const pnl = $('.panel'); if (pnl) { pnl.remove(); load(); return true; } return false; },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };

  $('#tset').innerHTML = ic('gear', 'chrome', 'none');
  $('#bhome').innerHTML = ic('niche', 'pink', 'none');
  $('#bclose').innerHTML = ic('left', 'chrome', 'none');
  load();
  if (new URLSearchParams(location.search).get('settings')) openSettings();

  function mockT() {
    const now = Date.now();
    return {
      perms: () => JSON.stringify({ usage: true, a11y: false, overlay: true, sdk: 34, brand: 'samsung' }),
      mem: () => JSON.stringify({ avail: 3.4e9, total: 7.6e9 }),
      settings: () => JSON.stringify({ style: 'full', order: 'samsung', height: 54, alpha: 100, edge: true, edgeSide: 'right', edgeY: -60 }),
      list: () => JSON.stringify([
        { key: 'self:MusicActivity', name: 'PupMusic', glyph: 'music', color: 'pink', self: true, running: true, current: true, t: now - 20000 },
        { key: 'com.whatsapp', pkg: 'com.whatsapp', name: 'WhatsApp', self: false, t: now - 120000 },
        { key: 'self:FileActivity', name: 'PupFile', glyph: 'folder', color: 'amber', self: true, running: true, t: now - 600000 },
        { key: 'self:CameraActivity', name: 'PupCamera', glyph: 'camera', color: 'orange', self: true, t: now - 4e6 },
        { key: 'com.android.chrome', pkg: 'com.android.chrome', name: 'Chrome', self: false, t: now - 9e6 },
      ]),
    };
  }
})();
