/* Volet PuppyPhone — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const J = (s, d) => { try { return JSON.parse(s); } catch (e) { return d; } };
  const V = window.Volet || mock();
  const { card, swipe, esc } = window.PupCard;
  let st = {}, pr = J(V.prefs(), {}) || {}, notifs = [], med = {}, replyKey = '';
  if (pr.acc) { document.documentElement.style.setProperty('--acc', pr.acc); document.documentElement.style.setProperty('--acc2', pr.acc2); }

  // ------------------------------------------------------------ horloge + batterie
  function clock() {
    const n = new Date();
    $('#hh').textContent = String(n.getHours()).padStart(2, '0'); $('#mm').textContent = String(n.getMinutes()).padStart(2, '0');
    $('#d1').textContent = n.toLocaleDateString('fr-FR', { weekday: 'long' }); $('#d2').textContent = n.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long' });
  }
  function batt() {
    const p = st.pct >= 0 ? st.pct : 0;
    $('#liq').style.width = p + '%'; $('#liq').classList.toggle('low', p <= 15 && !st.charging);
    $('#bpct').textContent = (st.pct >= 0 ? st.pct : '--') + '%'; $('#bat').classList.toggle('chg', !!st.charging);
    $('#alm').textContent = st.alarm ? st.alarm : 'Réveil';
  }

  // ------------------------------------------------------------ tuiles
  const RING = { 2: ['🔔', 'Son'], 1: ['📳', 'Vibreur'], 0: ['🔕', 'Silence'] };
  const TILES = [
    ['wifi', () => ['📶', 'Wi-Fi', st.wifi]], ['son', () => [RING[st.ringer == null ? 2 : st.ringer][0], RING[st.ringer == null ? 2 : st.ringer][1], st.ringer === 2]],
    ['torch', () => ['🔦', 'Lampe', st.torch]], ['bt', () => ['🎧', 'Bluetooth', st.bt]],
    ['dnd', () => ['🌙', 'Ne pas déranger', st.dnd]], ['rot', () => ['🔄', 'Rotation auto', st.rot]], ['loc', () => ['📍', 'Position', st.loc]], ['data', () => ['📡', 'Données', false]],
    ['plane', () => ['✈️', 'Mode avion', st.plane]], ['veille', () => ['🐶', 'Veille puppy', st.veille]], ['capture', () => ['📸', 'Capture', false]], ['lock', () => ['🔒', 'Verrouiller', false]],
    ['scan', () => ['📄', 'PupScan', false]], ['dicta', () => ['📼', 'PupDicta', st.dicta]],
  ];
  function tiles() {
    $('#tiles').innerHTML = TILES.map(([id, f]) => { const [e, l, on] = f(); return `<div class="tl${on ? ' on' : ''}" data-tile="${id}"><button class="btn" type="button">${e}</button><small>${esc(l)}</small></div>`; }).join('');
    $('#autob').classList.toggle('on', !!st.autoBright);
  }
  let lp = 0, lpFired = false;
  $('#tiles').addEventListener('pointerdown', (e) => { const t = e.target.closest('[data-tile]'); if (!t) return; lpFired = false; clearTimeout(lp); lp = setTimeout(() => { lpFired = true; V.haptic(); V.tileLong(t.dataset.tile); }, 520); });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((k) => $('#tiles').addEventListener(k, () => clearTimeout(lp)));
  $('#tiles').addEventListener('click', (e) => {
    const t = e.target.closest('[data-tile]'); if (!t || lpFired) return;
    const id = t.dataset.tile; V.haptic();
    if (['torch', 'rot', 'veille', 'dnd', 'bt', 'wifi'].includes(id)) t.classList.toggle('on'); // réponse immédiate, l'état réel suit
    V.tile(id); setTimeout(refreshState, 450);
  });
  $('#more').addEventListener('click', () => { $('#tiles').classList.toggle('all'); $('#more').classList.toggle('up'); });

  // ------------------------------------------------------------ curseurs-os
  const SL = {
    bright: { get: () => (st.bright || 128) / 255, set: (f) => V.setBright(Math.round(1 + f * 254)) },
    vol: { get: () => st.volMax ? st.vol / st.volMax : .5, set: (f) => V.setVol(Math.round(f * (st.volMax || 15))) },
  };
  function paintSl(g, f) { $('.fill', g).style.width = `calc(${f * 100}% - ${f * 8}px + 4px)`; $('.bone', g).style.left = `calc(22px + ${f} * (100% - 44px))`; }
  function sliders() { document.querySelectorAll('[data-sl]').forEach((g) => paintSl(g, SL[g.dataset.sl].get())); $('#vv').textContent = st.volMax ? Math.round(st.vol / st.volMax * 100) : ''; }
  document.querySelectorAll('[data-sl]').forEach((g) => {
    let dragging = false, last = 0;
    const at = (e) => { const r = g.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left - 22) / (r.width - 44))); };
    const go = (e, force) => { const f = at(e); paintSl(g, f); const now = Date.now(); if (force || now - last > 70) { last = now; SL[g.dataset.sl].set(f); if (g.dataset.sl === 'vol') { st.vol = Math.round(f * (st.volMax || 15)); $('#vv').textContent = Math.round(f * 100); } else st.bright = Math.round(1 + f * 254); } };
    g.addEventListener('pointerdown', (e) => { dragging = true; g.setPointerCapture(e.pointerId); go(e, true); });
    g.addEventListener('pointermove', (e) => { if (dragging) go(e); });
    g.addEventListener('pointerup', (e) => { if (dragging) { dragging = false; go(e, true); } });
  });
  $('#autob').addEventListener('click', () => { V.tile('autobright'); st.autoBright = !st.autoBright; tiles(); setTimeout(refreshState, 400); });

  // ------------------------------------------------------------ platine
  function media() {
    med = J(V.media(), {}) || {};
    const has = !!med.title;
    $('#media').classList.toggle('hidden', !has); if (!has) return;
    $('#media').classList.toggle('play', !!med.playing);
    $('#mtit').textContent = med.title; $('#mart').textContent = med.artist || med.album || ''; $('#mapp').textContent = med.app || '';
    $('#mplay').textContent = med.playing ? '❚❚' : '▶';
    const src = med.art ? '/nart?v=' + med.id : ''; if ($('#art').dataset.src !== src) { $('#art').dataset.src = src; $('#art').src = src || 'data:,'; $('#art').style.visibility = src ? '' : 'hidden'; }
  }
  $('#media').addEventListener('click', (e) => { const b = e.target.closest('[data-m]'); if (!b) return; V.haptic(); V.mediaCmd(b.dataset.m); if (b.dataset.m === 'toggle') { med.playing = !med.playing; $('#media').classList.toggle('play', med.playing); $('#mplay').textContent = med.playing ? '❚❚' : '▶'; } setTimeout(media, 500); });

  // ------------------------------------------------------------ courrier
  function list() {
    pr = J(V.prefs(), pr) || pr;
    notifs = (J(V.notifs(), []) || []).filter((n) => !n.media || !med.title);
    $('#ncount').textContent = notifs.length ? notifs.length + ' lettre' + (notifs.length > 1 ? 's' : '') : '';
    $('#clear').classList.toggle('hidden', !notifs.some((n) => n.clear));
    if (!pr.granted) { $('#nlist').innerHTML = `<div class="grant"><b>🙉 Le chiot n’entend pas encore tes notifications</b>Autorise <b>PuppyPhone</b> dans « Accès aux notifications » pour les voir ici en version puppyplay.<button class="ab small amber" id="grant" type="button">Autoriser</button></div>`; return; }
    $('#nlist').innerHTML = notifs.length ? notifs.map((n) => card(n)).join('') : `<div class="empty"><b>Boîte aux lettres vide 🐶</b>Aucune notification. Le facteur est passé, il n’a rien laissé.</div>`;
    document.querySelectorAll('#nlist .nc').forEach((el) => swipe(el, {
      open: (k) => { if (replyKey) return; V.openNotif(k); },
      dismiss: (k, el2) => { V.dismiss(k); el2.style.height = el2.offsetHeight + 'px'; requestAnimationFrame(() => { el2.style.transition = 'height .2s, margin .2s, padding .2s'; el2.style.height = '0'; el2.style.padding = '0'; el2.style.marginBottom = '-10px'; }); setTimeout(() => el2.remove(), 260); },
      cant: () => toast('Celle-ci est épinglée par son appli 📌'),
    }));
    if (replyKey) openReply(replyKey);
  }
  function openReply(k) {
    const el = document.querySelector(`.nc[data-key="${CSS.escape(k)}"]`); if (!el) { replyKey = ''; return; }
    const b = el.querySelector('[data-reply="1"]'); if (!b) { replyKey = ''; return; }
    replyKey = k;
    if (!el.querySelector('.rep')) { const r = document.createElement('div'); r.className = 'rep'; r.innerHTML = `<input placeholder="Ta réponse… 🐾" enterkeyhint="send"><button type="button">➤</button>`; el.querySelector('.bd').appendChild(r); r.dataset.i = b.dataset.act; }
    setTimeout(() => { const i = el.querySelector('.rep input'); if (i) i.focus(); }, 80);
  }
  function sendReply(el) {
    const r = el.querySelector('.rep'), t = r.querySelector('input').value.trim(); if (!t) return;
    const ok = V.act(el.dataset.key, +r.dataset.i, t); r.remove(); replyKey = '';
    toast(ok ? 'Réponse envoyée 🐾' : 'Oups, l’appli n’a pas pris la réponse 😿');
  }
  $('#nlist').addEventListener('click', (e) => {
    if (e.target.closest('#grant')) { V.grant(); return; }
    const el = e.target.closest('.nc'); if (!el) return;
    const rb = e.target.closest('.rep button'); if (rb) { sendReply(el); return; }
    const a = e.target.closest('[data-act]'); if (!a) return;
    V.haptic();
    if (a.dataset.reply === '1') openReply(el.dataset.key);
    else V.act(el.dataset.key, +a.dataset.act, '');
  });
  $('#nlist').addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target.closest('.rep input')) sendReply(e.target.closest('.nc')); });
  $('#clear').addEventListener('click', () => {
    V.haptic(); const cs = [...document.querySelectorAll('#nlist .nc[data-clear="1"]')];
    cs.forEach((el, i) => setTimeout(() => { el.style.transform = 'translateX(120%) rotate(8deg)'; el.style.opacity = '0'; }, i * 60));
    setTimeout(() => { V.clearAll(); }, cs.length * 60 + 250);
  });

  // ------------------------------------------------------------ remonter le volet
  function dragger(el) {
    let y0 = 0, on = false, dy = 0, t0 = 0, moved = false;
    el.addEventListener('pointerdown', (e) => { if (e.target.closest('button,input,.groove')) return; on = true; moved = false; y0 = e.clientY; dy = 0; t0 = performance.now(); el.setPointerCapture(e.pointerId); });
    el.addEventListener('pointermove', (e) => { if (!on) return; dy = Math.min(0, e.clientY - y0); if (dy < -6) moved = true; if (moved) V.drag(dy); });
    const end = () => { if (!on) return; on = false; if (moved) { const vy = dy / Math.max(1, performance.now() - t0) * 1000; V.release(dy, vy); } };
    el.addEventListener('pointerup', end); el.addEventListener('pointercancel', end);
  }
  dragger($('#grip')); dragger($('#hdr'));
  // Glissé vers le haut sur la liste des notifications : referme le volet (le défilement normal reste possible)
  (function () {
    const L = $('#nlist'); if (!L) return;
    let sy = 0, st0 = 0, active = false;
    L.addEventListener('pointerdown', (e) => { if (e.target.closest('button,input,.rep')) { active = false; return; } sy = e.clientY; st0 = performance.now(); active = true; });
    L.addEventListener('pointerup', (e) => {
      if (!active) return; active = false;
      const dy = e.clientY - sy, vy = dy / Math.max(1, performance.now() - st0) * 1000;
      if (dy < -90 && vy < -350 && $('#panel').scrollTop < 6) V.close();
    });
  })();
  $('#out').addEventListener('click', () => V.close());
  document.addEventListener('click', (e) => { const g = e.target.closest('[data-g]'); if (g) { V.haptic(); V.glob(g.dataset.g); } });

  function toast(m) { let t = $('.vtoast'); if (!t) { t = document.createElement('div'); t.className = 'vtoast'; t.style.cssText = 'position:fixed;left:50%;bottom:30px;transform:translateX(-50%);z-index:9;padding:9px 16px;border-radius:14px;font-size:13px;background:#1d0f30;border:1px solid rgba(255,255,255,.4);box-shadow:0 8px 20px rgba(0,0,0,.7);transition:opacity .3s'; document.body.appendChild(t); } t.textContent = m; t.style.opacity = '1'; clearTimeout(t._h); t._h = setTimeout(() => { t.style.opacity = '0'; }, 2400); }

  // ------------------------------------------------------------ cycle de vie
  function refreshState() { st = J(V.state(), st) || st; batt(); tiles(); sliders(); }
  function all() { clock(); refreshState(); media(); list(); }
  let ti = 0;
  window.VoletUI = {
    on(ev) {
      if (ev === 'open') { try { const k = localStorage.getItem('replyKey'); if (k) { replyKey = k; localStorage.removeItem('replyKey'); } } catch (e) { } all(); $('#panel').scrollTop = 0; clearInterval(ti); ti = setInterval(() => { clock(); media(); }, 1000); }
      if (ev === 'closed') { clearInterval(ti); replyKey = ''; document.activeElement && document.activeElement.blur && document.activeElement.blur(); }
      if (ev === 'notifs') { media(); list(); }
      if (ev === 'state') refreshState();
    },
    back() { if (replyKey) { const r = document.querySelector('.rep'); if (r) r.remove(); replyKey = ''; return true; } return false; },
  };
  all();

  function mock() {
    const N = [
      { key: 'a', pkg: 'com.whatsapp', app: 'WhatsApp', when: Date.now() - 120000, title: 'Rex 🐶', text: '', lines: [{ who: 'Rex', t: 'Tu viens au parc ?' }, { who: 'Rex', t: 'J’ai un nouvel os 🦴' }], acts: [{ i: 0, t: 'Répondre', reply: true }, { i: 1, t: 'Marquer comme lu' }], clear: true, hasColor: true, color: '#25D366' },
      { key: 'b', pkg: 'fr.piika.puppyphone', app: 'PupAgenda', when: Date.now() - 900000, title: '📅 Toilettage de Rex', text: '14:30 · vendredi 9 octobre', acts: [], clear: true },
      { key: 'c', pkg: 'com.android.vending', app: 'Play Store', when: Date.now() - 3600000, title: 'Mise à jour en cours', text: '3 applis', prog: 62, acts: [], clear: false, ongoing: true },
      { key: 'd', pkg: 'com.google.android.gm', app: 'Gmail', when: Date.now() - 7200000, title: 'Croquettes & Co', text: 'Votre commande de croquettes premium a été expédiée ! Livraison prévue demain entre 9h et 12h.', acts: [{ i: 0, t: 'Archiver' }, { i: 1, t: 'Répondre', reply: true }], clear: true, hasColor: true, color: '#EA4335' },
    ];
    return {
      prefs: () => JSON.stringify({ granted: true }), state: () => JSON.stringify({ pct: 72, charging: true, wifi: true, bt: false, torch: false, ringer: 2, vol: 9, volMax: 15, dnd: false, rot: true, bright: 150, autoBright: false, loc: true, plane: false, veille: true, dicta: false, alarm: '07:30' }),
      notifs: () => JSON.stringify(N), media: () => JSON.stringify({ title: 'Who Let the Dogs Out', artist: 'Baha Men', app: 'PupMusic', playing: true, art: false, id: 1 }),
      tile() {}, tileLong() {}, setBright() {}, setVol() {}, mediaCmd() {}, openNotif() {}, act: () => true, dismiss() {}, clearAll() {}, grant() {}, close() {}, drag() {}, release() {}, glob() {}, haptic() {},
    };
  }
})();
