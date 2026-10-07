/* PupSMS — interface. Bulles orange = moi, bleues = reçus. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon, PAL = PupIcons.PAL;
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const norm = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Sms;
  const S = window.Sms || mock();
  const call = (fn, ...a) => { try { return S[fn] ? S[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const XSVG = '<svg viewBox="0 0 20 20"><path d="M4 4L16 16M16 4L4 16" stroke="#fff" stroke-width="3.4" stroke-linecap="round"/></svg>';
  const COLS = ['pink', 'cyan', 'violet', 'amber', 'green', 'red', 'blue', 'orange'];
  const colorOf = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return PAL[COLS[h % COLS.length]][1]; };
  const haptic = () => call('haptic');

  let view = 'list', convos = [], thread = null, attach = [], newTo = [];

  // ------------------------------------------------------------------ outils
  function avatar(name, photo, key) {
    const ini = (String(name || '?').replace(/[^\p{L}\p{N}]/gu, '')[0] || '#').toUpperCase();
    const img = photo && !MOCK ? `<img src="https://pupsms.local/avatar?u=${encodeURIComponent(photo)}" alt="" onerror="this.remove()">` : '';
    return `<span class="av" style="--c:${colorOf(key || name)}">${esc(ini)}${img}</span>`;
  }
  function when(ms) {
    const d = new Date(ms), now = new Date();
    if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return 'hier';
    if (now - d < 6 * 864e5) return d.toLocaleDateString('fr-FR', { weekday: 'short' });
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: d.getFullYear() === now.getFullYear() ? undefined : '2-digit' });
  }
  const fmtSize = (b) => b > 1e6 ? (b / 1e6).toFixed(1) + ' Mo' : Math.max(1, Math.round(b / 1e3)) + ' Ko';
  function show(v) {
    view = v;
    ['list', 'thread', 'new'].forEach((x) => { $('#v' + x).hidden = x !== v; });
    $('#composer').hidden = v === 'list';
    if (v !== 'thread') call('closed');
  }
  function toast(msg) { call('toast', msg); }

  // ------------------------------------------------------------------ liste des conversations
  function loadConvos() {
    convos = J(call('convos'), []) || [];
    renderList();
  }
  function renderList() {
    const q = norm($('#lq').value);
    const def = call('isDefault');
    const b = $('#defbanner');
    b.hidden = !!def;
    if (!def) b.innerHTML = `<div>🐾 Pour <b>recevoir</b> les SMS et MMS ici, PupSMS doit être ton appli SMS par défaut.</div><button class="ab wide" id="mkdef" type="button">Devenir mon appli SMS</button>`;
    const mk = $('#mkdef'); if (mk) mk.onclick = () => call('askDefault');
    const list = convos.filter((c) => !q || norm(c.names.join(' ')).includes(q) || c.addrs.join(' ').includes(q) || norm(c.snippet).includes(q));
    $('#clist').innerHTML = list.map((c) => `<button class="conv ${c.unread ? 'unread' : ''}" data-t="${c.id}" type="button">${avatar(c.names[0], c.photo, c.addrs[0])}
      <span class="ctx"><span class="ctop"><b>${esc(c.names.join(', ') || 'Inconnu')}</b><small>${when(c.date)}</small></span><span class="csnip">${esc(c.snippet || '📎 MMS')}</span></span>${c.unread ? '<span class="udot"></span>' : ''}</button>`).join('') ||
      `<div class="empty">${I('sms', 'orange')}${call('canRead') === false ? 'Autorise PupSMS à lire tes messages 🐾' : 'Aucune conversation pour l\'instant.'}</div>`;
  }

  // ------------------------------------------------------------------ fil de discussion
  function openThread(t) {
    thread = t;
    show('thread');
    $('#tname').textContent = t.names.join(', ') || t.addrs.join(', ');
    $('#tnum').textContent = t.addrs.join(' · ');
    $('#tav').innerHTML = avatar(t.names[0], t.photo, t.addrs[0]);
    call('opened', t.addrs[0] || '');
    loadMsgs(true);
    updateCount();
  }
  function loadMsgs(scroll) {
    if (!thread) return;
    const box = $('#msgs');
    const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    const msgs = J(call('messages', thread.id), []) || [];
    let html = '', day = '';
    const group = thread.addrs.length > 1;
    for (const m of msgs) {
      const d = new Date(m.date).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
      if (d !== day) { html += `<div class="dayline">${esc(d)}</div>`; day = d; }
      const who = m.me ? 'me' : 'them';
      let inner = '';
      if (group && !m.me && m.addr) inner += `<span class="from">${esc(J(call('contact', m.addr), {}).name || m.addr)}</span>`;
      for (const p of (m.parts || [])) {
        const src = MOCK ? p.src : `https://pupsms.local/part?id=${p.id}&ct=${encodeURIComponent(p.ct)}`;
        if (p.ct.startsWith('image/')) inner += `<img class="att" src="${src}" data-view="img" alt="" loading="lazy">`;
        else if (p.ct.startsWith('video/')) inner += `<video class="att" src="${src}" controls preload="metadata" playsinline></video>`;
        else inner += `<button class="fchip" data-part="${p.id}" data-ct="${esc(p.ct)}" data-name="${esc(p.name)}" type="button">${I(p.ct.includes('pdf') ? 'news' : p.ct.startsWith('audio/') ? 'music' : 'folder', p.ct.includes('pdf') ? 'red' : 'amber')}<span><b>${esc(p.name)}</b><small>${esc(p.ct)} · toucher pour ouvrir</small></span></button>`;
      }
      if (m.body) inner += esc(m.body);
      html += `<div class="bub ${who} ${m.st === 'fail' ? 'fail' : ''}" data-k="${m.k}" data-id="${m.id}">${inner}</div>
        <div class="meta ${who}">${new Date(m.date).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })}${m.me ? (m.st === 'fail' ? ` · <span class="err" data-retry="${m.k}:${m.id}">⚠ échec — toucher pour renvoyer</span>` : m.st === 'sending' ? ' · ⏳ envoi…' : ' · ✓') : ''}${m.k === 'm' ? ' · MMS' : ''}</div>`;
    }
    box.innerHTML = html || `<div class="empty">${I('sms', 'cyan')}Dis bonjour 🐾</div>`;
    box._msgs = msgs;
    if (scroll || atBottom) box.scrollTop = box.scrollHeight;
  }

  // ------------------------------------------------------------------ composer
  function isMms() { return attach.length > 0 || (view === 'thread' ? thread && thread.addrs.length > 1 : newTo.length > 1); }
  function updateCount() {
    const t = $('#ctext').value;
    const uni = /[^\u0000-\u007f€£¥èéùìòÇØøÅåÄÖÑÜßÉæÆ]/.test(t);
    const per = uni ? 70 : 160, multi = uni ? 67 : 153;
    const n = t.length <= per ? 1 : Math.ceil(t.length / multi);
    $('#ccount').textContent = isMms() ? 'MMS' : t.length ? `SMS ${n} · ${t.length}/${n === 1 ? per : n * multi}` : '';
    $('#csend').disabled = !t.trim() && !attach.length;
  }
  function renderAtts() {
    $('#atts').innerHTML = attach.map((a, i) => {
      const src = MOCK ? '' : `https://pupsms.local/att?k=${encodeURIComponent(a.key)}`;
      const media = a.mime.startsWith('image/') && src ? `<img src="${src}" alt="">` : a.mime.startsWith('video/') && src ? `<video src="${src}" muted></video>` : I(a.mime.includes('pdf') ? 'news' : 'folder', a.mime.includes('pdf') ? 'red' : 'amber', { shape: 'none' });
      return `<span class="att2">${media}<small>${esc(a.name)} · ${fmtSize(a.size)}</small><button class="rm" data-rm="${i}" type="button">✕</button></span>`;
    }).join('');
    updateCount();
  }
  const ta = $('#ctext');
  ta.addEventListener('input', () => { ta.style.height = 'auto'; ta.style.height = Math.min(140, ta.scrollHeight) + 'px'; updateCount(); });
  $('#csend').onclick = () => {
    const text = ta.value.trim();
    if (!text && !attach.length) return;
    let to;
    if (view === 'thread' && thread) to = thread.addrs;
    else if (view === 'new') {
      const typed = $('#nq').value.replace(/[^0-9+]/g, '');
      if (typed.length >= 3) newTo.push({ num: typed, name: typed });
      to = newTo.map((x) => x.num);
      if (!to.length) { toast('Choisis au moins un destinataire'); return; }
    }
    haptic();
    const max = call('maxMms') || 614400;
    const heavy = attach.filter((a) => !a.mime.startsWith('image/') && a.size > max);
    if (heavy.length) { toast(`« ${heavy[0].name} » dépasse la limite MMS de ton opérateur (${Math.round(max / 1024)} Ko)`); return; }
    call('send', JSON.stringify(to), text, JSON.stringify(attach));
    if (attach.length) toast('📤 Envoi du MMS…');
    ta.value = ''; ta.style.height = 'auto'; attach = []; renderAtts();
    if (view === 'new') {
      const id = call('threadFor', JSON.stringify(to));
      const names = newTo.map((x) => x.name);
      newTo = []; $('#nq').value = '';
      openThread({ id, addrs: to, names, photo: '' });
    } else setTimeout(() => loadMsgs(true), 350);
  };
  $('#cplus').onclick = () => attachMenu();
  function attachMenu() {
    const veil = document.createElement('div'); veil.className = 'veil';
    const m = document.createElement('div'); m.className = 'menu';
    m.style.left = '12px'; m.style.bottom = 'calc(var(--sb) + 80px)';
    const it = (k, g, c, l) => `<button data-pk="${k}" type="button">${I(g, c)}<span style="flex:1">${l}</span></button>`;
    m.innerHTML = `<div class="msec">Joindre au message</div>${it('image', 'gallery', 'violet', 'Photo')}${it('video', 'video', 'red', 'Vidéo')}${it('pdf', 'news', 'red', 'PDF')}${it('any', 'folder', 'amber', 'Autre fichier')}`;
    const close = () => { veil.remove(); m.remove(); };
    veil.onclick = close;
    m.onclick = (e) => { const b = e.target.closest('[data-pk]'); if (b) { close(); call('pick', b.dataset.pk); } };
    $('#layer').append(veil, m);
  }

  // ------------------------------------------------------------------ nouveau message
  function openNew(prefAddr) {
    newTo = [];
    show('new');
    $('#nq').value = prefAddr || '';
    drawSugg();
    setTimeout(() => $('#nq').focus(), 100);
  }
  function drawSugg() {
    const q = $('#nq').value.trim();
    $('#chips').innerHTML = newTo.map((x, i) => `<span class="chip2">${esc(x.name)}<button data-unpick="${i}" type="button">✕</button></span>`).join('');
    const res = J(call('searchContacts', q), []) || [];
    let html = '';
    const digits = q.replace(/[^0-9+]/g, '');
    if (digits.length >= 3) html += `<button class="conv" data-pickn="${esc(digits)}" data-name="${esc(digits)}" type="button">${avatar('#', '', digits)}<span class="ctx"><span class="ctop"><b>Envoyer à ${esc(digits)}</b></span><span class="csnip">numéro saisi</span></span></button>`;
    html += res.map((c) => `<button class="conv" data-pickn="${esc(c.num)}" data-name="${esc(c.name)}" type="button">${avatar(c.name, c.photo, c.num)}<span class="ctx"><span class="ctop"><b>${esc(c.name)}</b></span><span class="csnip">${esc(c.num)}</span></span></button>`).join('');
    $('#nsugg').innerHTML = html || `<div class="empty">${I('contacts', 'orange')}Tape un nom ou un numéro</div>`;
  }
  $('#nq').addEventListener('input', drawSugg);

  // ------------------------------------------------------------------ clics
  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-t],[data-pickn],[data-unpick],[data-rm],[data-part],[data-view],[data-retry]');
    if (!b) return;
    const d = b.dataset;
    if (d.t) { const c = convos.find((x) => String(x.id) === d.t); if (c) { haptic(); openThread(c); } return; }
    if (d.pickn) { if (!newTo.some((x) => x.num === d.pickn)) newTo.push({ num: d.pickn, name: d.name }); $('#nq').value = ''; drawSugg(); updateCount(); return; }
    if (d.unpick != null) { newTo.splice(+d.unpick, 1); drawSugg(); updateCount(); return; }
    if (d.rm != null) { attach.splice(+d.rm, 1); renderAtts(); return; }
    if (d.part) { call('openPart', +d.part, d.ct, d.name); return; }
    if (d.view === 'img') {
      const v = document.createElement('div'); v.className = 'viewer';
      v.innerHTML = `<img src="${b.src}" alt=""><button class="xb" type="button">${XSVG}</button>`;
      v.onclick = () => v.remove();
      document.body.append(v);
      return;
    }
    if (d.retry) {
      const [k, id] = d.retry.split(':');
      const m = ($('#msgs')._msgs || []).find((x) => x.k === k && String(x.id) === id);
      if (m) { call('retry', k, +id, thread.addrs[0], m.body || ''); setTimeout(() => loadMsgs(true), 500); }
    }
  });
  // appui long sur une conversation → supprimer
  let lp = null;
  $('#clist').addEventListener('pointerdown', (e) => {
    const b = e.target.closest('[data-t]');
    if (!b) return;
    lp = setTimeout(() => {
      lp = null; haptic();
      const c = convos.find((x) => String(x.id) === b.dataset.t);
      if (c && confirm(`Supprimer la conversation avec ${c.names.join(', ')} ?`)) call('deleteThread', c.id);
    }, 600);
  });
  ['pointerup', 'pointercancel', 'pointermove'].forEach((ev) => $('#clist').addEventListener(ev, () => { if (lp) { clearTimeout(lp); lp = null; } }));

  $('#fab').onclick = () => { haptic(); openNew(''); };
  $('#tback').onclick = () => { thread = null; show('list'); loadConvos(); };
  $('#nback').onclick = () => { show('list'); };
  $('#tcall').onclick = () => { if (thread) call('dial', thread.addrs[0]); };
  $('#lq').addEventListener('input', renderList);

  // ------------------------------------------------------------------ pont natif
  function handleStart(st) {
    if (!st) return;
    if (st.attach && st.attach.length) { attach = attach.concat(st.attach); renderAtts(); }
    if (st.body) { ta.value = st.body; updateCount(); }
    if (st.addr) {
      const id = call('threadFor', JSON.stringify([st.addr]));
      const ct = J(call('contact', st.addr), {});
      openThread({ id, addrs: [st.addr], names: [ct.name || st.addr], photo: ct.photo || '' });
    } else if ((st.attach && st.attach.length) || st.body) openNew('');
  }
  window.SmsUI = {
    on(ev, data) {
      if (ev === 'changed' || ev === 'resume') { if (view === 'thread') loadMsgs(false); loadConvos(); }
      else if (ev === 'attach') { attach = attach.concat(J(data, [])); renderAtts(); }
      else if (ev === 'sent') { if (view === 'thread') loadMsgs(true); }
      else if (ev === 'senderr') { toast(data); }
      else if (ev === 'open') handleStart(J(data, null));
    },
    back() {
      if ($('#layer').children.length) { $('#layer').innerHTML = ''; return true; }
      if ($('.viewer')) { $('.viewer').remove(); return true; }
      if (view !== 'list') { thread = null; show('list'); loadConvos(); return true; }
      return false;
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };

  $('#slogo').innerHTML = I('sms', 'orange');
  $('#lsico').innerHTML = I('search', 'cyan', { shape: 'none' });
  $('#fab').innerHTML = I('plus', 'chrome', { shape: 'none' });
  $('#tback').innerHTML = I('left', 'chrome', { shape: 'none' });
  $('#nback').innerHTML = I('left', 'chrome', { shape: 'none' });
  $('#tcall').innerHTML = I('phone', 'green', { shape: 'none' });
  $('#cplus').innerHTML = I('plus', 'chrome', { shape: 'none' });
  $('#csend').innerHTML = I('paw', 'chrome', { shape: 'none' });
  show('list');
  loadConvos();
  handleStart(J(call('start'), null));
  updateCount();

  // ------------------------------------------------------------------ aperçu navigateur (hors APK)
  function mock() {
    const now = Date.now();
    const img = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 120"><defs><linearGradient id="g" x1="0" x2="1"><stop offset="0" stop-color="#ff3fa4"/><stop offset="1" stop-color="#29e6ff"/></linearGradient></defs><rect width="160" height="120" fill="url(#g)"/><circle cx="110" cy="40" r="18" fill="#fff" opacity=".6"/></svg>');
    const C = [
      { id: 1, date: now - 6e5, count: 8, addrs: ['+33600000001'], names: ['Max'], photo: '', snippet: 'On se retrouve à 22h devant le club ?', unread: true },
      { id: 2, date: now - 3.6e6, count: 3, addrs: ['+33600000002'], names: ['Léo 🐾'], photo: '', snippet: '📷 Photo', unread: false },
      { id: 3, date: now - 9e7, count: 12, addrs: ['+33600000003', '+33600000004'], names: ['Sam', 'Noé'], photo: '', snippet: 'Les billets sont pris !', unread: false },
      { id: 4, date: now - 3e8, count: 2, addrs: ['36179'], names: ['36179'], photo: '', snippet: 'Votre code : 482913', unread: false },
    ];
    const M = [
      { k: 's', id: 1, me: false, date: now - 7.2e6, body: 'Hey ! Tu viens ce soir ? 🔥', st: 'ok' },
      { k: 's', id: 2, me: true, date: now - 7e6, body: 'Grave ! Je finis un truc et j\'arrive', st: 'ok' },
      { k: 'm', id: 3, me: false, date: now - 1.2e6, body: 'Regarde le décor 😍', st: 'ok', parts: [{ id: 9, ct: 'image/jpeg', name: 'photo.jpg', src: img }] },
      { k: 'm', id: 4, me: true, date: now - 9e5, body: '', st: 'ok', parts: [{ id: 10, ct: 'application/pdf', name: 'billets-soiree.pdf' }] },
      { k: 's', id: 5, me: false, date: now - 6e5, body: 'On se retrouve à 22h devant le club ?', st: 'ok' },
      { k: 's', id: 6, me: true, date: now - 3e5, body: 'Parfait, à tout à l\'heure 🐾', st: 'sending' },
    ];
    return { convos: () => JSON.stringify(C), messages: () => JSON.stringify(M), isDefault: () => false, canRead: () => true, contact: () => '{}', searchContacts: () => JSON.stringify([{ name: 'Max', num: '+33600000001', photo: '' }, { name: 'Léo 🐾', num: '+33600000002', photo: '' }]),
      threadFor: () => 5, maxMms: () => 614400, start: () => '', opened() {}, closed() {}, haptic() {}, toast() {}, send() {}, pick() {} };
  }
})();
