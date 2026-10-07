/* PuppyPhone — le lanceur puppyplay. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon, PAL = PupIcons.PAL;
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const norm = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const MOCK = !window.Pup;
  const N = window.Pup || window.PupMock;
  const call = (fn, ...a) => { try { return N[fn] ? N[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const J = (s, d) => { try { return s == null ? d : JSON.parse(s); } catch (e) { return d; } };
  const S = {
    get: (k, d) => J(call('get', k), d),
    set: (k, v) => call('set', k, JSON.stringify(v)),
  };
  const XSVG = '<svg viewBox="0 0 20 20"><path d="M4 4L16 16M16 4L4 16" stroke="#fff" stroke-width="3.4" stroke-linecap="round" style="filter:drop-shadow(0 1px 1px #0008)"/></svg>';

  // ------------------------------------------------------------------ réglages
  const DEF = { accent: 'pink', iconStyle: 'framed', iconShape: 'tile', pack: true, size: 58, cols: 4, labels: true, showClock: true, showPower: true, showSearch: true, welcomed: false, fx: 'auto' };
  const ACCENTS = { pink: ['#ff3fa4', '#29e6ff'], cyan: ['#29e6ff', '#ff3fa4'], violet: ['#9b5cff', '#29e6ff'], amber: ['#ffb627', '#ff3fa4'], green: ['#3dffb0', '#9b5cff'], red: ['#ff4d5e', '#ffb627'] };
  let cfg = Object.assign({}, DEF, S.get('cfg', {}));
  const saveCfg = () => { S.set('cfg', cfg); applyCfg(); };
  function applyCfg() {
    const r = document.documentElement.style;
    const ac = ACCENTS[cfg.accent] || ACCENTS.pink;
    r.setProperty('--acc', ac[0]); r.setProperty('--acc2', ac[1]);
    r.setProperty('--is', cfg.size + 'px'); r.setProperty('--cols', cfg.cols);
    document.body.classList.toggle('nolabels', !cfg.labels);
    document.body.classList.toggle('fx-max', cfg.fx === 'max');
    document.body.classList.toggle('fx-turbo', cfg.fx === 'turbo');
    $('#wclock').hidden = !cfg.showClock; $('#wpower').hidden = !cfg.showPower; $('#searchbar').hidden = !cfg.showSearch;
  }

  // ------------------------------------------------------------------ état
  let apps = [], byId = new Map(), defs = {}, status = {}, wall = {};
  let overrides = S.get('cats', {}), learn = S.get('learn', {}), fresh = S.get('fresh', {});
  let home = S.get('home', null);
  const flairCache = new Map();
  const saveHome = () => S.set('home', home);

  function flairOf(a) {
    let r = flairCache.get(a.id);
    if (!r) { r = Flair.classify(a, learn); flairCache.set(a.id, r); }
    return r;
  }
  const isNet = (a) => a.pkg === defs.self && /BrowserActivity/.test(a.id);
  const selfCat = (a) => !isSelf(a) ? null : /BrowserActivity/.test(a.id) ? 'web' : /GalleryActivity/.test(a.id) ? 'photo' : /VideoActivity/.test(a.id) ? 'video' : null;
  const catOf = (a) => overrides[a.id] || selfCat(a) || flairOf(a).cat;
  const appsIn = (cat) => apps.filter((a) => catOf(a) === cat);
  const isSelf = (a) => a.pkg === defs.self;
  const isSon = (a) => isSelf(a) && /PupSon/.test(a.id);

  // ------------------------------------------------------------------ icônes
  const shapeOpt = () => ({ shape: cfg.iconShape === 'orb' ? 'orb' : 'tile' });
  function role(a) {
    const p = a.pkg || '', l = norm(a.label);
    if (isSon(a)) return ['speaker', 'pink', { muted: wall.muted }];
    if (isNet(a)) return ['pupnet', 'pink'];
    if (isSelf(a) && /GalleryActivity/.test(a.id)) return ['gallery', 'violet'];
    if (isSelf(a) && /VideoActivity/.test(a.id)) return ['film', 'red'];
    if (isSelf(a)) return ['paw', 'pink'];
    if (p === 'fr.piika.pupdown') return ['piggy', 'pink'];
    if (p === defs.dial || /dialer|incallui/.test(p) || l === 'telephone' || l === 'phone') return ['phone', 'green'];
    if (p === defs.sms || /\.messaging$|\.mms$|apps\.messaging/.test(p) || (a.system && l === 'messages')) return ['sms', 'cyan'];
    if (p === defs.browser && a.system) return ['globe', 'blue'];
    if (p === defs.camera || (a.system && /camera/.test(p))) return ['camera', 'amber'];
    if (/gallery|apps\.photos/.test(p)) return ['gallery', 'violet'];
    if (p === 'com.android.settings' || (a.system && /\.settings$/.test(p))) return ['gear', 'chrome'];
    if (/deskclock|clockpackage/.test(p)) return ['clock', 'cyan'];
    if (/calculator/.test(p)) return ['calc', 'violet'];
    if (/contacts/.test(p)) return ['contacts', 'orange'];
    if (/calendar/.test(p) && a.system) return ['calendar', 'blue', { day: new Date().getDate() }];
    if (p === 'com.google.android.apps.maps') return ['pin', 'red'];
    if (p === 'com.google.android.gm' || /\.email$/.test(p)) return ['mail', 'red'];
    if (p === 'com.android.vending' || /samsungapps|appmarket|xiaomi\.market/.test(p)) return ['store', 'green'];
    if (/apps\.nbu\.files|myfiles|documentsui|fileexplorer/.test(p)) return ['folder', 'amber'];
    if (a.system && /weather|meteo|daemonapp/.test(p)) return ['weather', 'blue'];
    if (/android\.keep|app\.notes/.test(p)) return ['notes', 'gold'];
    if (a.system && /music/.test(p)) return ['music', 'violet'];
    return null;
  }
  const iconUrl = (id, raw) => {
    if (MOCK) return N.iconUrl(id);
    const a = byId.get(id);
    return 'https://pup.local/icon?id=' + encodeURIComponent(id) + '&raw=' + (raw ? 1 : 0) + '&v=' + (a && a.updated || 0);
  };
  const initial = (s) => (String(s || '?').trim()[0] || '?').toUpperCase();
  function appIcon(a) {
    const r = cfg.pack ? role(a) : null;
    if (r) return `<div class="ico svg">${I(r[0], r[1], Object.assign(shapeOpt(), r[2] || {}))}</div>`;
    if (cfg.iconStyle === 'drawn') {
      const c = Flair.cat(catOf(a));
      return `<div class="ico svg">${I(c.g, c.c, shapeOpt())}<span class="letter">${esc(initial(a.label))}</span></div>`;
    }
    if (cfg.iconStyle === 'raw') return `<div class="ico raw"><img src="${iconUrl(a.id, false)}" loading="lazy" alt=""></div>`;
    return `<div class="ico framed ${cfg.iconShape === 'orb' ? 'orb' : ''}"><img src="${iconUrl(a.id, true)}" loading="lazy" alt=""></div>`;
  }
  function miniIcon(a) {
    const r = cfg.pack ? role(a) : null;
    if (r) return `<span>${I(r[0], r[1], Object.assign({ shape: 'tile' }, r[2] || {}))}</span>`;
    return `<span><img src="${iconUrl(a.id, true)}" loading="lazy" alt=""></span>`;
  }
  function folderIcon(cat) {
    const c = Flair.cat(cat);
    const list = appsIn(cat).slice(0, 4);
    return `<div class="ico fold" style="--fc:${(PAL[c.c] || PAL.pink)[1]}"><div class="mini">${list.map(miniIcon).join('')}</div><div class="fb">${I(c.g, c.c, { shape: 'orb' })}</div></div>`;
  }
  const PUP = {
    son: { label: 'PupSon', icon: () => I('speaker', 'pink', { muted: wall.muted }), open: () => openPupSon() },
    settings: { label: 'PupRéglages', icon: () => I('gear', 'violet'), open: () => openSettings() },
    drawer: { label: 'Mes applis', icon: () => I('apps', 'cyan'), open: () => openDrawer() },
    niche: { label: 'La Niche', icon: () => I('paw', 'pink', { shape: 'orb' }), open: () => goPage(1) },
  };

  // ------------------------------------------------------------------ éléments de grille
  function cell(key, icon, label, extra) {
    return `<button class="it ${extra || ''}" data-k="${esc(key)}" type="button">${icon}<span class="lb">${esc(label)}</span></button>`;
  }
  function itemHTML(it, key, opts) {
    opts = opts || {};
    if (!it) return '';
    if (it.t === 'app') {
      const a = byId.get(it.id);
      if (!a) return '';
      return cell(key, appIcon(a) + (fresh[a.id] ? '<span class="newdot">NEW</span>' : ''), a.label);
    }
    if (it.t === 'folder') {
      const c = Flair.cat(it.cat), n = appsIn(it.cat).length;
      const showCount = opts.counts || it.cat === 'sort';
      return cell(key, folderIcon(it.cat) + (showCount && n ? `<span class="count">${n}</span>` : ''), c.n, it.cat === 'sort' && n ? 'sorting' : '');
    }
    if (it.t === 'sc') {
      const owner = apps.find((a) => a.pkg === it.pkg);
      const src = MOCK ? N.iconUrl(it.pkg) : `https://pup.local/sicon?pkg=${encodeURIComponent(it.pkg)}&sid=${encodeURIComponent(it.sid)}`;
      const badge = owner ? `<span class="sbadge"><img src="${iconUrl(owner.id, true)}" alt=""></span>` : '';
      return cell(key, `<div class="ico framed"><img src="${src}" alt="" onerror="this.style.opacity=0"></div>${badge}`, it.label);
    }
    if (it.t === 'link') return cell(key, `<div class="ico svg">${I('link', it.color || 'cyan', shapeOpt())}</div>`, it.label);
    if (it.t === 'pup') { const p = PUP[it.app]; return p ? cell(key, `<div class="ico svg">${p.icon()}</div>`, p.label) : ''; }
    return '';
  }

  // ------------------------------------------------------------------ rendu accueil
  function defaultHome() {
    const want = ['msg', 'social', 'date', 'photo', 'music', 'video', 'games', 'sort'];
    const items = want.filter((c) => appsIn(c).length).map((c) => ({ t: 'folder', cat: c }));
    items.push({ t: 'pup', app: 'son' }, { t: 'pup', app: 'settings' });
    const pick = (pkg, cat) => {
      let a = pkg && apps.find((x) => x.pkg === pkg);
      if (!a && cat) a = appsIn(cat)[0];
      return a ? { t: 'app', id: a.id } : null;
    };
    return { pages: [items.slice(0, 12), [{ t: 'pup', app: 'drawer' }, { t: 'pup', app: 'niche' }]], dock: [pick(defs.dial, 'tel'), pick(defs.sms, 'tel'), pick(defs.browser, 'web'), pick(defs.camera, 'photo')] };
  }

  function renderGrid(p) {
    const g = $('#g' + p);
    const items = home.pages[p] || [];
    g.innerHTML = items.map((it, i) => itemHTML(it, `g${p}:${i}`)).join('') +
      `<button class="it addtile" data-k="add:${p}" type="button"><div class="ico">${I('plus', 'chrome', { shape: 'none' })}</div><span class="lb">Ajouter</span></button>`;
  }
  function renderNiche() {
    const cats = Flair.CATS.filter((c) => appsIn(c.id).length);
    $('#niche').innerHTML = cats.map((c, i) => `<div class="bob" style="--d:${(-i * 0.7).toFixed(1)}s">${itemHTML({ t: 'folder', cat: c.id }, 'n:' + c.id, { counts: true })}</div>`).join('') ||
      `<div class="empty" style="grid-column:1/-1">${I('box', 'orange')}Aucune appli trouvée.</div>`;
  }
  function renderDock() {
    const d = $('#dock');
    $$('.it,#orb', d).forEach((e) => e.remove());
    const slot = (i) => {
      const it = home.dock[i];
      const html = it && itemHTML(it, 'dock:' + i);
      return html || `<button class="it addtile" data-k="dock:${i}" type="button"><div class="ico">${I('plus', 'chrome', { shape: 'none' })}</div><span class="lb">Vide</span></button>`;
    };
    d.insertAdjacentHTML('beforeend', slot(0) + slot(1) + `<button id="orb" type="button" aria-label="Toutes les applis">${I('paw', 'chrome', { shape: 'none' })}</button>` + slot(2) + slot(3));
    $('#orb').onclick = () => { haptic(); openDrawer(); };
  }
  function renderAll() {
    renderGrid(0); renderNiche(); renderGrid(1); renderDock();
    if (drawerOpen) renderDrawer();
    const top = winStack[winStack.length - 1];
    if (top && top.refresh) top.refresh();
  }

  // ------------------------------------------------------------------ widgets
  const PW = [
    { k: 'wifi', g: 'wifi', c: 'cyan', l: 'Wi-Fi' },
    { k: 'bt', g: 'bt', c: 'blue', l: 'Bluetooth' },
    { k: 'data', g: 'data', c: 'green', l: '4G' },
    { k: 'torch', g: 'torch', c: 'amber', l: 'Lampe' },
    { k: 'wallsound', g: 'speaker', c: 'pink', l: 'Son fond' },
  ];
  let powerSig = '';
  function renderPower() {
    const sig = [status.wifi, status.bt, status.data, status.airplane, status.torch, wall.type, wall.muted, status.operator].join('|');
    if (sig === powerSig) return;
    powerSig = sig;
    $('#wpower').innerHTML = PW.map((p) => {
      let st = '';
      if (p.k === 'wifi') st = status.wifi ? 'on' : '';
      if (p.k === 'bt') st = status.bt ? 'on' : '';
      if (p.k === 'data') st = status.airplane ? '' : status.data ? 'on' : '';
      if (p.k === 'torch') st = status.torch ? 'on' : '';
      if (p.k === 'wallsound') st = wall.type !== 'video' ? '' : wall.muted ? 'mid' : 'on';
      let label = p.l;
      if (p.k === 'data' && status.operator) label = status.airplane ? 'Avion' : '4G';
      if (p.k === 'wallsound') label = wall.type !== 'video' ? 'Son fond' : wall.muted ? 'Muet' : 'Son ON';
      const o = p.k === 'torch' ? { on: status.torch } : p.k === 'wallsound' ? { muted: wall.muted || wall.type !== 'video' } : {};
      return `<button class="${st}" data-pw="${p.k}" type="button" style="--pc:${PAL[p.c][1]}"><span class="pwi">${I(p.g, p.c, Object.assign({ shape: 'none' }, o))}</span><small>${esc(label)}</small><i class="led"></i></button>`;
    }).join('');
  }
  function renderBattery() {
    const lv = status.battery == null ? -1 : status.battery;
    const col = lv < 0 ? 'var(--muted)' : lv <= 15 ? 'var(--red)' : lv <= 35 ? 'var(--amber)' : 'var(--good)';
    const b = $('#batt');
    b.style.setProperty('--bcol', col);
    $('#liq').style.width = Math.max(4, lv) + '%';
    $('#bpct').textContent = lv < 0 ? '--%' : lv + '%';
    $('#bolt').textContent = status.charging ? '⚡' : '';
  }
  const DAYS = ['DIMANCHE', 'LUNDI', 'MARDI', 'MERCREDI', 'JEUDI', 'VENDREDI', 'SAMEDI'];
  const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
  let lastH = '', lastM = '';
  function tick() {
    const d = new Date();
    const h = String(d.getHours()).padStart(2, '0'), m = String(d.getMinutes()).padStart(2, '0');
    if (h !== lastH) { const c = $('#fh'); $('b', c).textContent = h; if (lastH) { c.classList.remove('flipit'); void c.offsetWidth; c.classList.add('flipit'); } lastH = h; }
    if (m !== lastM) {
      const c = $('#fm'); $('b', c).textContent = m; if (lastM) { c.classList.remove('flipit'); void c.offsetWidth; c.classList.add('flipit'); } lastM = m;
      $('#d1').textContent = DAYS[d.getDay()];
      $('#d2').textContent = d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + d.getFullYear();
    }
  }
  function updateStatus() {
    status = J(call('status'), status) || {};
    if (status.wall) wall = status.wall;
    renderPower(); renderBattery(); applyWall();
    const top = winStack[winStack.length - 1];
    if (top && top.onStatus) top.onStatus();
  }
  let wallKey = '';
  function applyWall() {
    const t = wall.type || 'neon';
    const b = document.body;
    ['neon', 'image', 'video', 'system'].forEach((x) => b.classList.toggle('wall-' + x, x === t));
    const k = t + ':' + wall.ver;
    if (t === 'image' && k !== wallKey) $('#wallimg').style.backgroundImage = `url("https://pup.local/wall?v=${wall.ver}")`;
    wallKey = k;
  }

  // ------------------------------------------------------------------ applis
  function loadDefaults() { defs = J(call('defaults'), {}) || {}; }
  let appsSig = '';
  function refreshApps(reason) {
    const list = (J(call('apps'), []) || []).filter((a) => !(a.pkg === defs.self && /MainActivity/.test(a.id)));
    list.sort((a, b) => a.label.localeCompare(b.label, 'fr', { sensitivity: 'base' }));
    apps = list;
    byId = new Map(apps.map((a) => [a.id, a]));
    const sig = apps.map((a) => a.id + '@' + (a.updated || 0)).join(',');
    const changed = sig !== appsSig;
    appsSig = sig;
    if (changed) flairCache.clear();
    let known = S.get('known', null);
    const added = [];
    if (!known) known = apps.map((a) => a.id);
    else {
      const ks = new Set(known);
      for (const a of apps) if (!ks.has(a.id)) { added.push(a); fresh[a.id] = Date.now(); }
    }
    // nettoyage
    const ids = new Set(apps.map((a) => a.id));
    for (const k of Object.keys(fresh)) if (!ids.has(k) || Date.now() - fresh[k] > 7 * 864e5) delete fresh[k];
    S.set('known', apps.map((a) => a.id));
    S.set('fresh', fresh);
    if (!home || !home.pages) { home = defaultHome(); saveHome(); }
    const net = apps.find(isNet);
    for (const re of [/GalleryActivity/, /VideoActivity/]) {
      const m = apps.find((x) => isSelf(x) && re.test(x.id));
      const flag = 'added_' + re.source;
      if (m && !cfg[flag]) { cfg[flag] = true; S.set('cfg', cfg); if (!onHome({ t: 'app', id: m.id })) { home.pages[0].unshift({ t: 'app', id: m.id }); saveHome(); } }
    }
    if (net && !cfg.netAdded) { cfg.netAdded = true; S.set('cfg', cfg); if (!onHome({ t: 'app', id: net.id })) { home.pages[0].unshift({ t: 'app', id: net.id }); saveHome(); } }
    if (changed || added.length || reason === 'force') renderAll();
    added.slice(0, 4).forEach((a, i) => setTimeout(() => announceNew(a), 400 + i * 900));
  }
  function announceNew(a) {
    const f = flairOf(a), c = Flair.cat(catOf(a));
    if (catOf(a) === 'sort') toast(`Nouvelle appli : ${a.label}`, `Flair hésite 🤔 → rangée dans « À trier ». Appui long pour la ranger.`, appIcon(a), 6000);
    else toast(`Nouvelle appli : ${a.label}`, `Flair 🐾 l'a rangée dans « ${c.n} »${f.conf < 0.99 ? ' (sûr à ' + Math.round(f.conf * 100) + ' %)' : ''}.`, appIcon(a), 5000);
  }
  function launchApp(id) {
    const a = byId.get(id);
    if (!a) return;
    haptic();
    if (isSon(a)) return openPupSon();
    if (isSelf(a)) return call('launch', id);
    if (fresh[id]) { delete fresh[id]; S.set('fresh', fresh); setTimeout(renderAll, 600); }
    call('launch', id);
  }
  function takePins() {
    const pins = J(call('takePins'), []) || [];
    if (!pins.length) return;
    for (const p of pins) home.pages[1].push(p.type === 'link' ? { t: 'link', url: p.url, label: p.label, color: 'cyan' } : { t: 'sc', pkg: p.pkg, sid: p.sid, label: p.label });
    saveHome(); renderAll();
    toast('Raccourci ajouté', `${pins.map((p) => p.label).join(', ')} → page Raccourcis`, `<div class="ico svg">${I('link', 'cyan')}</div>`);
  }

  // ------------------------------------------------------------------ pages & gestes
  const pager = $('#pager');
  let curPage = 0;
  function goPage(i, smooth = true) { pager.scrollTo({ left: i * pager.clientWidth, behavior: smooth ? 'smooth' : 'auto' }); }
  function renderDots() {
    $('#dots').innerHTML = [0, 1, 2].map((i) => `<i class="${i === curPage ? 'on' : ''}">${I('paw', i === 1 ? 'cyan' : 'pink', { shape: 'none' })}</i>`).join('');
  }
  pager.addEventListener('scroll', () => {
    const p = Math.round(pager.scrollLeft / Math.max(1, pager.clientWidth));
    if (p !== curPage) { curPage = p; renderDots(); document.body.classList.toggle('on-niche', p === 1); }
  }, { passive: true });

  let ts = null;
  pager.addEventListener('touchstart', (e) => {
    const t = e.touches[0];
    const page = $$('.page')[curPage];
    ts = { x: t.clientX, y: t.clientY, t: Date.now(), top: page.scrollTop, bottom: page.scrollTop + page.clientHeight >= page.scrollHeight - 4 };
  }, { passive: true });
  pager.addEventListener('touchend', (e) => {
    if (!ts) return;
    const t = e.changedTouches[0], dx = t.clientX - ts.x, dy = t.clientY - ts.y;
    if (Math.abs(dy) > 70 && Math.abs(dy) > Math.abs(dx) * 1.4 && Date.now() - ts.t < 700) {
      if (dy < 0 && ts.bottom) openDrawer();
      else if (dy > 0 && ts.top <= 0) call('expand');
    }
    ts = null;
  }, { passive: true });

  // ------------------------------------------------------------------ appui court / long
  let lp = null, suppress = false;
  document.addEventListener('pointerdown', (e) => {
    const it = e.target.closest('[data-k]');
    const blank = !it && e.target.closest('.page') && !e.target.closest('button,.w-clock,.w-power,input');
    suppress = false;
    if (!it && !blank) return;
    const x = e.clientX, y = e.clientY;
    if (it) it.classList.add('press');
    lp = { it, x, y, timer: setTimeout(() => {
      suppress = true;
      if (it) it.classList.remove('press');
      haptic('long');
      if (it) longPress(it.dataset.k, x, y); else blankMenu(x, y);
    }, 480) };
  });
  const cancelLp = () => { if (lp) { clearTimeout(lp.timer); if (lp.it) lp.it.classList.remove('press'); lp = null; } };
  document.addEventListener('pointermove', (e) => { if (lp && Math.hypot(e.clientX - lp.x, e.clientY - lp.y) > 10) cancelLp(); });
  document.addEventListener('pointerup', cancelLp);
  document.addEventListener('pointercancel', cancelLp);
  document.addEventListener('contextmenu', (e) => e.preventDefault());
  document.addEventListener('click', (e) => {
    if (suppress) { suppress = false; e.preventDefault(); e.stopPropagation(); return; }
    const it = e.target.closest('[data-k]');
    if (it) activate(it.dataset.k);
  }, true);

  function itemAt(key) {
    const [kind, rest] = key.split(/:(.*)/s);
    if (kind === 'g0' || kind === 'g1') { const p = +kind[1], i = +rest; return { where: 'grid', p, i, it: home.pages[p][i] }; }
    if (kind === 'dock') return { where: 'dock', i: +rest, it: home.dock[+rest] };
    if (kind === 'n') return { where: 'niche', it: { t: 'folder', cat: rest } };
    if (kind === 'd' || kind === 'f' || kind === 'pick') return { where: kind, it: { t: 'app', id: rest } };
    if (kind === 'add') return { where: 'add', p: +rest };
    return {};
  }
  function activate(key) {
    const r = itemAt(key);
    if (r.where === 'add') return addMenu(r.p);
    if (r.where === 'pick') return;
    if (r.where === 'dock' && !r.it) return pickApp('Choisir une appli pour le dock', (id) => { home.dock[r.i] = { t: 'app', id }; saveHome(); renderAll(); });
    if (r.where === 'd') closeDrawer();
    if (r.where === 'f') closeTop();
    openItem(r.it);
  }
  function openItem(it) {
    if (!it) return;
    if (it.t === 'app') return launchApp(it.id);
    if (it.t === 'folder') { haptic(); return openFolder(it.cat); }
    if (it.t === 'sc') { haptic(); return call('startShortcut', it.pkg, it.sid); }
    if (it.t === 'link') { haptic(); return call('browse', it.url, ''); }
    if (it.t === 'pup') { haptic(); const p = PUP[it.app]; return p && p.open(); }
  }

  function onHome(it) {
    return home.pages.some((pg) => pg.some((x) => x.t === it.t && (x.id || x.cat) === (it.id || it.cat))) || home.dock.some((x) => x && x.id === it.id);
  }
  function addToHome(it, page) {
    const p = page == null ? 0 : page;
    home.pages[p].push(it); saveHome(); renderAll();
    toast('Ajouté à l\'accueil', p === 0 ? 'Sur la page principale' : 'Sur la page Raccourcis', `<div class="ico svg">${I('home', 'pink')}</div>`, 2200);
  }

  function longPress(key, x, y) {
    const r = itemAt(key);
    if (r.where === 'add') return addMenu(r.p);
    const it = r.it;
    const items = [];
    let head = '';
    if (it && it.t === 'app') {
      const a = byId.get(it.id);
      if (!a) return;
      const c = Flair.cat(catOf(a)), f = flairOf(a);
      const how = overrides[a.id] ? 'rangée par toi' : catOf(a) === 'sort' ? 'Flair hésite' : 'Flair ' + Math.round(f.conf * 100) + ' %';
      head = `<div class="mh">${appIcon(a)}<div><b>${esc(a.label)}</b><small>${esc(c.n)} · ${esc(how)}</small></div></div>`;
      if (r.where !== 'grid' && r.where !== 'dock' && !onHome(it)) {
        items.push({ g: ['home', 'pink'], l: 'Ajouter à l\'accueil', f: () => addToHome({ t: 'app', id: a.id }, 0) });
        items.push({ g: ['link', 'cyan'], l: 'Ajouter aux Raccourcis', f: () => addToHome({ t: 'app', id: a.id }, 1) });
      }
      items.push({ g: ['folder', 'amber'], l: 'Ranger dans…', f: () => moveApp(a) });
      if (!isSelf(a)) {
        const sc = J(call('shortcuts', a.pkg), []);
        if (Array.isArray(sc) && sc.length) {
          items.push({ sec: 'Raccourcis de l\'appli' });
          sc.slice(0, 5).forEach((s) => items.push({ img: MOCK ? N.iconUrl(a.id) : `https://pup.local/sicon?pkg=${encodeURIComponent(s.pkg)}&sid=${encodeURIComponent(s.sid)}`, l: s.label, f: () => call('startShortcut', s.pkg, s.sid),
            pin: () => addToHome({ t: 'sc', pkg: s.pkg, sid: s.sid, label: s.label }, 1) }));
        }
      }
      items.push({ hr: 1 });
      if (r.where === 'grid' || r.where === 'dock') pushPlaceItems(items, r);
      if (!isSelf(a)) {
        items.push({ g: ['gear', 'chrome'], l: 'Infos de l\'appli', f: () => call('appInfo', a.pkg) });
        if (!a.system) items.push({ g: ['box', 'red'], l: 'Désinstaller', danger: 1, f: () => call('uninstall', a.pkg) });
      }
    } else if (it) {
      const label = it.t === 'folder' ? Flair.cat(it.cat).n : it.t === 'pup' ? PUP[it.app].label : it.label;
      const ic = it.t === 'folder' ? folderIcon(it.cat) : itemHTML(it, 'x').replace(/^<button[^>]*>|<span class="lb">[\s\S]*$/g, '');
      head = `<div class="mh">${ic}<div><b>${esc(label)}</b><small>${it.t === 'folder' ? appsIn(it.cat).length + ' applis' : it.t === 'sc' ? 'raccourci' : it.t === 'link' ? esc(it.url) : 'appli PuppyPhone'}</small></div></div>`;
      items.push({ g: ['plus', 'green'], l: 'Ouvrir', f: () => openItem(it) });
      if (r.where === 'niche' && !onHome(it)) items.push({ g: ['home', 'pink'], l: 'Ajouter à l\'accueil', f: () => addToHome(it, 0) });
      if (r.where === 'grid' || r.where === 'dock') { items.push({ hr: 1 }); pushPlaceItems(items, r); }
    } else if (r.where === 'dock') {
      return pickApp('Choisir une appli pour le dock', (id) => { home.dock[r.i] = { t: 'app', id }; saveHome(); renderAll(); });
    }
    menu(items, x, y, head);
  }
  function pushPlaceItems(items, r) {
    if (r.where === 'grid') {
      const pg = home.pages[r.p];
      if (r.i > 0) items.push({ g: ['plus', 'violet'], txt: '◀', l: 'Déplacer vers la gauche', f: () => { [pg[r.i - 1], pg[r.i]] = [pg[r.i], pg[r.i - 1]]; saveHome(); renderAll(); } });
      if (r.i < pg.length - 1) items.push({ g: ['plus', 'violet'], txt: '▶', l: 'Déplacer vers la droite', f: () => { [pg[r.i + 1], pg[r.i]] = [pg[r.i], pg[r.i + 1]]; saveHome(); renderAll(); } });
      items.push({ g: ['link', 'cyan'], l: r.p === 0 ? 'Envoyer vers Raccourcis' : 'Envoyer vers l\'accueil', f: () => { const [x] = pg.splice(r.i, 1); home.pages[r.p ? 0 : 1].push(x); saveHome(); renderAll(); } });
      items.push({ g: ['box', 'red'], l: 'Retirer de l\'écran', danger: 1, f: () => { pg.splice(r.i, 1); saveHome(); renderAll(); } });
    } else if (r.where === 'dock') {
      items.push({ g: ['apps', 'cyan'], l: 'Changer d\'appli', f: () => pickApp('Choisir une appli pour le dock', (id) => { home.dock[r.i] = { t: 'app', id }; saveHome(); renderAll(); }) });
      items.push({ g: ['box', 'red'], l: 'Vider cet emplacement', danger: 1, f: () => { home.dock[r.i] = null; saveHome(); renderAll(); } });
    }
  }
  function blankMenu(x, y) {
    menu([
      { g: ['gallery', 'violet'], l: 'Fond d\'écran…', f: () => openSettings('wall') },
      { g: ['plus', 'green'], l: 'Ajouter sur l\'accueil…', f: () => addMenu(curPage === 2 ? 1 : 0) },
      { g: ['speaker', 'pink'], l: 'PupSon', f: openPupSon },
      { g: ['gear', 'violet'], l: 'PupRéglages', f: () => openSettings() },
      { hr: 1 },
      { g: ['gear', 'chrome'], l: 'Paramètres du téléphone', f: () => call('open', 'settings') },
    ], x, y, `<div class="mh"><div class="ico svg">${I('paw', 'pink', { shape: 'orb' })}</div><div><b>PuppyPhone</b><small>personnaliser l'écran</small></div></div>`);
  }

  // ------------------------------------------------------------------ fenêtres / menus / bulles
  const layer = $('#layer');
  const winStack = [];
  function openWin(o) {
    const veil = document.createElement('div');
    veil.className = 'veil';
    const w = document.createElement('div');
    w.className = 'win' + (o.max ? ' max' : '') + (o.cls ? ' ' + o.cls : '');
    if (o.color) w.style.setProperty('--wc', o.color);
    w.innerHTML = `<div class="tb">${o.icon || ''}<div class="tx"><div class="t chrome">${esc(o.title)}</div><div class="sub">${esc(o.sub || '')}</div></div><button class="xb" type="button" aria-label="Fermer">${XSVG}</button></div><div class="wb"></div><div class="wf"></div>`;
    layer.append(veil, w);
    const entry = { w, veil, onClose: o.onClose, close: () => closeEntry(entry) };
    entry.body = $('.wb', w);
    entry.set = (html) => { entry.body.innerHTML = html; };
    entry.foot = (html) => { const f = $('.wf', w); if (f) f.innerHTML = html; };
    entry.title = (t, s) => { $('.t', w).textContent = t; const sb = $('.sub', w); if (sb && s != null) sb.textContent = s; };
    if (o.body != null) entry.set(o.body);
    if (o.foot) entry.foot(o.foot);
    veil.onclick = entry.close;
    $('.xb', w).onclick = entry.close;
    if (!o.max) dragWin(w);
    winStack.push(entry);
    return entry;
  }
  function closeEntry(e) {
    const i = winStack.indexOf(e);
    if (i < 0) return;
    winStack.splice(i, 1);
    e.w.remove(); e.veil.remove();
    if (e.onClose) e.onClose();
  }
  function closeTop() { const e = winStack[winStack.length - 1]; if (e) { e.close(); return true; } return false; }
  function dragWin(w) {
    const tb = $('.tb', w);
    tb.addEventListener('pointerdown', (e) => {
      if (e.target.closest('.xb')) return;
      const r = w.getBoundingClientRect();
      w.classList.add('free');
      w.style.left = r.left + 'px'; w.style.top = r.top + 'px';
      const ox = e.clientX - r.left, oy = e.clientY - r.top;
      tb.setPointerCapture(e.pointerId);
      const mv = (ev) => {
        w.style.left = Math.max(-r.width + 80, Math.min(innerWidth - 80, ev.clientX - ox)) + 'px';
        w.style.top = Math.max(0, Math.min(innerHeight - 60, ev.clientY - oy)) + 'px';
      };
      const up = () => { tb.removeEventListener('pointermove', mv); tb.removeEventListener('pointerup', up); };
      tb.addEventListener('pointermove', mv);
      tb.addEventListener('pointerup', up);
    });
  }

  let menuEl = null;
  function closeMenu() { if (menuEl) { menuEl.veil.remove(); menuEl.m.remove(); menuEl = null; return true; } return false; }
  function menu(items, x, y, head) {
    closeMenu();
    const veil = document.createElement('div');
    veil.className = 'veil'; veil.style.background = 'rgba(0,0,0,.25)'; veil.style.backdropFilter = 'none';
    const m = document.createElement('div');
    m.className = 'menu';
    m.innerHTML = (head || '') + items.map((it, i) => {
      if (it.hr) return '<hr>';
      if (it.sec) return `<div class="msec">${esc(it.sec)}</div>`;
      const ic = it.img ? `<span class="mi"><img src="${esc(it.img)}" alt="" onerror="this.style.opacity=.2"></span>` : I(it.g[0], it.g[1]);
      return `<button type="button" data-i="${i}" class="${it.danger ? 'danger' : ''}">${ic}<span style="flex:1">${esc(it.l)}</span>${it.txt ? `<b>${it.txt}</b>` : ''}${it.pin ? `<span data-pin="${i}" style="width:30px;height:30px;display:block">${I('home', 'pink', { shape: 'none' })}</span>` : ''}</button>`;
    }).join('');
    layer.append(veil, m);
    const mw = m.offsetWidth, mh = m.offsetHeight;
    m.style.left = Math.max(10, Math.min(innerWidth - mw - 10, x - mw / 2)) + 'px';
    m.style.top = Math.max(10, Math.min(innerHeight - mh - 10, y - 20)) + 'px';
    veil.onclick = closeMenu;
    m.addEventListener('click', (e) => {
      const pin = e.target.closest('[data-pin]');
      const b = e.target.closest('[data-i]');
      if (!b) return;
      const it = items[+b.dataset.i];
      closeMenu();
      if (pin && it.pin) it.pin(); else if (it.f) it.f();
    });
    menuEl = { m, veil };
  }

  function toast(title, text, iconHTML, ms) {
    const t = document.createElement('div');
    t.className = 'toast';
    t.innerHTML = `${iconHTML || I('paw', 'pink', { shape: 'orb' })}<div><b class="chrome">${esc(title)}</b><span>${esc(text)}</span></div>`;
    $('#toasts').append(t);
    t.onclick = () => t.remove();
    setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 320); }, ms || 3500);
  }
  function haptic(k) { call('haptic', k || 'tap'); }

  // ------------------------------------------------------------------ dossiers flottants
  function openFolder(cat) {
    const c = Flair.cat(cat);
    const col = (PAL[c.c] || PAL.pink)[1];
    const w = openWin({ title: c.n, sub: '', icon: I(c.g, c.c, { shape: 'orb' }), color: col, cls: 'folderwin' });
    w.refresh = () => {
      const list = appsIn(cat);
      w.title(c.n, list.length + (list.length > 1 ? ' applis' : ' appli') + ' · dossier flottant');
      const note = cat === 'sort'
        ? 'Flair 🐾 hésite sur ces applis. Appui long → « Ranger dans… » : il retiendra ton choix.'
        : 'Rangé automatiquement par Flair 🐾 · appui long pour déplacer une appli';
      w.set(list.length
        ? `<div class="grid">${list.map((a) => itemHTML({ t: 'app', id: a.id }, 'f:' + a.id)).join('')}</div><div class="wnote">${note}</div>`
        : `<div class="empty">${I(c.g, c.c)}Dossier vide pour l'instant.</div>`);
      w.foot(onHome({ t: 'folder', cat }) ? '' : `<button class="ab small glass" data-act="pin" type="button">${I('home', 'pink', { shape: 'none' })} Mettre sur l'accueil</button>`);
      const pb = $('[data-act=pin]', w.w);
      if (pb) pb.onclick = () => { addToHome({ t: 'folder', cat }, 0); w.refresh(); };
    };
    w.refresh();
  }
  function moveApp(a) {
    const cur = catOf(a);
    const w = openWin({ title: 'Ranger ' + a.label, sub: 'choisis le dossier', icon: I('folder', 'amber') });
    w.set(`<div class="choices c3">${Flair.CATS.map((c) => `<button class="choice ${c.id === cur ? 'on' : ''}" data-c="${c.id}" type="button">${I(c.g, c.c, { shape: 'orb' })}<b>${esc(c.n)}</b></button>`).join('')}</div>
      ${overrides[a.id] ? '<button class="ab small glass wide" data-c="__auto" type="button" style="margin-top:12px">Rendre la main à Flair (tri auto)</button>' : ''}`);
    w.body.addEventListener('click', (e) => {
      const b = e.target.closest('[data-c]');
      if (!b) return;
      const cat = b.dataset.c;
      if (cat === '__auto') delete overrides[a.id];
      else { overrides[a.id] = cat; learn = Flair.learnFrom(a, cat, learn); S.set('learn', learn); }
      S.set('cats', overrides);
      flairCache.clear();
      w.close();
      renderAll();
      const c = Flair.cat(catOf(a));
      toast('Rangé !', `${a.label} → ${c.n}. Flair s'en souviendra pour les applis du même éditeur.`, `<div class="ico svg">${I(c.g, c.c, { shape: 'orb' })}</div>`);
    });
  }

  // ------------------------------------------------------------------ choisir une appli / ajouter
  function pickApp(title, cb) {
    const w = openWin({ title, sub: 'touche une appli', icon: I('apps', 'cyan'), max: true });
    w.set(`<label class="vsearch" style="margin-bottom:12px"><span class="pw">${I('search', 'cyan', { shape: 'none' })}</span><input class="pq" placeholder="Chercher…"></label><div class="grid pg"></div>`);
    const draw = (q) => {
      const n = norm(q);
      $('.pg', w.w).innerHTML = apps.filter((a) => !n || norm(a.label).includes(n)).map((a) => itemHTML({ t: 'app', id: a.id }, 'pick:' + a.id)).join('');
    };
    draw('');
    $('.pq', w.w).oninput = (e) => draw(e.target.value);
    w.body.addEventListener('click', (e) => {
      const b = e.target.closest('[data-k^="pick:"]');
      if (!b) return;
      w.close();
      cb(b.dataset.k.slice(5));
    });
  }
  function addMenu(p) {
    const w = openWin({ title: 'Ajouter', sub: p === 0 ? 'sur l\'accueil' : 'sur la page Raccourcis', icon: I('plus', 'green') });
    const opts = [
      ['app', 'apps', 'cyan', 'Une appli', 'n\'importe laquelle'],
      ['folder', 'folder', 'amber', 'Un dossier', 'flottant, trié par Flair'],
      ['sc', 'link', 'violet', 'Raccourci d\'appli', 'ex : « nouveau message »'],
      ['link', 'globe', 'blue', 'Lien web', 'ouvre un site'],
      ['son', 'speaker', 'pink', 'PupSon', 'coupe / remet le son'],
      ['settings', 'gear', 'violet', 'PupRéglages', 'le panneau puppy'],
    ];
    w.set(`<div class="choices">${opts.map((o) => `<button class="choice" data-o="${o[0]}" type="button">${I(o[1], o[2])}<b>${o[3]}</b><small>${o[4]}</small></button>`).join('')}</div>`);
    w.body.addEventListener('click', (e) => {
      const b = e.target.closest('[data-o]');
      if (!b) return;
      const o = b.dataset.o;
      w.close();
      if (o === 'app') pickApp('Ajouter une appli', (id) => addToHome({ t: 'app', id }, p));
      if (o === 'folder') pickFolder((cat) => addToHome({ t: 'folder', cat }, p));
      if (o === 'sc') pickApp('Raccourci de quelle appli ?', (id) => pickShortcut(byId.get(id), p));
      if (o === 'link') linkForm(p);
      if (o === 'son' || o === 'settings') addToHome({ t: 'pup', app: o }, p);
    });
  }
  function pickFolder(cb) {
    const w = openWin({ title: 'Quel dossier ?', icon: I('folder', 'amber') });
    w.set(`<div class="choices c3">${Flair.CATS.map((c) => `<button class="choice" data-c="${c.id}" type="button">${I(c.g, c.c, { shape: 'orb' })}<b>${esc(c.n)}</b><small>${appsIn(c.id).length}</small></button>`).join('')}</div>`);
    w.body.addEventListener('click', (e) => { const b = e.target.closest('[data-c]'); if (b) { w.close(); cb(b.dataset.c); } });
  }
  function pickShortcut(a, p) {
    if (!a) return;
    const r = J(call('shortcuts', a.pkg), []);
    const w = openWin({ title: 'Raccourcis · ' + a.label, icon: I('link', 'violet') });
    if (r && r.denied) {
      w.set(`<div class="warnbox">Android ne donne les raccourcis d'applis qu'au lanceur par défaut. Fais de PuppyPhone ton écran d'accueil, puis réessaie 🐾</div>`);
      w.foot(`<button class="ab" data-act="def" type="button">${I('paw', 'chrome', { shape: 'none' })} Devenir mon accueil</button>`);
      $('[data-act=def]', w.w).onclick = () => { call('askDefault'); w.close(); };
      return;
    }
    if (!r || !r.length) { w.set(`<div class="empty">${I('link', 'violet')}Cette appli ne propose pas de raccourcis.</div>`); return; }
    w.set(r.map((s, i) => `<button class="choice" data-s="${i}" type="button" style="flex-direction:row;justify-content:flex-start;gap:12px;width:100%;margin-bottom:8px;padding:10px 12px"><span class="ico framed" style="--is:40px"><img src="${MOCK ? N.iconUrl(a.id) : `https://pup.local/sicon?pkg=${encodeURIComponent(s.pkg)}&sid=${encodeURIComponent(s.sid)}`}" alt=""></span><b style="font-size:13px">${esc(s.label)}</b></button>`).join(''));
    w.body.addEventListener('click', (e) => { const b = e.target.closest('[data-s]'); if (!b) return; const s = r[+b.dataset.s]; w.close(); addToHome({ t: 'sc', pkg: s.pkg, sid: s.sid, label: s.label }, p); });
  }
  function linkForm(p) {
    let color = 'cyan';
    const w = openWin({ title: 'Lien web', icon: I('globe', 'blue') });
    const cols = ['pink', 'cyan', 'violet', 'amber', 'green', 'red'];
    w.set(`<span class="lbl">Nom</span><input class="vin" id="ln" placeholder="Ex : Folsom Berlin">
      <span class="lbl">Adresse</span><input class="vin" id="lu" placeholder="https://…" inputmode="url">
      <span class="lbl">Couleur</span><div class="swatches">${cols.map((c) => `<button class="swatch ${c === color ? 'on' : ''}" data-col="${c}" style="--s:${PAL[c][1]}" type="button"></button>`).join('')}</div>`);
    w.foot(`<button class="ab glass" data-act="x" type="button">Annuler</button><button class="ab green" data-act="ok" type="button">Ajouter</button>`);
    w.body.addEventListener('click', (e) => { const b = e.target.closest('[data-col]'); if (!b) return; color = b.dataset.col; $$('.swatch', w.w).forEach((s) => s.classList.toggle('on', s === b)); });
    $('[data-act=x]', w.w).onclick = w.close;
    $('[data-act=ok]', w.w).onclick = () => {
      let u = $('#lu', w.w).value.trim();
      if (!u) return;
      if (!/^[a-z]+:/i.test(u)) u = 'https://' + u;
      const label = $('#ln', w.w).value.trim() || u.replace(/^https?:\/\/(www\.)?/, '').split('/')[0];
      w.close();
      addToHome({ t: 'link', url: u, label, color }, p);
    };
  }

  // ------------------------------------------------------------------ tiroir
  let drawerOpen = false, tab = 'all';
  const drawer = $('#drawer');
  function openDrawer() {
    if (drawerOpen) return;
    drawerOpen = true;
    renderDrawer();
    drawer.classList.add('open');
    drawer.setAttribute('aria-hidden', 'false');
  }
  function closeDrawer() {
    if (!drawerOpen) return false;
    drawerOpen = false;
    drawer.classList.remove('open');
    drawer.setAttribute('aria-hidden', 'true');
    const q = $('#q'); q.value = ''; q.blur();
    return true;
  }
  function renderDrawer() {
    const q = norm($('#q').value);
    const body = $('#drbody');
    const list = apps.filter((a) => !q || norm(a.label).includes(q) || norm(a.pkg).includes(q));
    const cellOf = (a) => itemHTML({ t: 'app', id: a.id }, 'd:' + a.id);
    let html = '';
    if (q) {
      html = list.length ? `<div class="grid">${list.map(cellOf).join('')}</div>` : '';
      html += `<button class="ab c2 wide" id="websearch" type="button" style="margin-top:14px">${I('globe', 'blue', { shape: 'none' })} Chercher « ${esc($('#q').value)} » sur le web</button>`;
    } else if (tab === 'all') {
      let cur = '', grid = [];
      const flush = () => { if (grid.length) html += `<div class="letterbar">${cur}</div><div class="grid">${grid.join('')}</div>`; grid = []; };
      for (const a of list) {
        let L = norm(a.label)[0] || '#';
        if (!/[a-z]/.test(L)) L = '#';
        L = L.toUpperCase();
        if (L !== cur) { flush(); cur = L; }
        grid.push(cellOf(a));
      }
      flush();
    } else if (tab === 'folders') {
      for (const c of Flair.CATS) {
        const inC = list.filter((a) => catOf(a) === c.id);
        if (!inC.length) continue;
        html += `<div class="dr-sec">${I(c.g, c.c, { shape: 'orb' })}<span class="chrome">${esc(c.n)}</span><small style="font-family:var(--f-num);color:var(--muted)">${inC.length}</small></div><div class="grid">${inC.map(cellOf).join('')}</div>`;
      }
    } else {
      const nw = list.filter((a) => fresh[a.id]).sort((x, y) => fresh[y.id] - fresh[x.id]);
      const recent = list.filter((a) => !fresh[a.id]).sort((x, y) => (y.installed || 0) - (x.installed || 0)).slice(0, 12);
      if (nw.length) html += `<div class="dr-sec">${I('sparkle', 'amber', { shape: 'orb' })}<span class="chrome">Tout juste installées</span></div><div class="grid">${nw.map(cellOf).join('')}</div>`;
      else html += `<div class="empty">${I('sparkle', 'amber')}Rien de nouveau cette semaine.<br>Installe une appli : Flair la rangera tout seul 🐾</div>`;
      html += `<div class="dr-sec">${I('clock', 'cyan', { shape: 'orb' })}<span class="chrome">Installées récemment</span></div><div class="grid">${recent.map(cellOf).join('')}</div>`;
    }
    body.innerHTML = html;
    const ws = $('#websearch');
    if (ws) ws.onclick = () => { call('browse', '', $('#q').value); closeDrawer(); };
  }
  $('#q').addEventListener('input', renderDrawer);
  $('#q').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    const n = norm(e.target.value);
    const first = apps.find((a) => norm(a.label).startsWith(n)) || apps.find((a) => norm(a.label).includes(n));
    if (first && n) { closeDrawer(); launchApp(first.id); }
    else if (n) { call('browse', '', e.target.value); closeDrawer(); }
  });
  $('#tabs').addEventListener('click', (e) => {
    const b = e.target.closest('[data-tab]');
    if (!b) return;
    tab = b.dataset.tab;
    $$('#tabs button').forEach((x) => x.classList.toggle('on', x === b));
    renderDrawer();
    $('#drbody').scrollTop = 0;
  });
  $('#drclose').onclick = closeDrawer;
  let dts = null;
  drawer.addEventListener('touchstart', (e) => { dts = { y: e.touches[0].clientY, top: $('#drbody').scrollTop <= 0 }; }, { passive: true });
  drawer.addEventListener('touchend', (e) => { if (dts && dts.top && e.changedTouches[0].clientY - dts.y > 110) closeDrawer(); dts = null; }, { passive: true });

  // ------------------------------------------------------------------ PupSon
  function openPupSon() {
    const w = openWin({ title: 'PupSon', sub: 'son du fond d\'écran vidéo', icon: I('speaker', 'pink') });
    w.onStatus = w.refresh = () => {
      const has = wall.type === 'video';
      const m = !!wall.muted;
      w.set(`<div class="sonbig">
        <button class="sonbtn" id="sonb" type="button" style="--sc:${!has ? '#6a5a8a' : m ? '#ff4d5e' : 'var(--acc)'}">${I('speaker', 'chrome', { shape: 'none', muted: m || !has })}</button>
        <div class="sonstate chrome">${!has ? 'Pas de fond vidéo' : m ? 'SON COUPÉ' : 'SON EN LECTURE'}</div>
        <div class="eq ${has && !m ? '' : 'off'}"><i></i><i></i><i></i><i></i><i></i></div>
      </div>
      ${has ? `<span class="lbl">Volume du fond</span><input type="range" id="sonv" min="0" max="100" value="${Math.round((wall.volume == null ? 1 : wall.volume) * 100)}">
        <p style="margin-top:10px">Le son continue <b>même écran éteint</b>. Tu peux aussi le couper depuis la <b>notification</b>, la <b>tuile rapide PupSon</b> (réglages rapides → crayon → ajoute PupSon) ou l'icône <b>PupSon</b> du tiroir.</p>`
        : `<p>Choisis une vidéo MP4 (avec le son) comme fond d'écran : elle tournera en boucle sur l'accueil et le son continuera même écran éteint.</p>`}`);
      w.foot(has ? '' : `<button class="ab wide" data-act="pick" type="button">${I('video', 'chrome', { shape: 'none' })} Choisir une vidéo MP4</button>`);
      const sb = $('#sonb', w.w);
      sb.onclick = () => {
        if (!has) { call('setWallType', 'video'); return; }
        haptic(); wall.muted = !m; call('setWallMuted', wall.muted); w.refresh(); renderPower(); renderAll();
      };
      const v = $('#sonv', w.w);
      if (v) {
        const upd = () => v.style.setProperty('--v', v.value + '%');
        upd();
        v.oninput = () => { upd(); wall.volume = v.value / 100; call('setWallVolume', wall.volume); };
      }
      const pk = $('[data-act=pick]', w.w);
      if (pk) pk.onclick = () => call('setWallType', 'video');
    };
    w.refresh();
  }

  // ------------------------------------------------------------------ PupRéglages
  function openSettings(focus) {
    const w = openWin({ title: 'PupRéglages', sub: 'panneau de configuration puppy', icon: I('gear', 'violet'), max: true });
    const sw = (k, on) => `<button class="sw ${on ? 'on' : ''}" data-sw="${k}" type="button" aria-pressed="${on}"><i></i></button>`;
    w.onStatus = w.refresh = () => {
      const st = wall.type || 'neon';
      const isDef = !!status.isDefault;
      const sorted = appsIn('sort').length;
      w.set(`
      <div class="cp" style="--wc:var(--acc)">
        <div class="cp-h">${I('paw', 'pink', { shape: 'orb' })}<div><b class="chrome">Mode PuppyPhone</b><small>Transforme ton téléphone en vrai PuppyPhone : PuppyPhone remplace Nova comme écran d'accueil.</small></div></div>
        ${isDef ? `<div class="ok">${I('paw', 'green', { shape: 'none' }).replace('class="pi"', 'class="pi" style="width:24px;height:24px"')} PuppyPhone est ton écran d'accueil ✓</div>`
          : `<button class="ab wide" data-act="default" type="button">${I('home', 'chrome', { shape: 'none' })} Devenir mon écran d'accueil</button>
             <small style="color:var(--muted);font-size:12px">Si rien ne s'ouvre : Paramètres → Applications → Applis par défaut → Appli d'accueil → PuppyPhone.</small>`}
      </div>

      <div class="cp" id="cp-wall">
        <div class="cp-h">${I('gallery', 'violet')}<div><b>Fond d'écran</b><small>Néon animé, fond du téléphone, image ou vidéo MP4 avec le son.</small></div></div>
        <div class="choices">
          <button class="choice ${st === 'neon' ? 'on' : ''}" data-wall="neon" type="button">${I('sparkle', 'pink')}<b>Néon animé</b><small>lasers puppyplay</small></button>
          <button class="choice ${st === 'system' ? 'on' : ''}" data-wall="system" type="button">${I('home', 'blue')}<b>Fond du tél</b><small>celui d'Android</small></button>
          <button class="choice ${st === 'image' ? 'on' : ''}" data-wall="image" type="button">${I('gallery', 'amber')}<b>Image</b><small>${esc(wall.image || 'choisir…')}</small></button>
          <button class="choice ${st === 'video' ? 'on' : ''}" data-wall="video" type="button">${I('video', 'red')}<b>Vidéo MP4 🔊</b><small>${esc(wall.video || 'choisir…')}</small></button>
        </div>
        <div style="display:flex;gap:8px">
          <button class="ab small glass" style="flex:1" data-act="pickimg" type="button">Autre image…</button>
          <button class="ab small glass" style="flex:1" data-act="pickvid" type="button">Autre vidéo…</button>
        </div>
      </div>

      ${st === 'video' ? `<div class="cp">
        <div class="cp-h">${I('speaker', 'pink', { muted: wall.muted })}<div><b>Son du fond vidéo</b><small>Continue de jouer écran éteint. Coupe-le ici, avec PupSon, la notification ou la tuile rapide.</small></div></div>
        <div class="row"><span>Son du fond<small>${wall.muted ? 'coupé' : 'en lecture'}</small></span>${sw('wallsound', !wall.muted)}</div>
        <span class="lbl" style="margin-top:0">Volume</span><input type="range" id="setv" min="0" max="100" value="${Math.round((wall.volume == null ? 1 : wall.volume) * 100)}">
      </div>` : ''}

      <div class="cp">
        <div class="cp-h">${I('apps', 'cyan')}<div><b>Icônes 3D</b><small>Style Android 4 / Vista, version puppyplay.</small></div></div>
        <div class="choices c3">
          <button class="choice ${cfg.iconStyle === 'framed' ? 'on' : ''}" data-is="framed" type="button"><div class="ico framed" style="--is:46px">${I('paw', 'pink', { shape: 'none' })}</div><b>Verre 3D</b><small>logo d'origine sous verre</small></button>
          <button class="choice ${cfg.iconStyle === 'drawn' ? 'on' : ''}" data-is="drawn" type="button">${I('gamepad', 'green')}<b>Tout dessiné</b><small>pictos puppy</small></button>
          <button class="choice ${cfg.iconStyle === 'raw' ? 'on' : ''}" data-is="raw" type="button">${I('globe', 'blue', { shape: 'none' })}<b>Brillant</b><small>forme d'origine</small></button>
        </div>
        <div class="row"><span>Forme<small>${cfg.iconShape === 'orb' ? 'bulle Vista' : 'tuile KitKat'}</small></span>
          <div class="seg" style="width:180px"><button class="${cfg.iconShape !== 'orb' ? 'on' : ''}" data-shape="tile" type="button">Tuile</button><button class="${cfg.iconShape === 'orb' ? 'on' : ''}" data-shape="orb" type="button">Bulle</button></div></div>
        <div class="row"><span>Pack d'icônes Puppy<small>redessine Téléphone, SMS, Photo, Réglages…</small></span>${sw('pack', cfg.pack)}</div>
        <div class="row"><span>Noms sous les icônes</span>${sw('labels', cfg.labels)}</div>
        <div class="row"><span>Colonnes</span><div class="seg" style="width:180px">${[3, 4, 5].map((n) => `<button class="${cfg.cols === n ? 'on' : ''}" data-cols="${n}" type="button">${n}</button>`).join('')}</div></div>
        <span class="lbl" style="margin-top:0">Taille des icônes</span><input type="range" id="setsz" min="44" max="72" value="${cfg.size}">
      </div>

      <div class="cp">
        <div class="cp-h">${I('pulse', 'green')}<div><b>Fluidité</b><small>Le GPU est déjà utilisé à fond. Ici tu choisis combien d'effets il doit calculer.</small></div></div>
        <div class="choices c3">
          <button class="choice ${cfg.fx === 'turbo' ? 'on' : ''}" data-fx="turbo" type="button">${I('torch', 'green', { on: true })}<b>Turbo</b><small>ultra fluide, effets figés</small></button>
          <button class="choice ${cfg.fx === 'auto' ? 'on' : ''}" data-fx="auto" type="button">${I('paw', 'pink')}<b>Équilibré</b><small>animé et fluide</small></button>
          <button class="choice ${cfg.fx === 'max' ? 'on' : ''}" data-fx="max" type="button">${I('sparkle', 'violet')}<b>Max</b><small>+ flou de verre réel</small></button>
        </div>
      </div>

      <div class="cp">
        <div class="cp-h">${I('sparkle', 'amber')}<div><b>Couleur néon</b><small>La lueur de tout PuppyPhone.</small></div></div>
        <div class="swatches">${Object.keys(ACCENTS).map((k) => `<button class="swatch ${cfg.accent === k ? 'on' : ''}" data-acc="${k}" style="--s:${ACCENTS[k][0]}" type="button"></button>`).join('')}</div>
      </div>

      <div class="cp">
        <div class="cp-h">${I('box', 'orange')}<div><b>Flair · tri intelligent hors ligne</b><small>Chaque appli installée est rangée toute seule dans le bon dossier flottant, sans internet ni jetons. En cas de doute → « À trier ». Quand tu corriges, Flair apprend.</small></div></div>
        <div class="row"><span>Dans « À trier »<small>${sorted} appli${sorted > 1 ? 's' : ''}</small></span><button class="ab small amber" data-act="opensort" type="button">Voir</button></div>
        <div class="row"><span>Mes corrections<small>${Object.keys(overrides).length} appli(s) rangée(s) à la main</small></span><button class="ab small red" data-act="forget" type="button">Oublier</button></div>
      </div>

      <div class="cp">
        <div class="cp-h">${I('clock', 'cyan')}<div><b>Widgets</b><small>Comme à l'époque, version puppy.</small></div></div>
        <div class="row"><span>Recherche</span>${sw('showSearch', cfg.showSearch)}</div>
        <div class="row"><span>Horloge flip + batterie</span>${sw('showClock', cfg.showClock)}</div>
        <div class="row"><span>Contrôle Wi-Fi · BT · 4G · Lampe · Son</span>${sw('showPower', cfg.showPower)}</div>
      </div>

      <div class="cp">
        <div class="cp-h">${I('home', 'pink')}<div><b>Écran d'accueil</b><small>Remettre la disposition de départ (tes corrections de tri sont gardées).</small></div></div>
        <button class="ab small glass" data-act="resethome" type="button">Réorganiser l'accueil par défaut</button>
      </div>

      <div class="hero" style="padding-top:6px"><div class="logo neon" style="font-size:22px">PUPPY<span>PHONE</span></div><div class="kicker">v1 · lanceur · 100 % hors ligne · 🐾</div></div>`);

      w.body.onclick = (e) => {
        const t = e.target.closest('button');
        if (!t) return;
        const d = t.dataset;
        if (d.act === 'default') call('askDefault');
        if (d.wall) { if (d.wall === 'video' || d.wall === 'image') call('setWallType', d.wall); else { call('setWallType', d.wall); wall.type = d.wall; applyWall(); w.refresh(); } }
        if (d.act === 'pickimg') call('pickWall', 'image');
        if (d.act === 'pickvid') call('pickWall', 'video');
        if (d.sw) {
          if (d.sw === 'wallsound') { wall.muted = !wall.muted; call('setWallMuted', wall.muted); renderPower(); }
          else { cfg[d.sw] = !cfg[d.sw]; saveCfg(); renderAll(); }
          w.refresh();
        }
        if (d.is) { cfg.iconStyle = d.is; saveCfg(); renderAll(); w.refresh(); }
        if (d.shape) { cfg.iconShape = d.shape; saveCfg(); renderAll(); w.refresh(); }
        if (d.cols) { cfg.cols = +d.cols; saveCfg(); renderAll(); w.refresh(); }
        if (d.fx) { cfg.fx = d.fx; saveCfg(); w.refresh(); }
        if (d.acc) { cfg.accent = d.acc; saveCfg(); renderAll(); w.refresh(); }
        if (d.act === 'opensort') openFolder('sort');
        if (d.act === 'forget') { overrides = {}; learn = {}; S.set('cats', overrides); S.set('learn', learn); flairCache.clear(); renderAll(); w.refresh(); toast('Flair remis à zéro', 'Toutes les applis sont re-triées automatiquement.'); }
        if (d.act === 'resethome') { home = defaultHome(); saveHome(); renderAll(); toast('Accueil réorganisé', 'Disposition puppy par défaut.'); }
      };
      const rng = (id, fn) => { const r = $('#' + id, w.w); if (!r) return; const min = +r.min, max = +r.max; const u = () => r.style.setProperty('--v', ((r.value - min) / (max - min) * 100) + '%'); u(); r.oninput = () => { u(); fn(+r.value); }; };
      rng('setv', (v) => { wall.volume = v / 100; call('setWallVolume', wall.volume); });
      rng('setsz', (v) => { cfg.size = v; saveCfg(); });
      $('#setsz', w.w) && ($('#setsz', w.w).onchange = () => renderAll());
    };
    w.refresh();
    if (focus === 'wall') setTimeout(() => { const c = $('#cp-wall', w.w); if (c) c.scrollIntoView({ block: 'start' }); }, 50);
  }

  // ------------------------------------------------------------------ bienvenue
  function welcome() {
    const w = openWin({ title: 'Bienvenue', sub: 'ton téléphone devient un puppyphone', icon: I('paw', 'pink', { shape: 'orb' }) });
    w.set(`<div class="hero"><div class="logo neon">PUPPY<span>PHONE</span></div><div class="kicker">Woof ! 🐾</div></div>
      <div class="steps">
        <div>${I('folder', 'amber', { shape: 'orb' })}<span><b>La Niche</b> (glisse à gauche) : tes applis rangées toutes seules dans des dossiers flottants par <b>Flair</b>, le tri hors ligne.</span></div>
        <div>${I('paw', 'pink', { shape: 'orb' })}<span>L'<b>orbe</b> du dock (ou glisse vers le haut) ouvre toutes tes applis.</span></div>
        <div>${I('video', 'red', { shape: 'orb' })}<span><b>Fond vidéo MP4 avec le son</b> : appui long sur l'écran → Fond d'écran. Coupe le son avec <b>PupSon</b>.</span></div>
        <div>${I('plus', 'green', { shape: 'orb' })}<span>Appui long sur une icône pour la ranger, l'épingler ou voir ses raccourcis.</span></div>
      </div>`);
    w.foot(`<button class="ab glass" data-act="later" type="button">Plus tard</button><button class="ab" data-act="def" type="button">Devenir mon accueil</button>`);
    const done = () => { cfg.welcomed = true; saveCfg(); w.close(); };
    $('[data-act=later]', w.w).onclick = done;
    $('[data-act=def]', w.w).onclick = () => { call('askDefault'); done(); };
  }

  // ------------------------------------------------------------------ pont natif
  window.PupNative = {
    on(ev, data) {
      if (ev === 'resume') { document.body.classList.remove('paused'); refreshApps('resume'); updateStatus(); takePins(); tick(); }
      else if (ev === 'pause') document.body.classList.add('paused');
      else if (ev === 'apps') refreshApps(data);
      else if (ev === 'status') updateStatus();
      else if (ev === 'wall') { wall = J(data, wall) || wall; applyWall(); renderPower(); const t = winStack[winStack.length - 1]; if (t && t.refresh) t.refresh(); }
      else if (ev === 'home') { closeMenu(); while (closeTop()); closeDrawer(); goPage(0); }
    },
    back() {
      if (closeMenu()) return true;
      if (closeTop()) return true;
      if (closeDrawer()) return true;
      if (curPage !== 0) { goPage(0); return true; }
      return true;
    },
    insets(t, b) {
      const r = document.documentElement.style;
      r.setProperty('--st', Math.max(t, 20) + 'px');
      r.setProperty('--sb', Math.max(b, 8) + 'px');
    },
  };

  // ------------------------------------------------------------------ démarrage
  $('#searchpaw').innerHTML = I('paw', 'pink', { shape: 'none' });
  $('#drpaw').innerHTML = I('search', 'cyan', { shape: 'none' });
  $('#searchgo').innerHTML = I('search', 'chrome', { shape: 'none' });
  $('#nicheico').innerHTML = I('paw', 'pink', { shape: 'orb' });
  $('#scico').innerHTML = I('link', 'cyan', { shape: 'orb' });
  $('#drclose').innerHTML = `<span class="xb" style="position:static">${XSVG}</span>`;
  $('#searchbar').onclick = () => { openDrawer(); setTimeout(() => $('#q').focus(), 350); };
  $('#wclock').addEventListener('click', (e) => { if (e.target.closest('#batt')) call('open', 'battery'); else call('open', 'clock'); });
  $('#wpower').addEventListener('click', (e) => { const b = e.target.closest('[data-pw]'); if (!b) return; haptic(); if (b.dataset.pw === 'wallsound' && wall.type !== 'video') return openPupSon(); call('toggle', b.dataset.pw); });
  $('#wpower').addEventListener('contextmenu', (e) => { const b = e.target.closest('[data-pw]'); if (b) call('open', b.dataset.pw === 'wallsound' ? 'sound' : b.dataset.pw); });

  applyCfg();
  loadDefaults();
  wall = J(call('wallInfo'), {}) || {};
  updateStatus();
  refreshApps('force');
  renderDots();
  tick();
  setInterval(tick, 1000);
  setInterval(() => { if (!document.hidden && !document.body.classList.contains('paused')) updateStatus(); }, 15000);
  if (!cfg.welcomed) setTimeout(welcome, 500);
})();
