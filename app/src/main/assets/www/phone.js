/* PupPhone — composeur et écran d'appel. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon, PAL = PupIcons.PAL;
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const norm = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Phone;
  const P = window.Phone || mockP();
  const C = window.Call || mockC();
  const call = (o, fn, ...a) => { try { return o[fn] ? o[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const ph = (fn, ...a) => call(P, fn, ...a);
  const cl = (fn, ...a) => call(C, fn, ...a);
  const XSVG = '<svg viewBox="0 0 20 20"><path d="M4 4L16 16M16 4L4 16" stroke="#fff" stroke-width="3.4" stroke-linecap="round"/></svg>';
  const COLS = ['pink', 'cyan', 'violet', 'amber', 'green', 'red', 'blue', 'orange'];
  const colorOf = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return PAL[COLS[h % COLS.length]][1]; };
  const haptic = () => ph('haptic');
  const mode = new URLSearchParams(location.search).get('mode') || 'dial';
  const digits = (s) => String(s || '').replace(/[^0-9]/g, '');

  function avatar(name, photo, key) {
    const ini = (String(name || '').replace(/[^\p{L}\p{N}]/gu, '')[0] || '#').toUpperCase();
    const img = photo && !MOCK ? `<img src="https://pupphone.local/avatar?u=${encodeURIComponent(photo)}" alt="" onerror="this.remove()">` : '';
    return `<span class="av" style="--c:${colorOf(key || name)}">${esc(ini)}${img}</span>`;
  }
  const fmtDur = (s) => { s = Math.max(0, Math.floor(s)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : String(m).padStart(2, '0')) + ':' + String(x).padStart(2, '0'); };
  function when(ms) {
    const d = new Date(ms), now = new Date();
    if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return 'hier ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
  }

  // =================================================================== COMPOSEUR
  let tab = 'keypad', num = '', contacts = null, log = null;
  const KEYS = [['1', ''], ['2', 'ABC'], ['3', 'DEF'], ['4', 'GHI'], ['5', 'JKL'], ['6', 'MNO'], ['7', 'PQRS'], ['8', 'TUV'], ['9', 'WXYZ'], ['*', ''], ['0', '+'], ['#', '']];
  const T9 = { a: 2, b: 2, c: 2, d: 3, e: 3, f: 3, g: 4, h: 4, i: 4, j: 5, k: 5, l: 5, m: 6, n: 6, o: 6, p: 7, q: 7, r: 7, s: 7, t: 8, u: 8, v: 8, w: 9, x: 9, y: 9, z: 9 };
  const t9 = (name) => norm(name).replace(/[a-z]/g, (ch) => T9[ch]).replace(/[^0-9 ]/g, '');

  function getContacts() { if (!contacts) contacts = J(ph('contacts'), []) || []; return contacts; }

  function renderDial() {
    const def = ph('isDefault');
    const bn = $('#pbanner');
    bn.hidden = !!def;
    if (!def) bn.innerHTML = `<span>🐾 Pour l'écran d'appel PupPhone (appels entrants, verrouillé), fais-en ton appli Téléphone.</span><button class="ab small green" id="mkdef" type="button">Activer</button>`;
    const mk = $('#mkdef'); if (mk) mk.onclick = () => ph('askDefault');
    const ib = $('#incallbar');
    ib.hidden = !ph('inCall');
    ib.innerHTML = `${I('phone', 'green', { shape: 'none' }).replace('class="pi"', 'class="pi" style="width:24px;height:24px"')}<span style="flex:1">Appel en cours — toucher pour revenir</span>`;
    ib.onclick = () => ph('openCall');
    $$('#ptabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
    const body = $('#pbody');
    if (tab === 'keypad') {
      body.innerHTML = `<div class="kp">
        <div class="t9" id="t9"></div>
        <div class="disp"><b id="numd"></b></div>
        <div class="keys">${KEYS.map(([k, l]) => `<button class="key" data-key="${k}" type="button"><b>${k}</b><small>${l || '&nbsp;'}</small></button>`).join('')}</div>
        <div class="krow"><button class="ghost" id="ksms" type="button">${I('sms', 'cyan', { shape: 'none' })}</button><button class="orbbtn big" id="kcall" type="button">${I('phone', 'chrome', { shape: 'none' })}</button><button class="ghost" id="kdel" type="button">${I('left', 'chrome', { shape: 'none' })}</button></div></div>`;
      updNum();
      $('#kcall').onclick = () => { if (!num) { const l = (log || J(ph('log'), []))[0]; if (l) { num = l.num; updNum(); } return; } haptic(); ph('call', num); };
      $('#ksms').onclick = () => { if (num) ph('sms', num); };
      const del = $('#kdel');
      let dt = null;
      del.onpointerdown = () => { dt = setTimeout(() => { num = ''; updNum(); dt = null; }, 600); };
      del.onpointerup = () => { if (dt) { clearTimeout(dt); dt = null; num = num.slice(0, -1); updNum(); } };
    } else if (tab === 'recents') {
      log = J(ph('log'), []) || [];
      const ICO = { 1: ['phone', 'green', 'entrant'], 2: ['phone', 'blue', 'sortant'], 3: ['phone', 'red', 'manqué'], 4: ['speaker', 'violet', 'messagerie'], 5: ['plus', 'red', 'refusé'], 6: ['lock', 'red', 'bloqué'] };
      body.innerHTML = log.map((c) => {
        const t = ICO[c.type] || ICO[1];
        const miss = c.type === 3 || c.type === 5;
        return `<div class="lrow" data-num="${esc(c.num)}" data-lid="${c.id}">${avatar(c.name || c.num, c.photo, c.num)}<span class="ltx"><b class="${miss ? 'miss' : ''}">${esc(c.name || c.num || 'Numéro masqué')}</b>
          <small>${I(t[0], t[1], { shape: 'none' })}${t[2]} · ${when(c.date)}${c.dur ? ' · ' + fmtDur(c.dur) : ''}</small></span>${c.num ? `<button class="lcall" data-call="${esc(c.num)}" type="button">${I('phone', 'chrome', { shape: 'none' })}</button>` : ''}</div>`;
      }).join('') || `<div class="empty">${I('clock', 'green')}Aucun appel récent.</div>`;
    } else if (tab === 'contacts') {
      body.innerHTML = `<label class="vsearch" style="margin-bottom:8px"><span class="pw">${I('search', 'cyan', { shape: 'none' })}</span><input id="cq" placeholder="Chercher un contact…" autocomplete="off"></label><div id="clist2"></div>`;
      const draw = () => {
        const q = norm($('#cq').value);
        let html = '', cur = '';
        for (const c of getContacts()) {
          if (q && !norm(c.name).includes(q) && !digits(c.num).includes(digits(q) || '~')) continue;
          const L = (norm(c.name)[0] || '#').toUpperCase();
          if (!q && L !== cur) { html += `<div class="letter2">${/[A-Z]/.test(L) ? L : '#'}</div>`; cur = L; }
          html += `<div class="lrow" data-num="${esc(c.num)}" data-cid="${c.cid}" data-star="${c.star ? 1 : 0}" data-name="${esc(c.name)}">${avatar(c.name, c.photo, c.num)}<span class="ltx"><b>${esc(c.name)}${c.star ? ' ⭐' : ''}</b><small>${esc(c.label ? c.label + ' · ' : '')}${esc(c.num)}</small></span><button class="lcall" data-call="${esc(c.num)}" type="button">${I('phone', 'chrome', { shape: 'none' })}</button></div>`;
        }
        $('#clist2').innerHTML = html || `<div class="empty">${I('contacts', 'orange')}Aucun contact.</div>`;
      };
      $('#cq').oninput = draw;
      draw();
    } else {
      const f = J(ph('favorites'), []) || [];
      body.innerHTML = f.length ? `<div class="favgrid">${f.map((c) => `<button class="fav2" data-call="${esc(c.num)}" type="button">${avatar(c.name, c.photo, c.num)}<b>${esc(c.name)}</b></button>`).join('')}</div>`
        : `<div class="empty">${I('star', 'amber')}Pas encore de favoris.<br>Appui long sur un contact → ⭐</div>`;
    }
  }
  function updNum() {
    const d = $('#numd');
    if (!d) return;
    d.textContent = num || ' ';
    const t = $('#t9');
    if (!num) { t.innerHTML = ''; return; }
    const dn = digits(num);
    const res = getContacts().filter((c) => digits(c.num).includes(dn) || (dn.length >= 2 && t9(c.name).split(' ').some((w) => w.startsWith(dn)))).slice(0, 8);
    t.innerHTML = res.map((c) => `<button data-fill="${esc(c.num)}" type="button">${avatar(c.name, c.photo, c.num)}${esc(c.name)}</button>`).join('');
  }

  // clavier : appui = chiffre + bip ; appui long sur 0 = « + »
  let lpTimer = null, lpDone = false;
  document.addEventListener('pointerdown', (e) => {
    const k = e.target.closest('[data-key]');
    if (k) {
      k.classList.add('hit'); lpDone = false;
      if (k.dataset.key === '0') lpTimer = setTimeout(() => { lpDone = true; num += '+'; updNum(); haptic(); }, 550);
      return;
    }
    const row = e.target.closest('.lrow');
    if (row && !e.target.closest('[data-call]')) lpTimer = setTimeout(() => { lpTimer = null; lpDone = true; haptic(); rowMenu(row); }, 550);
  });
  document.addEventListener('pointerup', (e) => {
    $$('.key.hit').forEach((k) => k.classList.remove('hit'));
    if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
    const k = e.target.closest('[data-key]');
    if (k && !lpDone) { num += k.dataset.key; ph('tone', k.dataset.key); haptic(); updNum(); }
  });
  document.addEventListener('pointercancel', () => { if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; } });

  document.addEventListener('click', (e) => {
    if (lpDone) { lpDone = false; if (!e.target.closest('[data-key]')) return; }
    const b = e.target.closest('[data-tab],[data-call],[data-fill],.lrow');
    if (!b) return;
    const d = b.dataset;
    if (d.tab) { tab = d.tab; renderDial(); return; }
    if (d.fill) { num = d.fill; updNum(); return; }
    if (d.call) { haptic(); ph('call', d.call); return; }
    if (b.classList.contains('lrow') && d.num) { num = d.num; tab = 'keypad'; renderDial(); }
  });

  function rowMenu(row) {
    const d = row.dataset;
    const veil = document.createElement('div'); veil.className = 'veil';
    const m = document.createElement('div'); m.className = 'menu';
    m.style.left = '14px'; m.style.right = '14px'; m.style.bottom = 'calc(var(--sb) + 30px)';
    const it = (k, g, c, l) => `<button data-mk="${k}" type="button">${I(g, c)}<span style="flex:1">${l}</span></button>`;
    m.innerHTML = `<div class="mh"><div><b>${esc(d.name || d.num)}</b><small>${esc(d.num)}</small></div></div>` + it('call', 'phone', 'green', 'Appeler') + it('sms', 'sms', 'orange', 'Envoyer un SMS') +
      (d.cid ? it('star', 'star', 'amber', d.star === '1' ? 'Retirer des favoris' : 'Ajouter aux favoris') + it('open', 'contacts', 'blue', 'Fiche du contact') : it('add', 'plus', 'green', 'Ajouter aux contacts')) +
      (d.lid ? it('del', 'trash', 'red', 'Supprimer de l\'historique') : '');
    const close = () => { veil.remove(); m.remove(); };
    veil.onclick = close;
    m.onclick = (e) => {
      const b = e.target.closest('[data-mk]'); if (!b) return; close();
      const k = b.dataset.mk;
      if (k === 'call') ph('call', d.num);
      if (k === 'sms') ph('sms', d.num);
      if (k === 'star') { ph('star', +d.cid, d.star !== '1'); contacts = null; }
      if (k === 'open') ph('openContact', +d.cid);
      if (k === 'add') ph('addContact', d.num);
      if (k === 'del') ph('deleteLog', +d.lid);
    };
    $('#layer').append(veil, m);
  }

  // =================================================================== ÉCRAN D'APPEL
  let cs = { calls: [] }, ticker = null, showPad = false, dtmf = '';
  const QUICK = ['Je ne peux pas répondre, je te rappelle 🐾', 'Je suis occupé, envoie-moi un message', 'J\'arrive !', 'Je te rappelle dans 5 minutes'];
  function mainCall() { return cs.calls.find((c) => c.state === 'ringing') || cs.calls.find((c) => c.state === 'active' || c.state === 'dialing') || cs.calls[0]; }
  function renderCall() {
    const c = mainCall();
    const ringing = cs.calls.find((x) => x.state === 'ringing');
    document.body.classList.toggle('ringing', !!ringing);
    if (!c) { $('#cstate').textContent = 'Appel terminé'; $('#cpanel').innerHTML = ''; return; }
    $('#bigav').innerHTML = avatar(c.name || c.num, c.photo, c.num);
    $('#cname').textContent = c.name || c.num || 'Numéro masqué';
    $('#cnum').textContent = c.name ? c.num : '';
    tick();
    const other = cs.calls.find((x) => x !== c && x.state !== 'ended');
    const w = $('#cwait');
    w.hidden = !other || !!ringing && other === ringing;
    if (other) w.innerHTML = `${avatar(other.name || other.num, other.photo, other.num)}<span>${esc(other.name || other.num)} · ${other.state === 'holding' ? 'en attente' : other.state}</span>${other.state === 'holding' ? `<button class="ab small green" data-c="swap" data-id="${other.id}" type="button">Reprendre</button>` : ''}`;
    const p = $('#cpanel');
    if (ringing && c === ringing) {
      p.innerHTML = `<div class="inrow">
        <span class="lbl2"><button class="orbbtn red" data-c="reject" type="button">${I('phone', 'chrome', { shape: 'none' }).replace('class="pi"', 'class="pi" style="transform:rotate(135deg)"')}</button>Refuser</span>
        <span class="lbl2"><button class="ghost" data-c="sms" type="button" style="width:62px;height:62px">${I('sms', 'cyan', { shape: 'none' })}</button>Message</span>
        <span class="lbl2"><button class="orbbtn accept" data-c="answer" type="button">${I('phone', 'chrome', { shape: 'none' })}</button>Répondre</span></div>`;
      return;
    }
    const route = cs.route || 1, routes = cs.routes || 5;
    const btn = (k, g, c2, l, on, dis) => `<button class="cbtn ${on ? 'on' : ''}" data-c="${k}" type="button" ${dis ? 'disabled' : ''}><i>${I(g, c2, { shape: 'none', muted: k === 'mute' && on })}</i><span>${l}</span></button>`;
    if (showPad) {
      p.innerHTML = `<div class="disp"><b>${esc(dtmf) || ' '}</b></div><div class="keys">${KEYS.map(([k, l]) => `<button class="key" data-dtmf="${k}" type="button"><b>${k}</b><small>${l || '&nbsp;'}</small></button>`).join('')}</div>
        <div class="krow"><span></span><button class="orbbtn red" data-c="hang" type="button">${I('phone', 'chrome', { shape: 'none' }).replace('class="pi"', 'class="pi" style="transform:rotate(135deg)"')}</button><button class="ghost" data-c="pad" type="button">${I('plus', 'chrome', { shape: 'none' }).replace('class="pi"', 'class="pi" style="transform:rotate(45deg)"')}</button></div>`;
      return;
    }
    p.innerHTML = `<div class="cgrid">
      ${btn('mute', 'speaker', 'pink', 'Muet', cs.muted)}
      ${btn('pad', 'apps', 'cyan', 'Clavier', false)}
      ${btn('spk', 'speaker', 'amber', 'Haut-parleur', route === 8)}
      ${btn('bt', 'bt', 'blue', 'Bluetooth', route === 2, !(routes & 2))}
      ${btn('hold', 'pause', 'violet', c.state === 'holding' ? 'Reprendre' : 'Attente', c.state === 'holding', !c.canHold)}
      ${btn('min', 'home', 'green', 'Réduire', false)}
    </div>
    <button class="orbbtn red big" data-c="hang" type="button">${I('phone', 'chrome', { shape: 'none' }).replace('class="pi"', 'class="pi" style="transform:rotate(135deg)"')}</button>`;
  }
  function tick() {
    const c = mainCall();
    if (!c) return;
    const st = { ringing: 'Appel entrant…', dialing: 'Appel en cours…', holding: 'En attente', ended: 'Terminé' }[c.state];
    $('#cstate').textContent = st || (c.since ? fmtDur((Date.now() - c.since) / 1000) : '00:00');
  }
  function loadCalls() { cs = J(cl('calls'), cs) || cs; renderCall(); }
  document.addEventListener('click', (e) => {
    if (mode !== 'call') return;
    const b = e.target.closest('[data-c],[data-dtmf],[data-qr]');
    if (!b) return;
    const c = mainCall();
    haptic();
    if (b.dataset.dtmf) { dtmf += b.dataset.dtmf; cl('dtmf', b.dataset.dtmf); renderCall(); return; }
    if (b.dataset.qr != null) { $('#layer').innerHTML = ''; cl('rejectSms', c.id, b.dataset.qr); return; }
    switch (b.dataset.c) {
      case 'answer': cl('answer'); break;
      case 'reject': case 'hang': cl('hangup', c ? c.id : 0); break;
      case 'sms': {
        const veil = document.createElement('div'); veil.className = 'veil';
        const m = document.createElement('div'); m.className = 'menu';
        m.style.left = '14px'; m.style.right = '14px'; m.style.bottom = 'calc(var(--sb) + 30px)';
        m.innerHTML = `<div class="msec">Refuser avec un message</div><div class="qr">${QUICK.map((q) => `<button data-qr="${esc(q)}" type="button">${esc(q)}</button>`).join('')}</div>`;
        veil.onclick = () => { veil.remove(); m.remove(); };
        $('#layer').append(veil, m);
        break;
      }
      case 'mute': cl('mute', !cs.muted); cs.muted = !cs.muted; break;
      case 'spk': cl('route', cs.route === 8 ? 5 : 8); break;
      case 'bt': cl('route', cs.route === 2 ? 5 : 2); break;
      case 'hold': cl('hold', c.id, c.state !== 'holding'); break;
      case 'swap': cl('hold', +b.dataset.id, false); break;
      case 'pad': showPad = !showPad; break;
      case 'min': cl('close'); break;
    }
    renderCall();
  });

  // =================================================================== pont natif
  window.PhoneUI = {
    on(ev, data) {
      if (ev === 'calls') { cs = J(data, cs) || cs; if (mode === 'call') renderCall(); }
      else if (ev === 'resume') { if (mode === 'dial') { contacts = null; renderDial(); } else loadCalls(); }
      else if (ev === 'start') { const s = J(data, {}); if (s.number) { num = s.number; tab = 'keypad'; } if (s.tab) tab = s.tab; if (mode === 'dial') renderDial(); }
    },
    back() {
      if ($('#layer').children.length) { $('#layer').innerHTML = ''; return true; }
      if (mode === 'call') { if (showPad) { showPad = false; renderCall(); return true; } cl('close'); return true; }
      if (tab !== 'keypad') { tab = 'keypad'; renderDial(); return true; }
      return false;
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };

  $('#plogo').innerHTML = I('phone', 'green');
  if (mode === 'call') {
    $('#call').hidden = false;
    loadCalls();
    ticker = setInterval(tick, 1000);
    cl('dismissKeyguard');
  } else {
    $('#dial').hidden = false;
    const s = J(ph('start'), {}) || {};
    if (s.number) num = s.number;
    if (s.tab) tab = s.tab;
    renderDial();
  }

  // =================================================================== aperçu (hors APK)
  function mockP() {
    const now = Date.now();
    return { isDefault: () => false, inCall: () => true, contacts: () => JSON.stringify([{ cid: 1, name: 'Max', num: '+33600000001', photo: '', star: true, label: 'Mobile' }, { cid: 2, name: 'Léo', num: '+33600000002', photo: '', star: true, label: 'Mobile' }, { cid: 3, name: 'Maman', num: '+33600000003', photo: '', star: false, label: 'Domicile' }]),
      favorites: () => JSON.stringify([{ cid: 1, name: 'Max', num: '+33600000001', photo: '' }, { cid: 2, name: 'Léo', num: '+33600000002', photo: '' }]),
      log: () => JSON.stringify([{ id: 1, num: '+33600000001', name: 'Max', type: 3, date: now - 6e5, dur: 0 }, { id: 2, num: '+33600000003', name: 'Maman', type: 2, date: now - 9e6, dur: 312 }, { id: 3, num: '0235000000', name: '', type: 1, date: now - 9e7, dur: 45 }]),
      start: () => '', haptic() {}, tone() {}, call() {} };
  }
  function mockC() {
    const now = Date.now();
    return { calls: () => JSON.stringify({ calls: [{ id: 1, num: '+33600000001', name: 'Max', photo: '', state: new URLSearchParams(location.search).get('st') || 'active', since: now - 83000, canHold: true }], muted: false, route: 1, routes: 15 }) };
  }
})();
