/* PupGalery / PupVidéo — interface. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon;
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Media;
  const M = window.Media || mock();
  const call = (fn, ...a) => { try { return M[fn] ? M[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const XSVG = '<svg viewBox="0 0 20 20"><path d="M4 4L16 16M16 4L4 16" stroke="#fff" stroke-width="3.4" stroke-linecap="round"/></svg>';
  const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];

  const mode = call('mode') || new URLSearchParams(location.search).get('mode') || 'gallery';
  const isVid = mode === 'video', isPick = mode === 'pick';
  const pickMulti = isPick && !!call('pickMultiple');
  const pickType = isPick ? (call('pickType') || '*/*') : '*/*';
  const root = document.documentElement.style;
  if (isVid) { root.setProperty('--acc', '#ff4d5e'); root.setProperty('--acc2', '#29e6ff'); }
  else { root.setProperty('--acc', '#ff3fa4'); root.setProperty('--acc2', '#9b5cff'); }

  let items = [], favs = new Set(J(call('get', 'favs'), [])), resume = J(call('get', 'resume'), {}) || {};
  let tab = isVid ? 'all' : 'photos', album = null, selecting = false, selected = new Set(), view = [];
  const key = (it) => it.k + ':' + it.id;
  const th = (it) => MOCK ? it._th : `https://pupmedia.local/th?k=${it.k}&id=${it.id}`;
  const full = (it) => it.ext ? `https://pupmedia.local/ext?u=${encodeURIComponent(it.uri)}` : MOCK ? it._th : `https://pupmedia.local/img?id=${it.id}`;
  const saveFavs = () => call('set', 'favs', JSON.stringify([...favs]));
  const saveResume = () => call('set', 'resume', JSON.stringify(resume));
  const haptic = () => call('haptic');
  const fmtDur = (ms) => { const s = Math.max(0, Math.round((ms || 0) / 1000)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };
  const fmtSize = (b) => b > 1e9 ? (b / 1e9).toFixed(1) + ' Go' : b > 1e6 ? (b / 1e6).toFixed(1) + ' Mo' : Math.round(b / 1e3) + ' Ko';
  const fmtDate = (t) => new Date(t).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' }) + ' · ' + new Date(t).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });

  // ------------------------------------------------------------------ en-tête
  const TABS = isVid ? [['all', 'Toutes'], ['folders', 'Dossiers'], ['favs', 'Favoris']]
    : isPick ? [['photos', 'Tout'], ['albums', 'Albums']]
      : [['photos', 'Photos'], ['albums', 'Albums'], ['videos', 'Vidéos'], ['favs', 'Favoris']];
  $('#mlogo').innerHTML = I(isVid ? 'film' : 'gallery', isVid ? 'red' : 'violet');
  $('#mname').innerHTML = isVid ? 'PUP<span>VIDÉO</span>' : isPick ? 'CHOISIR <span>' + (pickMulti ? 'DES' : 'UNE') + '</span>' : 'PUP<span>GALERY</span>';
  $('#tabs').innerHTML = TABS.map(([k, l]) => `<button data-tab="${k}" class="${k === tab ? 'on' : ''}" type="button">${l}</button>`).join('');
  $('#bback').innerHTML = I('left', 'chrome', { shape: 'none' });
  $('#bslide').innerHTML = I('slides', 'pink', { shape: 'none' });
  $('#bsel').innerHTML = I('check', 'cyan', { shape: 'none' });
  $('#bslide').hidden = isVid || isPick;
  [$('#bback'), $('#bslide'), $('#bsel')].forEach((b) => { b.style.width = '46px'; b.style.height = '46px'; b.style.display = b.hidden ? 'none' : 'grid'; b.style.placeItems = 'center'; });
  $('#bback').style.display = 'none';
  const fixTop = () => root.setProperty('--toph', $('#top').offsetHeight + 'px');

  // ------------------------------------------------------------------ données
  function load() {
    const perm = call('perm') || 'full';
    let list = perm === 'none' ? [] : (J(call('list'), []) || []);
    if (isVid) list = list.filter((x) => x.k === 'v');
    if (isPick && /^image\//.test(pickType)) list = list.filter((x) => x.k === 'i');
    if (isPick && /^video\//.test(pickType)) list = list.filter((x) => x.k === 'v');
    list.sort((a, b) => b.t - a.t);
    items = list;
    return perm;
  }
  function filtered() {
    let l = items;
    if (album) l = l.filter((x) => x.bid === album.bid);
    else if (tab === 'videos') l = l.filter((x) => x.k === 'v');
    else if (tab === 'favs') l = l.filter((x) => favs.has(key(x)));
    return l;
  }

  // ------------------------------------------------------------------ rendu liste
  const list = $('#list');
  let io = null;
  function tileHTML(it, i) {
    const s = selected.has(key(it));
    return `<button class="tile ${s ? 'sel' : ''}" data-i="${i}" type="button"><img src="${th(it)}" loading="lazy" decoding="async" alt="" draggable="false">` +
      (it.k === 'v' ? `<span class="dur">${I('play', 'chrome', { shape: 'none' })}${fmtDur(it.d)}</span>` : '') +
      (favs.has(key(it)) ? `<span class="hrt">${I('heart', 'red', { shape: 'none' })}</span>` : '') +
      (selecting ? '<span class="ck"></span>' : '') + '</button>';
  }
  function vcardHTML(it, i, withProg) {
    const r = resume[key(it)];
    const pct = r && r.dur ? Math.min(100, r.pos / r.dur * 100) : 0;
    return `<button class="vcard ${selected.has(key(it)) ? 'sel' : ''}" data-i="${i}" type="button"><span class="vt"><img src="${th(it)}" loading="lazy" decoding="async" alt="">
      <span class="po">${I('play', 'chrome', { shape: 'orb' })}</span><span class="dur">${fmtDur(it.d)}</span>${(withProg || pct) && pct ? `<span class="prog"><i style="width:${pct}%"></i></span>` : ''}</span>
      <b>${esc(it.n)}</b><small>${fmtSize(it.s)} · ${new Date(it.t).toLocaleDateString('fr-FR')}</small></button>`;
  }
  function albumsHTML(src) {
    const map = new Map();
    for (const it of src) { if (!map.has(it.bid)) map.set(it.bid, { bid: it.bid, name: it.b || 'Sans nom', list: [] }); map.get(it.bid).list.push(it); }
    const al = [...map.values()].sort((a, b) => b.list.length - a.list.length);
    return `<div class="albums">${al.map((a, i) => `<button class="album" data-album="${esc(a.bid)}" type="button"><span class="stack">${[2, 1, 0].map((k) => a.list[k] ? `<span class="pol"><img src="${th(a.list[k])}" loading="lazy" alt=""></span>` : '<span class="pol"></span>').join('')}<span class="tape"></span></span><b>${esc(a.name)}</b><small>${a.list.length} ${isVid ? 'vidéo' : 'élément'}${a.list.length > 1 ? 's' : ''}</small></button>`).join('')}</div>`;
  }
  function timelineHTML(l) {
    const groups = [];
    let cur = null;
    l.forEach((it, i) => {
      const d = new Date(it.t), k = d.getFullYear() + '-' + d.getMonth();
      if (!cur || cur.k !== k) { cur = { k, label: MONTHS[d.getMonth()] + ' ' + d.getFullYear(), from: i, n: 0 }; groups.push(cur); }
      cur.n++;
    });
    const w = (innerWidth - 20 - 12) / 4 + 4;
    return groups.map((g) => `<section class="month" data-from="${g.from}" data-n="${g.n}" style="contain-intrinsic-size:auto ${Math.ceil(g.n / 4) * w + 44}px">
      <div class="mh2">${I('paw', isVid ? 'red' : 'pink', { shape: 'none' })}<span class="chrome">${g.label.toUpperCase()}</span><small>${g.n}</small></div><div class="tg" style="min-height:${Math.ceil(g.n / 4) * w}px"></div></section>`).join('');
  }
  function fillMonth(sec) {
    if (sec.dataset.done) return;
    sec.dataset.done = '1';
    const from = +sec.dataset.from, n = +sec.dataset.n;
    let html = '';
    for (let i = from; i < from + n; i++) html += tileHTML(view[i], i);
    const g = $('.tg', sec);
    g.innerHTML = html;
    g.style.minHeight = '';
  }

  function render(perm) {
    if (io) io.disconnect();
    $('#bback').style.display = album ? 'grid' : 'none';
    $('#tabs').hidden = !!album;
    $('#bsel').classList.toggle('on', selecting);
    if (perm === 'none') {
      list.innerHTML = `<div class="permcard">${I(isVid ? 'film' : 'gallery', isVid ? 'red' : 'violet')}<b class="chrome">Accès à tes ${isVid ? 'vidéos' : 'photos et vidéos'}</b>
        <p>${isVid ? 'PupVidéo' : 'PupGalery'} lit tes fichiers directement sur le téléphone. Rien ne quitte ton appareil.</p>
        <button class="ab wide" data-act="perm" type="button">Autoriser</button><button class="ab small glass" data-act="permset" type="button">Ouvrir les réglages</button></div>`;
      $('#msub').textContent = 'autorisation requise';
      fixTop();
      return;
    }
    view = filtered();
    const n = view.length;
    $('#msub').textContent = album ? album.name + ' · ' + n : n + (isVid ? ' vidéo' : ' élément') + (n > 1 ? 's' : '') + (selecting ? ' · ' + selected.size + ' choisi(s)' : '');
    let html = perm === 'partial' ? `<div class="limited">${I('lock', 'amber', { shape: 'none' }).replace('class="pi"', 'class="pi" style="width:26px;height:26px"')}<span>Accès limité aux éléments que tu as choisis.</span><button class="ab small amber" data-act="perm" type="button">Tout autoriser</button></div>` : '';
    if (!album && (tab === 'albums' || tab === 'folders')) html += albumsHTML(items);
    else if (isVid) {
      if (tab === 'all' && !album) {
        const rs = items.filter((it) => resume[key(it)] && resume[key(it)].pos > 5000).sort((a, b) => resume[key(b)].t - resume[key(a)].t).slice(0, 10);
        if (rs.length) html += `<div class="mh2">${I('play', 'red', { shape: 'orb' })}<span class="chrome">À REPRENDRE</span></div><div class="hrow">${rs.map((it) => vcardHTML(it, view.indexOf(it), true)).join('')}</div><div class="mh2">${I('film', 'cyan', { shape: 'orb' })}<span class="chrome">TOUTES</span></div>`;
      }
      html += `<div class="vgrid">${view.map((it, i) => vcardHTML(it, i)).join('')}</div>`;
    } else html += timelineHTML(view);
    if (!n && !(tab === 'albums' || tab === 'folders')) html += `<div class="empty">${I(tab === 'favs' ? 'heart' : 'gallery', tab === 'favs' ? 'red' : 'violet')}${tab === 'favs' ? 'Aucun favori. Ouvre une photo et touche ♥.' : 'Rien ici pour l\'instant.'}</div>`;
    list.innerHTML = html;
    io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) fillMonth(e.target); }), { root: list, rootMargin: '1400px 0px' });
    $$('.month', list).forEach((s) => io.observe(s));
    fixTop();
    renderSelbar();
  }
  function refreshTiles() {
    $$('.month[data-done]', list).forEach((s) => { delete s.dataset.done; fillMonth(s); });
    $$('.vcard', list).forEach((c) => c.classList.toggle('sel', selected.has(key(view[+c.dataset.i]))));
    $('#msub').textContent = (album ? album.name + ' · ' : '') + view.length + (selecting ? ' · ' + selected.size + ' choisi(s)' : '');
    renderSelbar();
  }

  // ------------------------------------------------------------------ sélection
  function renderSelbar() {
    const bar = $('#selbar');
    if (!selecting) { bar.hidden = true; return; }
    bar.hidden = false;
    const n = selected.size;
    bar.innerHTML = isPick
      ? `<b>${n} choisi${n > 1 ? 's' : ''}</b><button class="ab glass" data-s="cancel" type="button">Annuler</button><button class="ab green" data-s="pick" type="button" ${n ? '' : 'disabled'}>Choisir</button>`
      : `<b>${n} sélection${n > 1 ? 's' : ''}</b><button class="ibtn" data-s="share" type="button">${I('share', 'green')}</button><button class="ibtn" data-s="fav" type="button">${I('heart', 'red')}</button><button class="ibtn" data-s="trash" type="button">${I('trash', 'red')}</button><button class="ibtn" data-s="cancel" type="button">${I('plus', 'chrome', { shape: 'none' }).replace('class="pi"', 'class="pi" style="transform:rotate(45deg)"')}</button>`;
  }
  const selItems = () => items.filter((it) => selected.has(key(it)));
  const ids = (arr) => JSON.stringify(arr.map((it) => ({ k: it.k, id: it.id })));
  function setSelecting(on) { selecting = on; if (!on) selected.clear(); render(call('perm') || 'full'); }

  // ------------------------------------------------------------------ interactions liste
  let lp = null, suppress = false;
  list.addEventListener('pointerdown', (e) => {
    suppress = false;
    const t = e.target.closest('[data-i]');
    if (!t || isVid && false) return;
    lp = { t, x: e.clientX, y: e.clientY, timer: setTimeout(() => {
      suppress = true; haptic();
      if (!selecting) { selecting = true; selected.add(key(view[+t.dataset.i])); render(call('perm') || 'full'); }
    }, 450) };
  });
  const cancel = () => { if (lp) { clearTimeout(lp.timer); lp = null; } };
  list.addEventListener('pointermove', (e) => { if (lp && Math.hypot(e.clientX - lp.x, e.clientY - lp.y) > 10) cancel(); });
  list.addEventListener('pointerup', cancel);
  list.addEventListener('pointercancel', cancel);
  document.addEventListener('contextmenu', (e) => e.preventDefault());

  document.addEventListener('click', (e) => {
    if (suppress) { suppress = false; e.stopPropagation(); e.preventDefault(); return; }
    const b = e.target.closest('button,[data-i]');
    if (!b) return;
    const d = b.dataset;
    if (d.tab) { tab = d.tab; album = null; $$('#tabs button').forEach((x) => x.classList.toggle('on', x === b)); render(call('perm') || 'full'); list.scrollTop = 0; return; }
    if (d.album) { const it = items.find((x) => x.bid === d.album); album = { bid: d.album, name: it ? it.b : '' }; render(call('perm') || 'full'); list.scrollTop = 0; return; }
    if (d.act === 'perm') { call('askPerm'); return; }
    if (d.act === 'permset') { call('permSettings'); return; }
    if (d.s) {
      const sel = selItems();
      if (d.s === 'cancel') setSelecting(false);
      if (d.s === 'pick') call('pick', ids(sel));
      if (d.s === 'share' && sel.length) call('share', ids(sel));
      if (d.s === 'trash' && sel.length) call('trash', ids(sel));
      if (d.s === 'fav' && sel.length) { const all = sel.every((it) => favs.has(key(it))); sel.forEach((it) => all ? favs.delete(key(it)) : favs.add(key(it))); saveFavs(); setSelecting(false); }
      return;
    }
    if (d.i != null && list.contains(b)) {
      const it = view[+d.i];
      if (!it) return;
      if (selecting) {
        selected.has(key(it)) ? selected.delete(key(it)) : selected.add(key(it));
        if (!selected.size && !isPick) setSelecting(false); else refreshTiles();
        return;
      }
      if (isPick) { if (pickMulti) { selecting = true; selected.add(key(it)); render(call('perm') || 'full'); } else call('pick', ids([it])); return; }
      haptic();
      if (isVid) openPlayer(it); else openViewer(view, +d.i);
    }
  }, true);
  $('#bback').onclick = () => { album = null; render(call('perm') || 'full'); };
  $('#bsel').onclick = () => setSelecting(!selecting);
  $('#bslide').onclick = () => { const l = filtered().filter((x) => x.k === 'i'); if (l.length) { openViewer(l, 0); startSlides(); } };

  // ------------------------------------------------------------------ visionneuse
  const viewer = $('#viewer'), stage = $('#vstage'), vimg = $('#vimg'), vnext = $('#vnext');
  let vlist = [], vi = 0, z = { s: 1, x: 0, y: 0 }, slideTimer = null, vExternal = false;
  function vbtns() {
    const it = vlist[vi];
    if (!it) return;
    const f = favs.has(key(it));
    const b = (k, g, c, l, on) => `<button data-v="${k}" class="${on ? 'on' : ''}" type="button">${I(g, c)}<span>${l}</span></button>`;
    $('#vbot').innerHTML = it.ext ? b('openwith', 'external', 'orange', 'Ouvrir avec') :
      b('share', 'share', 'green', 'Partager') + b('fav', 'heart', f ? 'red' : 'chrome', f ? 'Favori' : 'Aimer', f) + b('wall', 'home', 'pink', 'Fond Puppy') +
      (it.k === 'i' ? b('edit', 'edit', 'amber', 'Retoucher') : b('play', 'play', 'violet', 'Lire')) + b('info', 'info', 'cyan', 'Infos') + b('trash', 'trash', 'red', 'Corbeille');
  }
  function openViewer(l, i, external) {
    vlist = l; vi = i; vExternal = !!external;
    viewer.hidden = false; viewer.classList.remove('hide');
    $('#vtop .ibtn').innerHTML = I('left', 'chrome', { shape: 'none' });
    showItem(0);
  }
  function closeViewer() {
    stopSlides();
    viewer.hidden = true;
    vimg.removeAttribute('src');
    if (vExternal) call('exit');
  }
  function applyZ() { vimg.style.transform = `translate(${z.x}px,${z.y}px) scale(${z.s})`; }
  function showItem(dir) {
    const it = vlist[vi];
    if (!it) return closeViewer();
    z = { s: 1, x: 0, y: 0 };
    vimg.style.transition = 'none';
    vimg.style.transform = dir ? `translateX(${dir * 40}px)` : '';
    vimg.style.opacity = dir ? '.3' : '1';
    vimg.src = it.ext ? full(it) : th(it);
    if (it.k === 'i') { const hi = new Image(); hi.onload = () => { if (vlist[vi] === it) vimg.src = hi.src; }; hi.src = full(it); }
    requestAnimationFrame(() => { vimg.style.transition = 'transform .22s ease-out,opacity .22s'; vimg.style.transform = ''; vimg.style.opacity = '1'; });
    $('#vplay').hidden = it.k !== 'v';
    $('#vplay').innerHTML = I('play', 'chrome', { shape: 'none' });
    $('#vtitle').textContent = it.n || '';
    $('#vsub').textContent = it.ext ? 'ouvert depuis une autre appli' : fmtDate(it.t) + ' · ' + fmtSize(it.s);
    $('#vcount').textContent = it.ext ? '' : (vi + 1) + ' / ' + vlist.length;
    vbtns();
    [vlist[vi + 1], vlist[vi - 1]].forEach((n) => { if (n && n.k === 'i' && !n.ext) { const p = new Image(); p.src = full(n); } });
  }
  function nav(d) { const n = vi + d; if (n < 0 || n >= vlist.length) { applyZ(); return; } vi = n; showItem(d); }

  // gestes : glisser, pincer, double-toucher
  const ptrs = new Map();
  let g = null, lastTap = 0;
  stage.addEventListener('pointerdown', (e) => {
    if (e.target.closest('.vplay')) return;
    stopSlides();
    stage.setPointerCapture(e.pointerId);
    ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (ptrs.size === 2) {
      const [a, b] = [...ptrs.values()];
      g = { pinch: true, d: Math.hypot(a.x - b.x, a.y - b.y), s: z.s, x: z.x, y: z.y, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 };
    } else g = { x0: e.clientX, y0: e.clientY, zx: z.x, zy: z.y, t: Date.now(), moved: false };
    vimg.style.transition = 'none';
  });
  stage.addEventListener('pointermove', (e) => {
    if (!ptrs.has(e.pointerId) || !g) return;
    ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (g.pinch && ptrs.size >= 2) {
      const [a, b] = [...ptrs.values()];
      const ns = Math.max(1, Math.min(6, g.s * Math.hypot(a.x - b.x, a.y - b.y) / g.d));
      z.s = ns; z.x = g.mx - (g.mx - g.x) * ns / g.s; z.y = g.my - (g.my - g.y) * ns / g.s;
      applyZ();
      return;
    }
    const dx = e.clientX - g.x0, dy = e.clientY - g.y0;
    if (Math.hypot(dx, dy) > 8) g.moved = true;
    if (z.s > 1) { z.x = g.zx + dx; z.y = g.zy + dy; applyZ(); }
    else if (Math.abs(dx) > Math.abs(dy)) vimg.style.transform = `translateX(${dx}px)`;
    else if (dy > 0) { vimg.style.transform = `translateY(${dy}px) scale(${1 - Math.min(dy, 400) / 1600})`; viewer.style.background = `rgba(0,0,0,${1 - Math.min(dy, 300) / 400})`; }
  });
  const endPtr = (e) => {
    if (!ptrs.has(e.pointerId)) return;
    ptrs.delete(e.pointerId);
    if (!g) return;
    vimg.style.transition = 'transform .22s ease-out';
    viewer.style.background = '';
    if (g.pinch) { if (ptrs.size === 0) { if (z.s <= 1.02) { z = { s: 1, x: 0, y: 0 }; applyZ(); } g = null; } return; }
    const dx = e.clientX - g.x0, dy = e.clientY - g.y0;
    if (!g.moved) {
      const now = Date.now();
      if (now - lastTap < 280) {
        if (z.s > 1) z = { s: 1, x: 0, y: 0 };
        else { const s = 2.6; z = { s, x: e.clientX - e.clientX * s, y: e.clientY - e.clientY * s }; }
        applyZ(); lastTap = 0;
      } else { lastTap = now; setTimeout(() => { if (lastTap === now) viewer.classList.toggle('hide'); }, 290); }
    } else if (z.s <= 1) {
      if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 70) nav(dx < 0 ? 1 : -1);
      else if (dy > 140 && Math.abs(dy) > Math.abs(dx)) closeViewer();
      else applyZ();
    }
    g = null;
  };
  stage.addEventListener('pointerup', endPtr);
  stage.addEventListener('pointercancel', endPtr);
  $('#vplay').onclick = () => openPlayer(vlist[vi]);

  viewer.addEventListener('click', (e) => {
    const b = e.target.closest('[data-v]');
    if (!b) return;
    const it = vlist[vi];
    haptic();
    switch (b.dataset.v) {
      case 'close': closeViewer(); break;
      case 'share': call('share', ids([it])); break;
      case 'fav': favs.has(key(it)) ? favs.delete(key(it)) : favs.add(key(it)); saveFavs(); vbtns(); break;
      case 'wall': call('setWallpaper', it.k, it.id, it.n || ''); break;
      case 'edit': call('edit', it.k, it.id); break;
      case 'play': openPlayer(it); break;
      case 'info': info(it); break;
      case 'trash': call('trash', ids([it])); break;
      case 'openwith': break;
    }
  });

  // diaporama avec effet Ken Burns
  function startSlides() {
    viewer.classList.add('hide');
    stage.classList.add('slideshow');
    call('toast', '🐾 Diaporama — touche l\'écran pour arrêter');
    slideTimer = setInterval(() => {
      let n = vi + 1;
      if (n >= vlist.length) n = 0;
      const it = vlist[n];
      vnext.style.transition = 'none'; vnext.style.opacity = '0'; vnext.style.transform = 'scale(1.0)';
      vnext.onload = () => {
        requestAnimationFrame(() => {
          vnext.style.transition = 'opacity 1.2s ease, transform 4.5s linear';
          vnext.style.opacity = '1'; vnext.style.transform = 'scale(1.08)';
          setTimeout(() => { vi = n; vimg.style.transition = 'none'; vimg.src = vnext.src; vimg.style.transform = 'scale(1.08)'; vnext.style.opacity = '0'; $('#vcount').textContent = (vi + 1) + ' / ' + vlist.length; $('#vtitle').textContent = it.n; }, 1250);
        });
      };
      vnext.src = full(it);
    }, 4200);
  }
  function stopSlides() { if (slideTimer) { clearInterval(slideTimer); slideTimer = null; stage.classList.remove('slideshow'); vnext.style.opacity = '0'; vimg.style.transform = ''; } }

  // fenêtre d'infos (style Vista)
  function info(it) {
    const veil = document.createElement('div'); veil.className = 'veil';
    const w = document.createElement('div'); w.className = 'win';
    w.innerHTML = `<div class="tb">${I('info', 'cyan')}<div class="tx"><div class="t chrome">Infos</div><div class="sub">${esc(it.n)}</div></div><button class="xb" type="button">${XSVG}</button></div>
      <div class="wb"><table class="infot">
      <tr><td>Nom</td><td>${esc(it.n)}</td></tr><tr><td>Date</td><td>${fmtDate(it.t)}</td></tr>
      <tr><td>Taille</td><td>${fmtSize(it.s)}</td></tr><tr><td>Dimensions</td><td>${it.w} × ${it.h} px${it.w * it.h > 0 ? ' · ' + (it.w * it.h / 1e6).toFixed(1) + ' Mpx' : ''}</td></tr>
      ${it.k === 'v' ? `<tr><td>Durée</td><td>${fmtDur(it.d)}</td></tr>` : ''}
      <tr><td>Album</td><td>${esc(it.b || '—')}</td></tr><tr><td>Type</td><td>${esc(it.m || '')}</td></tr></table></div><div class="wf"></div>`;
    const close = () => { veil.remove(); w.remove(); };
    veil.onclick = close; $('.xb', w).onclick = close;
    $('#layer').append(veil, w);
  }

  // ------------------------------------------------------------------ lecteur vidéo
  const player = $('#player');
  let pitem = null, ptimer = null, pst = {}, hideTimer = null, lastSave = 0, seeking = false;
  const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
  const FITS = { fit: 'Adapté', fill: 'Zoom', stretch: 'Étiré' };
  const ROTS = { auto: 'Auto', land: 'Paysage', port: 'Portrait' };
  function openPlayer(it) {
    pitem = it;
    document.body.classList.add('playing');
    player.hidden = false;
    player.classList.remove('hide', 'locked');
    $('#unlock').hidden = true;
    $('#ptitle').textContent = it.n || 'Vidéo';
    $('#psub').textContent = it.ext ? '' : fmtDate(it.t);
    $('[data-p=close]').innerHTML = I('left', 'chrome', { shape: 'none' });
    $('[data-p=lock]').innerHTML = I('lock', 'chrome', { shape: 'none' });
    $('[data-p=rw]').innerHTML = I('rw', 'chrome', { shape: 'none' });
    $('[data-p=ff]').innerHTML = I('ff', 'chrome', { shape: 'none' });
    $('#unlock').innerHTML = I('lock', 'amber', { shape: 'none' });
    const r = it.ext ? null : resume[key(it)];
    const start = r && r.pos > 5000 ? r.pos : 0;
    if (it.ext) call('playExternal', 0); else call('play', it.k, it.id, start);
    if (start) call('toast', '▶ Reprise à ' + fmtDur(start));
    clearInterval(ptimer);
    ptimer = setInterval(poll, 300);
    pst = { playing: true, pos: start, dur: it.d || 0 };
    renderP();
    autoHide();
  }
  function saveRes(final) {
    if (!pitem || pitem.ext || !pst.dur) return;
    if (pst.pos > 5000 && pst.pos < pst.dur - 8000) resume[key(pitem)] = { pos: pst.pos, dur: pst.dur, t: Date.now() };
    else if (final || pst.pos >= pst.dur - 8000) delete resume[key(pitem)];
    saveResume();
  }
  function closePlayer() {
    saveRes(true);
    clearInterval(ptimer);
    call('close');
    document.body.classList.remove('playing');
    player.hidden = true;
    const wasExt = pitem && pitem.ext;
    pitem = null;
    if (isVid) render(call('perm') || 'full');
    if (wasExt) call('exit');
  }
  function poll() {
    pst = J(call('pstate'), pst) || pst;
    if (MOCK && pst.playing) pst.pos = Math.min(pst.dur, pst.pos + 300);
    renderP();
    if (Date.now() - lastSave > 5000) { lastSave = Date.now(); saveRes(false); }
  }
  function renderP() {
    $('#porb').innerHTML = I(pst.playing ? 'pause' : 'play', 'chrome', { shape: 'none' });
    if (!seeking) {
      const f = pst.dur ? pst.pos / pst.dur : 0;
      $('#sfill').style.width = f * 100 + '%';
      $('#sknob').style.left = f * 100 + '%';
      $('#ppos').textContent = fmtDur(pst.pos);
    }
    $('#pdur').textContent = fmtDur(pst.dur);
    const o = (k, g, c, l, on) => `<button data-p="${k}" class="${on ? 'on' : ''}" type="button">${I(g, c, { shape: 'none' })}<span>${l}</span></button>`;
    $('#popts').innerHTML = o('speed', 'ff', 'cyan', '×' + String(pst.speed || 1).replace('.', ','), (pst.speed || 1) !== 1) + o('fit', 'expand', 'violet', FITS[pst.fit || 'fit'], pst.fit && pst.fit !== 'fit') +
      o('rot', 'rotate', 'amber', ROTS[pst.rot || 'auto'], pst.rot && pst.rot !== 'auto') + o('loop', 'loop', 'green', 'Boucle', pst.loop) + (pitem && !pitem.ext ? o('wall', 'home', 'pink', 'Fond Puppy') : '');
  }
  function showUi() { player.classList.remove('hide'); autoHide(); }
  function autoHide() { clearTimeout(hideTimer); hideTimer = setTimeout(() => { if (pst.playing && !seeking) player.classList.add('hide'); }, 3200); }
  function skipFx(side) { const el = side < 0 ? $('#pskl') : $('#pskr'); el.classList.remove('show'); void el.offsetWidth; el.classList.add('show'); }

  player.addEventListener('click', (e) => {
    const b = e.target.closest('[data-p]');
    if (!b) return;
    haptic();
    const p = b.dataset.p;
    if (p === 'close') return closePlayer();
    if (p === 'toggle') { call('toggle'); pst.playing = !pst.playing; renderP(); }
    if (p === 'rw') { call('seek', Math.max(0, pst.pos - 10000)); skipFx(-1); }
    if (p === 'ff') { call('seek', pst.pos + 10000); skipFx(1); }
    if (p === 'speed') { const i = SPEEDS.indexOf(pst.speed || 1); call('speed', SPEEDS[(i + 1) % SPEEDS.length]); }
    if (p === 'fit') { const k = Object.keys(FITS); call('fit', k[(k.indexOf(pst.fit || 'fit') + 1) % k.length]); }
    if (p === 'rot') { const k = Object.keys(ROTS); call('rotate', k[(k.indexOf(pst.rot || 'auto') + 1) % k.length]); }
    if (p === 'loop') call('loop', !pst.loop);
    if (p === 'wall') call('setWallpaper', pitem.k, pitem.id, pitem.n || '');
    if (p === 'lock') { player.classList.add('locked'); $('#unlock').hidden = false; call('toast', '🔒 Écran verrouillé'); }
    autoHide();
  });
  $('#unlock').onclick = () => { player.classList.remove('locked'); $('#unlock').hidden = true; showUi(); };

  // gestes : tap = interface, double-tap = ±10 s, glisser vertical = luminosité (gauche) / volume (droite)
  const pg = $('#pgest');
  let pgs = null, ptap = 0;
  pg.addEventListener('pointerdown', (e) => {
    if (player.classList.contains('locked')) return;
    pg.setPointerCapture(e.pointerId);
    pgs = { x: e.clientX, y: e.clientY, side: e.clientX < innerWidth / 2 ? 'b' : 'v', base: null, moved: false };
  });
  pg.addEventListener('pointermove', (e) => {
    if (!pgs) return;
    const dy = pgs.y - e.clientY;
    if (!pgs.moved && Math.abs(dy) > 14 && Math.abs(dy) > Math.abs(e.clientX - pgs.x)) {
      pgs.moved = true;
      pgs.base = pgs.side === 'v' ? (pst.vol == null ? 0.5 : pst.vol) : (pst.bright == null || pst.bright < 0 ? 0.5 : pst.bright);
    }
    if (!pgs.moved) return;
    const v = Math.max(0, Math.min(1, pgs.base + dy / (innerHeight * 0.6)));
    if (pgs.side === 'v') { call('volume', v); pst.vol = v; } else { call('brightness', v); pst.bright = v; }
    $('#pmeter').hidden = false;
    $('#pmi').innerHTML = pgs.side === 'v' ? I('speaker', 'pink', { shape: 'none', muted: v === 0 }) : I('sun', 'amber', { shape: 'none' });
    $('#pmv').style.width = v * 100 + '%';
    $('#pmt').textContent = Math.round(v * 100) + ' %';
  });
  pg.addEventListener('pointerup', (e) => {
    if (!pgs) return;
    $('#pmeter').hidden = true;
    if (!pgs.moved) {
      const now = Date.now();
      if (now - ptap < 300) {
        const side = e.clientX < innerWidth / 2 ? -1 : 1;
        call('seek', Math.max(0, pst.pos + side * 10000)); pst.pos += side * 10000; skipFx(side); ptap = 0;
      } else { ptap = now; setTimeout(() => { if (ptap === now) { if (player.classList.contains('hide')) showUi(); else player.classList.add('hide'); } }, 300); }
    }
    pgs = null;
  });

  // barre de lecture
  const seek = $('#seek');
  const seekAt = (x) => { const r = seek.getBoundingClientRect(); return Math.max(0, Math.min(1, (x - r.left) / r.width)); };
  seek.addEventListener('pointerdown', (e) => { seeking = true; seek.setPointerCapture(e.pointerId); moveSeek(e); clearTimeout(hideTimer); });
  seek.addEventListener('pointermove', (e) => { if (seeking) moveSeek(e); });
  function moveSeek(e) { const f = seekAt(e.clientX); $('#sfill').style.width = f * 100 + '%'; $('#sknob').style.left = f * 100 + '%'; $('#ppos').textContent = fmtDur(f * pst.dur); }
  seek.addEventListener('pointerup', (e) => { if (!seeking) return; const f = seekAt(e.clientX); call('seek', Math.round(f * pst.dur)); pst.pos = f * pst.dur; seeking = false; autoHide(); });

  // ------------------------------------------------------------------ pont natif
  window.MediaUI = {
    on(ev, data) {
      if (ev === 'player') { pst = Object.assign(pst, J(data, {})); renderP(); }
      else if (ev === 'ended') { pst.playing = false; pst.pos = pst.dur; saveRes(true); renderP(); showUi(); }
      else if (ev === 'perm' || ev === 'resume') {
        if (!player.hidden || !viewer.hidden) return;
        const before = items.length + ':' + (items[0] && items[0].id);
        const perm = load();
        if (ev === 'perm' || before !== items.length + ':' + (items[0] && items[0].id)) render(perm);
      } else if (ev === 'deleted') {
        const gone = new Set((J(data, []) || []).map((x) => x.k + ':' + x.id));
        items = items.filter((it) => !gone.has(key(it)));
        gone.forEach((k) => { favs.delete(k); delete resume[k]; });
        saveFavs(); saveResume();
        if (!viewer.hidden) { vlist = vlist.filter((it) => !gone.has(key(it))); if (!vlist.length) closeViewer(); else { vi = Math.min(vi, vlist.length - 1); showItem(0); } }
        selecting = false; selected.clear();
        render(call('perm') || 'full');
      } else if (ev === 'external') openExternal(J(data, null));
    },
    back() {
      if ($('#layer').children.length) { $('#layer').innerHTML = ''; return true; }
      if (!player.hidden) { if (player.classList.contains('locked')) return true; closePlayer(); return true; }
      if (!viewer.hidden) { closeViewer(); return true; }
      if (selecting) { setSelecting(false); return true; }
      if (album) { album = null; render(call('perm') || 'full'); return true; }
      return false;
    },
    insets(t, b) { root.setProperty('--st', Math.max(t, 20) + 'px'); root.setProperty('--sb', Math.max(b, 0) + 'px'); fixTop(); },
  };

  function openExternal(x) {
    if (!x || !x.uri) return;
    const it = { ext: true, uri: x.uri, n: x.name || 'Fichier', k: /^video\//.test(x.mime) ? 'v' : 'i' };
    if (it.k === 'v') openPlayer(it); else openViewer([it], 0, true);
  }

  // démarrage
  const perm = load();
  render(perm);
  openExternal(J(call('external'), null));
  window.addEventListener('resize', fixTop);

  // ------------------------------------------------------------------ aperçu navigateur (hors APK)
  function mock() {
    const cols = [['#ff3fa4', '#29e6ff'], ['#9b5cff', '#ffb627'], ['#3dffb0', '#3d82ff'], ['#ff7a29', '#ff4d5e'], ['#29e6ff', '#9b5cff']];
    const L = [];
    const albums = ['Camera', 'Screenshots', 'WhatsApp Images', 'Folsom 2026', 'Pup Night'];
    for (let i = 0; i < 90; i++) {
      const c = cols[i % cols.length];
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c[0]}"/><stop offset="1" stop-color="${c[1]}"/></linearGradient></defs><rect width="100" height="100" fill="url(#g)"/><circle cx="${20 + i * 7 % 60}" cy="${30 + i * 13 % 40}" r="${10 + i % 15}" fill="#fff" opacity=".35"/></svg>`;
      const v = i % 7 === 3;
      L.push({ id: i, k: v ? 'v' : 'i', n: (v ? 'VID_' : 'IMG_') + (20261007 - i) + (v ? '.mp4' : '.jpg'), t: Date.now() - i * 3.2e8, w: 3000, h: 4000, s: 2.4e6 + i * 1e4, m: v ? 'video/mp4' : 'image/jpeg', b: albums[i % albums.length], bid: 'b' + (i % albums.length), d: v ? 61000 + i * 1000 : 0, _th: 'data:image/svg+xml,' + encodeURIComponent(svg) });
    }
    const mem = { resume: JSON.stringify({ 'v:3': { pos: 30000, dur: 64000, t: Date.now() } }), favs: JSON.stringify(['i:1', 'i:5']) };
    return { mode: () => new URLSearchParams(location.search).get('mode') || 'gallery', perm: () => 'full', list: () => JSON.stringify(L), get: (k) => mem[k], set: (k, v) => { mem[k] = v; }, external: () => '',
      pstate: () => '', play() {}, toggle() {}, seek() {}, close() {}, haptic() {}, toast() {}, share() {}, trash() {}, setWallpaper() {}, speed() {}, fit() {}, loop() {}, rotate() {}, volume() {}, brightness() {} };
  }
})();
