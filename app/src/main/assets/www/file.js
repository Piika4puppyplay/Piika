/* PupFile — explorateur de fichiers façon CX, thème puppyplay. Volumes à la Linux : hda, sda, sdb… et des gamelles. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon, PAL = PupIcons.PAL;
  const ic = (g, c, sh) => I(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const norm = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Files;
  const F = window.Files || mockF();
  const fc = (fn, ...a) => { try { return F[fn] ? F[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const haptic = () => fc('haptic');
  const LS = { get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } }, set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { } } };
  const XSVG = '<svg viewBox="0 0 20 20"><path d="M4 4L16 16M16 4L4 16" stroke="#fff" stroke-width="3.4" stroke-linecap="round"/></svg>';
  const fmtSize = (b) => { b = +b || 0; if (b < 1024) return b + ' o'; const u = ['Ko', 'Mo', 'Go', 'To']; let i = -1; do { b /= 1024; i++; } while (b >= 1024 && i < 3); return (b < 10 ? b.toFixed(1) : Math.round(b)).toString().replace('.', ',') + ' ' + u[i]; };
  const fmtDate = (ms) => { const d = new Date(ms); return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' }) + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }); };
  const plural = (n, a, b) => n + ' ' + (n > 1 ? b : a);
  const base = (p) => String(p).split('/').pop();
  const dirOf = (p) => String(p).replace(/\/[^/]*$/, '') || '/';
  const extOf = (n) => { const i = n.lastIndexOf('.'); return i <= 0 ? '' : n.slice(i + 1).toLowerCase(); };

  // =================================================================== VOLUMES
  const KIND = { food: { c: PAL.amber[1], g: 'bowl' }, water: { c: PAL.cyan[1], g: 'bowl' }, treat: { c: PAL.pink[1], g: 'bone' } };
  let VOLS = [];
  const loadVols = () => { VOLS = J(fc('vols'), []) || []; };
  const volOf = (p) => VOLS.filter((v) => p === v.root || String(p).startsWith(v.root + '/')).sort((a, b) => b.root.length - a.root.length)[0];
  const disp = (p) => { const v = volOf(p); return v ? '/' + v.dev + p.slice(v.root.length) : p; };

  /** Gamelle en SVG, remplie selon l'occupation. */
  function bowlSvg(kind, pct) {
    const k = KIND[kind] || KIND.food, id = 'b' + Math.random().toString(36).slice(2, 8);
    pct = Math.max(0, Math.min(1, pct || 0));
    const top = 86 - pct * 44;
    const body = 'M8 42H112L101 84Q99 91 90 91H30Q21 91 19 84Z';
    let fill = '';
    if (kind === 'water') {
      fill = `<rect x="0" y="${top}" width="120" height="60" fill="url(#${id}w)"/><path d="M0 ${top}Q15 ${top - 4} 30 ${top}T60 ${top}T90 ${top}T120 ${top}V${top + 6}H0Z" fill="#c4fbff" opacity=".7"><animateTransform attributeName="transform" type="translate" values="0 0;-30 0;0 0" dur="4s" repeatCount="indefinite"/></path>`;
    } else if (kind === 'treat') {
      fill = `<rect x="0" y="${top + 4}" width="120" height="60" fill="#5e1a3c"/>` + [14, 34, 54, 74, 94].map((x, i) => `<g transform="translate(${x} ${top - 2 + (i % 2) * 5}) rotate(${i * 37 - 40})"><rect x="-10" y="-3" width="20" height="6" rx="3" fill="#ffe7f3"/><circle cx="-10" cy="-3" r="4" fill="#ffe7f3"/><circle cx="-10" cy="3" r="4" fill="#ffe7f3"/><circle cx="10" cy="-3" r="4" fill="#ffe7f3"/><circle cx="10" cy="3" r="4" fill="#ffe7f3"/></g>`).join('');
    } else {
      fill = `<rect x="0" y="${top + 3}" width="120" height="60" fill="#6e3c00"/>` + Array.from({ length: 22 }, (_, i) => `<circle cx="${8 + (i * 37) % 106}" cy="${top + 2 + (i * 13) % 9}" r="${4 + (i % 3)}" fill="${['#c47a26', '#a85c12', '#e09a3e'][i % 3]}" stroke="#3e2000" stroke-width="1"/>`).join('');
    }
    return `<svg class="bowl" viewBox="0 0 120 96" xmlns="http://www.w3.org/2000/svg">
      <defs><linearGradient id="${id}g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".9"/><stop offset=".25" stop-color="${k.c}"/><stop offset="1" stop-color="#1a0b2e"/></linearGradient>
      <linearGradient id="${id}w" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#29e6ff"/><stop offset="1" stop-color="#03506a"/></linearGradient>
      <clipPath id="${id}c"><path d="${body}"/></clipPath></defs>
      <ellipse cx="60" cy="92" rx="50" ry="5" fill="#000" opacity=".5"/>
      <path d="${body}" fill="url(#${id}g)" stroke="#fff" stroke-opacity=".7" stroke-width="1.5"/>
      <g clip-path="url(#${id}c)" opacity=".95">${fill}</g>
      <ellipse cx="60" cy="42" rx="52" ry="8" fill="none" stroke="#fff" stroke-width="3" opacity=".85"/>
      <path d="M18 50Q22 76 34 86" stroke="#fff" stroke-width="4" stroke-linecap="round" opacity=".35" fill="none"/>
      <g transform="translate(60 70)" fill="#fff" opacity=".85"><ellipse cx="0" cy="4" rx="7" ry="6"/><circle cx="-8" cy="-4" r="3"/><circle cx="-3" cy="-8" r="3"/><circle cx="3" cy="-8" r="3"/><circle cx="8" cy="-4" r="3"/></g>
      <text x="60" y="${Math.max(20, top - 8)}" text-anchor="middle" font-family="Share Tech Mono,monospace" font-size="13" fill="#fff" stroke="#000" stroke-width="3" paint-order="stroke">${Math.round(pct * 100)}%</text>
    </svg>`;
  }

  // =================================================================== TYPES DE FICHIERS
  const TYPES = [
    [/^(jpe?g|png|gif|webp|bmp|heic|heif|svg|dng|raw|avif)$/, 'image', 'gallery', 'pink'],
    [/^(mp4|mkv|webm|avi|mov|3gp|m4v|ts|wmv|flv)$/, 'video', 'film', 'red'],
    [/^(mp3|flac|wav|ogg|opus|m4a|aac|wma|amr|mid|midi|aiff?)$/, 'audio', 'music', 'violet'],
    [/^apk$/, 'apk', 'apps', 'green'],
    [/^(zip|rar|7z|tar|gz|tgz|bz2|xz|zst|jar)$/, 'zip', 'zip', 'orange'],
    [/^pdf$/, 'pdf', 'doc', 'red'],
    [/^(docx?|odt|rtf|pages)$/, 'word', 'doc', 'blue'],
    [/^(xlsx?|ods|csv|numbers)$/, 'sheet', 'doc', 'green'],
    [/^(pptx?|odp|key)$/, 'slides', 'slides', 'orange'],
    [/^(txt|md|log|ini|conf|cfg|ya?ml|json|xml|lrc|srt|nfo|properties|toml|m3u8?)$/, 'text', 'edit', 'cyan'],
    [/^(html?|css|js|ts|py|java|kt|c|cpp|h|sh|gradle|php|rb|go|rs)$/, 'code', 'wrench', 'violet'],
    [/^(ttf|otf|woff2?)$/, 'font', 'sparkle', 'gold'],
  ];
  const SPECIAL = { Download: ['download', 'cyan'], DCIM: ['camera', 'pink'], Pictures: ['gallery', 'pink'], Music: ['music', 'violet'], Movies: ['film', 'red'], Documents: ['doc', 'blue'], Android: ['apps', 'green'], Alarms: ['clock', 'amber'], Ringtones: ['ringer', 'amber'], Podcasts: ['mic', 'violet'], Notifications: ['ringer', 'amber'], Recordings: ['mic', 'red'] };
  function typeOf(n, d) { if (d) return 'dir'; const e = extOf(n); for (const t of TYPES) if (t[0].test(e)) return t[1]; return 'other'; }
  function iconHtml(it) {
    const t = typeOf(it.n, it.d);
    if (t === 'dir') {
      const s = SPECIAL[it.n];
      return `<span class="fic">${ic('folder', 'amber')}${s ? `<span style="position:absolute;right:-2px;bottom:-2px;width:22px;height:22px">${ic(s[0], s[1])}</span>` : ''}</span>`;
    }
    const tt = TYPES.find((x) => x[1] === t);
    const g = tt ? ic(tt[2], tt[3]) : ic('doc', 'chrome');
    const th = !MOCK && (t === 'image' || t === 'video' || t === 'audio' || t === 'apk') ? `<img class="th" src="https://pupfile.local/thumb?p=${encodeURIComponent(it.p)}&s=128" loading="lazy" decoding="async" alt="" onerror="this.remove()">` : '';
    const play = t === 'video' ? `<span class="play">${ic('play', 'chrome', 'none')}</span>` : '';
    return `<span class="fic">${g}${th}${play}</span>`;
  }

  // =================================================================== ÉTAT
  let tab = 'local';
  let stack = [{ v: 'home' }];
  const top = () => stack[stack.length - 1];
  let items = [];          // éléments affichés (tous avec .p)
  let sel = new Set();
  let clip = null;         // { paths, move }
  let filterQ = '';
  let hidden = LS.get('pf_hidden', false);
  let sortK = LS.get('pf_sort', 'name'), sortDesc = LS.get('pf_desc', false);
  const gridFor = (v) => LS.get('pf_grid_' + v, v === 'lib:images' || v === 'lib:videos');
  let FAV = LS.get('pf_fav', null);

  function go(v) { exitSelect(); filterQ = ''; stack.push(v); render(true); }
  function back() { if (stack.length > 1) { exitSelect(); filterQ = ''; stack.pop(); render(true); return true; } return false; }
  function setTab(t) { tab = t; exitSelect(); filterQ = ''; stack = [{ v: t === 'local' ? 'home' : t === 'lib' ? 'libhome' : 'apps' }]; render(true); }

  // =================================================================== RENDU
  function render(scrollTop) {
    const v = top();
    const body = $('#fbody');
    if (scrollTop) body.scrollTop = 0;
    const deep = stack.length > 1;
    $('#fback').hidden = !deep;
    $('#flogo').hidden = deep;
    $('#ftabs').hidden = deep;
    $$('#ftabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
    $('#crumbs').hidden = v.v !== 'dir';
    $('#fab').hidden = v.v !== 'dir';
    document.body.classList.toggle('selecting', sel.size > 0);
    updateBars();
    if (!fc('perm')) {
      $('#fsub').textContent = 'Autorisation requise';
      body.innerHTML = `<div class="permcard">${ic('folder', 'amber')}<b>Accès à tous les fichiers</b><p>Pour fouiller dans toutes les gamelles (mémoire, carte SD, clé USB), Android demande l'autorisation « <b>Accès à tous les fichiers</b> ». Active-la pour PuppyPhone sur l'écran qui va s'ouvrir.</p><button class="ab wide" data-act="perm" type="button">${ic('check', 'chrome', 'none')}Autoriser</button></div>`;
      return;
    }
    switch (v.v) {
      case 'home': return renderHome();
      case 'libhome': return renderLibHome();
      case 'apps': return renderApps();
      case 'dir': return renderDir(v.path);
      case 'lib': return renderLib(v);
      case 'trash': return renderTrash();
      case 'analyze': return renderAnalyze(v);
      case 'search': return renderSearch(v);
    }
  }

  function renderHome() {
    loadVols();
    $('#fsub').textContent = plural(VOLS.length, 'gamelle', 'gamelles');
    const prim = VOLS.find((v) => v.kind === 'food') || VOLS[0];
    const trashN = VOLS.reduce((a, v) => a + (v.trash || 0), 0);
    if (!FAV && prim) { FAV = ['Download', 'DCIM', 'Documents', 'Music', 'Pictures', 'Movies'].map((d) => prim.root + '/' + d).filter((p) => fc('exists', p)); LS.set('pf_fav', FAV); }
    const fav = (FAV || []).filter((p) => fc('exists', p));
    $('#fbody').innerHTML = `<div class="sect">Gamelles</div>` +
      VOLS.map((v) => {
        const used = v.total - v.free, pct = v.total ? used / v.total : 0, k = KIND[v.kind] || KIND.food;
        return `<button class="bowlcard" data-open="${esc(v.root)}" style="--c:${k.c}" type="button">${bowlSvg(v.kind, pct)}<span class="btx"><b>${esc(v.label)}</b><span class="dev">/dev/${esc(v.dev)}</span><small>${fmtSize(v.free)} libres sur ${fmtSize(v.total)}</small><div class="gauge"><i class="${pct > .9 ? 'hot' : ''}" style="width:${(pct * 100).toFixed(1)}%"></i></div></span></button>`;
      }).join('') +
      `<div class="sect">Outils</div><div class="tiles">
        <button class="tile" data-go="trash" style="--c:${PAL.red[1]}" type="button"><span class="ti">${ic('trash', 'red')}</span><b>Poubelle</b><small>${plural(trashN, 'élément', 'éléments')}</small>${trashN ? `<span class="badge">${trashN}</span>` : ''}</button>
        <button class="tile" data-go="analyze" style="--c:${PAL.violet[1]}" type="button"><span class="ti">${ic('pulse', 'violet')}</span><b>Analyser</b><small>Qui mange tout ?</small></button>
        <button class="tile" data-tabgo="apps" style="--c:${PAL.green[1]}" type="button"><span class="ti">${ic('apps', 'green')}</span><b>Applis</b><small>Gérer, APK</small></button>
      </div>` +
      (fav.length ? `<div class="sect">Favoris</div><div class="tiles">${fav.map((p) => { const s = SPECIAL[base(p)] || ['folder', 'amber']; return `<button class="tile" data-open="${esc(p)}" data-fav="${esc(p)}" style="--c:${PAL[s[1]][1]}" type="button"><span class="ti">${ic(s[0], s[1])}</span><b>${esc(base(p))}</b><small>${esc(disp(dirOf(p)))}</small></button>`; }).join('')}</div>` : '');
  }

  const LIBCATS = [['images', 'Images', 'gallery', 'pink'], ['videos', 'Vidéos', 'film', 'red'], ['audio', 'Audio', 'music', 'violet'], ['docs', 'Documents', 'doc', 'blue'], ['downloads', 'Téléchargements', 'download', 'cyan'],
    ['apk', 'APK', 'apps', 'green'], ['archives', 'Archives', 'zip', 'orange'], ['recent', 'Récents (30 j)', 'clock', 'amber'], ['big', 'Gros fichiers', 'box', 'red']];
  function renderLibHome() {
    $('#fsub').textContent = 'Bibliothèque';
    $('#fbody').innerHTML = `<div class="sect">Par type</div><div class="tiles">${LIBCATS.map(([k, l, g, c]) => `<button class="tile" data-lib="${k}" style="--c:${PAL[c][1]}" type="button"><span class="ti">${ic(g, c)}</span><b>${l}</b></button>`).join('')}</div>`;
  }

  // ------------------------------------------------------------------ dossier
  function sortItems(l) {
    const dirs = l.filter((x) => x.d), files = l.filter((x) => !x.d);
    const cmp = { name: (a, b) => a.n.localeCompare(b.n, 'fr', { numeric: true, sensitivity: 'base' }), date: (a, b) => a.m - b.m, size: (a, b) => (a.s || a.c || 0) - (b.s || b.c || 0), type: (a, b) => extOf(a.n).localeCompare(extOf(b.n)) || a.n.localeCompare(b.n, 'fr', { numeric: true }) }[sortK] || ((a, b) => 0);
    const f = sortDesc ? (a, b) => -cmp(a, b) : cmp;
    return [...dirs.sort(f), ...files.sort(f)];
  }
  function toolbarHtml(count, extra) {
    const SORTS = { name: 'Nom', date: 'Date', size: 'Taille', type: 'Type' };
    return `<div class="toolbar"><small>${count}</small>${extra || ''}<button class="tbtn" data-act="sort" type="button">${ic('sortaz', 'cyan', 'none')}${SORTS[sortK]}${sortDesc ? ' ↓' : ' ↑'}</button><button class="tbtn${hidden ? ' on' : ''}" data-act="hidden" type="button">${ic('eye', 'cyan', 'none')}</button><button class="tbtn" data-act="grid" type="button">${ic(gridFor(viewKey()) ? 'menu' : 'grid', 'cyan', 'none')}</button></div>`;
  }
  const viewKey = () => { const v = top(); return v.v === 'lib' ? 'lib:' + v.cat : v.v; };

  function renderDir(path) {
    loadVols();
    const r = J(fc('list', path, hidden), {}) || {};
    const v = volOf(path);
    crumbs(path);
    $('#fsub').textContent = v ? v.label : 'Dossier';
    if (!r.ok) {
      items = [];
      $('#fbody').innerHTML = `<div class="empty2">${ic('lock', 'red')}<b>Accès refusé</b><span>${/\/Android\/(data|obb)/.test(path) ? 'Android interdit l\'accès aux dossiers Android/data et Android/obb aux autres applis.' : 'Ce dossier ne peut pas être lu.'}</span></div>`;
      return;
    }
    items = sortItems((r.items || []).map((x) => Object.assign(x, { p: path.replace(/\/$/, '') + '/' + x.n })));
    paintList(items, `${plural(items.filter((x) => x.d).length, 'dossier', 'dossiers')} · ${plural(items.filter((x) => !x.d).length, 'fichier', 'fichiers')}`);
  }

  function crumbs(path) {
    const v = volOf(path);
    const el = $('#crumbs');
    if (!v) { el.innerHTML = `<button class="crumb root" data-open="/" type="button">/</button>`; return; }
    const rel = path.slice(v.root.length).split('/').filter(Boolean);
    let acc = v.root;
    const k = KIND[v.kind] || KIND.food;
    el.innerHTML = `<button class="crumb root${rel.length ? '' : ' last'}" data-open="${esc(v.root)}" style="--acc:${k.c}" type="button">${ic(v.kind === 'treat' ? 'bone' : 'bowl', v.kind === 'water' ? 'cyan' : v.kind === 'treat' ? 'pink' : 'amber', 'none')}/${esc(v.dev)}</button>` +
      rel.map((s, i) => { acc += '/' + s; return `<span class="csep">›</span><button class="crumb${i === rel.length - 1 ? ' last' : ''}" data-open="${esc(acc)}" type="button">${esc(s)}</button>`; }).join('');
    requestAnimationFrame(() => { el.scrollLeft = el.scrollWidth; });
  }

  function paintList(list, countText, opts) {
    opts = opts || {};
    const q = norm(filterQ);
    const shown = q ? list.filter((x) => norm(x.n).includes(q)) : list;
    const grid = gridFor(viewKey());
    const body = $('#fbody');
    body.innerHTML = (opts.head || '') + toolbarHtml(q ? `${shown.length} / ${countText}` : countText, opts.extra) + `<div class="${grid ? 'fgrid' : ''}" id="fl"></div>` +
      (!shown.length ? `<div class="empty2">${ic(q ? 'search' : 'folder', q ? 'cyan' : 'amber')}<b>${q ? 'Rien trouvé ici' : 'Gamelle vide'}</b><span>${q ? `<button class="ab small c2" data-act="deep" type="button">Chercher dans tout ${esc(disp((volOf(top().path || '') || {}).root || ''))}</button>` : 'Ce dossier est vide.'}</span></div>` : '');
    const row = (x) => {
      const i = items.indexOf(x);
      const meta = x.d ? (x.c >= 0 ? plural(x.c, 'élément', 'éléments') : 'dossier') : fmtSize(x.s);
      const where = opts.showPath ? ' · ' + esc(disp(dirOf(x.p))) : '';
      const on = sel.has(x.p) ? ' sel' : '';
      if (grid) return `<div class="fcell${on}" data-i="${i}">${iconHtml(x)}<span class="chk">${ic('check', 'chrome', 'none')}</span><b>${esc(x.n)}</b><small>${meta}</small></div>`;
      return `<div class="frow2${on}" data-i="${i}">${iconHtml(x)}<span class="ftx"><b>${esc(x.n)}</b><small>${x.n.startsWith('.') ? '<span class="hid">caché · </span>' : ''}${meta} · ${fmtDate(x.m)}${where}</small></span><span class="chk">${ic('check', 'chrome', 'none')}</span></div>`;
    };
    chunk($('#fl'), shown, row);
  }

  function chunk(el, list, fn, size) {
    size = size || 90;
    let i = 0;
    const more = () => {
      const part = list.slice(i, i + size); i += part.length;
      el.insertAdjacentHTML('beforeend', part.map(fn).join(''));
      if (i < list.length) {
        const s = document.createElement('div'); s.style.cssText = 'height:1px;grid-column:1/-1'; el.appendChild(s);
        const ob = new IntersectionObserver((e) => { if (e[0].isIntersecting) { ob.disconnect(); s.remove(); more(); } }, { root: $('#fbody'), rootMargin: '900px' });
        ob.observe(s);
      }
    };
    el.innerHTML = '';
    more();
  }

  // ------------------------------------------------------------------ bibliothèque
  let libCache = {};
  function renderLib(v) {
    const c = LIBCATS.find((x) => x[0] === v.cat);
    $('#fsub').textContent = c ? c[1] : 'Bibliothèque';
    loadVols();
    if (!libCache[v.cat]) { $('#fbody').innerHTML = `<div class="empty2">${ic(c[2], c[3])}<b>Recherche dans les gamelles…</b></div>`; fc('lib', v.cat, 1500); return; }
    items = libCache[v.cat].map((x) => ({ p: x.p, n: base(x.p), d: false, s: x.s, m: x.m }));
    if (v.cat !== 'big' && v.cat !== 'recent') items = sortItems(items);
    paintList(items, `${plural(items.length, 'fichier', 'fichiers')} · ${fmtSize(items.reduce((a, x) => a + x.s, 0))}`, { showPath: true });
  }

  // ------------------------------------------------------------------ poubelle
  function renderTrash() {
    $('#fsub').textContent = 'Poubelle';
    const t = (J(fc('trashList'), []) || []).sort((a, b) => b.t - a.t);
    items = t.map((x) => ({ p: x.p, n: x.n, d: x.d, s: x.s, m: x.t, from: x.from, trash: true }));
    const head = `<div class="acts" style="display:flex;gap:8px;margin-bottom:12px"><button class="ab green" data-act="restoreall" type="button" ${t.length ? '' : 'disabled'}>${ic('refresh', 'chrome', 'none')}Tout restaurer</button><button class="ab red" data-act="emptytrash" type="button" ${t.length ? '' : 'disabled'}>${ic('trash', 'chrome', 'none')}Vider</button></div>`;
    $('#fbody').innerHTML = head + (t.length ? '<div id="fl"></div>' : `<div class="empty2">${ic('trash', 'red')}<b>Poubelle vide</b><span>Les fichiers supprimés atterrissent ici avant d'être jetés pour de bon.</span></div>`);
    if (t.length) chunk($('#fl'), items, (x) => `<div class="frow2${sel.has(x.p) ? ' sel' : ''}" data-i="${items.indexOf(x)}">${iconHtml(x)}<span class="ftx"><b>${esc(x.n)}</b><small>${x.d ? 'dossier' : fmtSize(x.s)} · jeté le ${fmtDate(x.m)}${x.from ? ' · ' + esc(disp(dirOf(x.from))) : ''}</small></span><span class="chk">${ic('check', 'chrome', 'none')}</span></div>`);
  }

  // ------------------------------------------------------------------ analyse
  let anaData = {};
  function renderAnalyze(v) {
    loadVols();
    const vol = VOLS.find((x) => x.root === v.root) || VOLS[0];
    if (!vol) return;
    v.root = vol.root;
    $('#fsub').textContent = 'Analyse · /dev/' + vol.dev;
    const used = vol.total - vol.free, pct = vol.total ? used / vol.total : 0, k = KIND[vol.kind] || KIND.food;
    const d = anaData[vol.root];
    const head = `<div class="seg" style="margin-bottom:12px">${VOLS.map((x) => `<button class="${x.root === vol.root ? 'on' : ''}" data-ana="${esc(x.root)}" type="button">/dev/${esc(x.dev)}</button>`).join('')}</div>
      <div class="abig" style="--c:${k.c}">${bowlSvg(vol.kind, pct)}<div><b>${fmtSize(used)}</b><small>mangés sur ${fmtSize(vol.total)}</small><small>${esc(vol.label)} · ${fmtSize(vol.free)} libres</small></div></div>`;
    if (!d) { $('#fbody').innerHTML = head + `<div class="empty2">${ic('pulse', 'violet')}<b>Analyse en cours…</b><span id="anastep">Je renifle chaque dossier 🐾</span></div>`; fc('analyze', vol.root); return; }
    const CATN = { images: ['Images', 'gallery', 'pink'], videos: ['Vidéos', 'film', 'red'], audio: ['Audio', 'music', 'violet'], docs: ['Documents', 'doc', 'blue'], apk: ['APK', 'apps', 'green'], archives: ['Archives', 'zip', 'orange'] };
    const bar = (g, c, name, size, of, attrs) => `<div class="abar" ${attrs || ''}>${ic(g, c)}<div class="at"><div><span>${esc(name)}</span><em>${fmtSize(size)}</em></div><div class="gauge" style="--c:${PAL[c][1]}"><i style="width:${Math.max(.5, of ? size / of * 100 : 0).toFixed(1)}%"></i></div></div></div>`;
    const cats = Object.entries(d.cats || {}).filter(([, s]) => s > 0).sort((a, b) => b[1] - a[1]);
    items = (d.big || []).map((x) => ({ p: x.p, n: base(x.p), d: false, s: x.s, m: 0 }));
    $('#fbody').innerHTML = head +
      (cats.length ? `<div class="sect">Par type</div>` + cats.map(([c, s]) => bar(CATN[c][1], CATN[c][2], CATN[c][0], s, used)).join('') : '') +
      `<div class="sect">Plus gros dossiers</div>` + (d.dirs || []).slice(0, 15).map((x) => bar(x.d ? 'folder' : 'doc', x.d ? 'amber' : 'chrome', base(x.p), x.s, used, x.d ? `data-open="${esc(x.p)}"` : '')).join('') +
      `<div class="sect">Plus gros fichiers</div><div id="fl"></div>` +
      `<button class="ab wide glass" data-act="reanalyze" type="button" style="margin-top:12px">${ic('refresh', 'chrome', 'none')}Relancer l'analyse</button>`;
    chunk($('#fl'), items, (x) => `<div class="frow2${sel.has(x.p) ? ' sel' : ''}" data-i="${items.indexOf(x)}">${iconHtml(x)}<span class="ftx"><b>${esc(x.n)}</b><small>${fmtSize(x.s)} · ${esc(disp(dirOf(x.p)))}</small></span><span class="chk">${ic('check', 'chrome', 'none')}</span></div>`);
  }

  // ------------------------------------------------------------------ recherche
  let searchRes = null;
  function renderSearch(v) {
    $('#fsub').textContent = `Recherche « ${v.q} »`;
    if (!searchRes || searchRes.q !== v.q) { $('#fbody').innerHTML = `<div class="empty2">${ic('search', 'cyan')}<b>Je flaire « ${esc(v.q)} »…</b><span id="sstep">dans ${esc(disp(v.root))}</span></div>`; return; }
    items = searchRes.items.map((x) => ({ p: x.p, n: base(x.p), d: x.d, s: x.s, m: x.m }));
    paintList(items, plural(items.length, 'résultat', 'résultats') + (items.length >= 500 ? ' (max)' : ''), { showPath: true });
  }
  function deepSearch(root, q) { searchRes = null; fc('search', root, q, hidden); go({ v: 'search', root, q }); }

  // ------------------------------------------------------------------ applis
  let APPS = null, appSys = false, appSort = 'name';
  function renderApps() {
    $('#fsub').textContent = 'Applis installées';
    if (!APPS) APPS = J(fc('apps', appSys), []) || [];
    const q = norm(filterQ);
    let l = APPS.filter((a) => !q || norm(a.n + ' ' + a.pkg).includes(q));
    l.sort(appSort === 'size' ? (a, b) => b.s - a.s : appSort === 'date' ? (a, b) => b.u - a.u : (a, b) => a.n.localeCompare(b.n, 'fr', { sensitivity: 'base' }));
    $('#fbody').innerHTML = `<div class="toolbar"><small>${plural(l.length, 'appli', 'applis')} · ${fmtSize(l.reduce((a, x) => a + x.s, 0))}</small>
      <button class="tbtn" data-act="appsort" type="button">${ic('sortaz', 'cyan', 'none')}${{ name: 'Nom', size: 'Taille', date: 'Mise à jour' }[appSort]}</button>
      <button class="tbtn${appSys ? ' on' : ''}" data-act="appsys" type="button">${ic('gear', 'cyan', 'none')}Système</button></div><div id="al"></div>`;
    chunk($('#al'), l, (a) => `<button class="app" data-pkg="${esc(a.pkg)}" type="button">${MOCK ? `<span style="width:48px;height:48px">${ic('apps', 'green')}</span>` : `<img src="https://pupfile.local/appicon?p=${encodeURIComponent(a.pkg)}" loading="lazy" alt="">`}<span class="ftx"><b>${esc(a.n)}</b><small>${esc(a.v || '')} · ${esc(a.pkg)}</small></span><em>${fmtSize(a.s)}</em></button>`);
  }
  function appMenu(a) {
    menu([
      a.launch ? { k: 'open', g: 'external', c: 'green', l: 'Ouvrir' } : null,
      { k: 'apk', g: 'download', c: 'cyan', l: 'Extraire l\'APK' + (a.split ? ' (base seulement)' : '') },
      { k: 'info', g: 'info', c: 'blue', l: 'Infos système' },
      a.sys ? null : { k: 'del', g: 'trash', c: 'red', l: 'Désinstaller', danger: 1 },
    ].filter(Boolean), { title: a.n, sub: `${a.v || ''} · ${fmtSize(a.s)}`, img: MOCK ? '' : `https://pupfile.local/appicon?p=${encodeURIComponent(a.pkg)}` }, (k) => {
      if (k === 'open') fc('launchApp', a.pkg);
      if (k === 'apk') fc('backupApk', a.pkg);
      if (k === 'info') fc('appInfo', a.pkg);
      if (k === 'del') { fc('uninstall', a.pkg); APPS = null; }
    });
  }

  // =================================================================== SÉLECTION ET ACTIONS
  function exitSelect() { sel.clear(); document.body.classList.remove('selecting'); $$('.sel', $('#fbody')).forEach((e) => e.classList.remove('sel')); updateBars(); }
  function toggleSel(i, el) {
    const x = items[i]; if (!x) return;
    if (sel.has(x.p)) sel.delete(x.p); else sel.add(x.p);
    if (el) el.classList.toggle('sel', sel.has(x.p));
    document.body.classList.toggle('selecting', sel.size > 0);
    updateBars();
  }
  function selItems() { return items.filter((x) => sel.has(x.p)); }

  function updateBars() {
    const sb = $('#selbar'), ab = $('#actbar'), pb = $('#pastebar');
    const n = sel.size, v = top();
    $('#frow').hidden = n > 0;
    sb.hidden = n === 0;
    if (n) sb.innerHTML = `<button data-sa="close" type="button">${XSVG}</button><b>${plural(n, 'sélectionné', 'sélectionnés')}</b><button data-sa="all" type="button">${ic('check', 'chrome', 'none')}</button><button data-sa="invert" type="button">${ic('refresh', 'chrome', 'none')}</button>`;
    ab.hidden = n === 0;
    if (n) {
      if (v.v === 'trash') {
        ab.innerHTML = `<button data-a="restore" type="button">${ic('refresh', 'green')}Restaurer</button><button data-a="delforever" type="button">${ic('trash', 'red')}Détruire</button><button data-a="props" type="button" ${n === 1 ? '' : 'disabled'}>${ic('info', 'cyan')}Infos</button><span></span><span></span>`;
      } else {
        ab.innerHTML = `<button data-a="copy" type="button">${ic('copy', 'cyan')}Copier</button><button data-a="cut" type="button">${ic('cut', 'pink')}Couper</button><button data-a="trash" type="button">${ic('trash', 'red')}Jeter</button><button data-a="rename" type="button" ${n === 1 ? '' : 'disabled'}>${ic('edit', 'amber')}Renommer</button><button data-a="more" type="button">${ic('menu', 'violet')}Plus</button>`;
      }
    }
    const pasting = !!clip && n === 0 && v.v === 'dir';
    document.body.classList.toggle('pasting', pasting);
    pb.hidden = !pasting;
    if (pasting) pb.innerHTML = `${ic(clip.move ? 'cut' : 'copy', clip.move ? 'pink' : 'cyan')}<span>${plural(clip.paths.length, 'élément', 'éléments')} à ${clip.move ? 'déplacer' : 'copier'}<small>${esc(clip.paths.map(base).join(', '))}</small></span><button class="ab small glass" data-p="cancel" type="button">Annuler</button><button class="ab small green" data-p="paste" type="button">${ic('paste', 'chrome', 'none')}Coller</button>`;
  }

  $('#selbar').onclick = (e) => {
    const b = e.target.closest('[data-sa]'); if (!b) return;
    const k = b.dataset.sa;
    if (k === 'close') exitSelect();
    if (k === 'all') { items.forEach((x) => sel.add(x.p)); render(); }
    if (k === 'invert') { items.forEach((x) => sel.has(x.p) ? sel.delete(x.p) : sel.add(x.p)); render(); }
  };

  $('#actbar').onclick = (e) => {
    const b = e.target.closest('[data-a]'); if (!b) return; haptic();
    const k = b.dataset.a, list = selItems(), paths = list.map((x) => x.p);
    if (k === 'copy' || k === 'cut') { clip = { paths, move: k === 'cut' }; exitSelect(); toast(`${plural(paths.length, 'élément', 'éléments')} en mémoire — va dans le dossier voulu et appuie sur Coller`, k === 'cut' ? 'cut' : 'copy'); if (top().v !== 'dir') goHome(); return; }
    if (k === 'trash') { confirm2('Jeter à la poubelle ?', `${plural(paths.length, 'élément', 'éléments')} ${paths.length > 1 ? 'iront' : 'ira'} dans la Poubelle (récupérable).`, 'Jeter', () => { fc('trash', JSON.stringify(paths)); exitSelect(); }, 'trash'); return; }
    if (k === 'rename') return renameItem(list[0]);
    if (k === 'restore') { fc('restore', JSON.stringify(paths)); exitSelect(); return; }
    if (k === 'delforever') { confirm2('Détruire définitivement ?', `${plural(paths.length, 'élément', 'éléments')} — impossible de revenir en arrière.`, 'Détruire', () => { fc('delete', JSON.stringify(paths)); exitSelect(); }, 'trash'); return; }
    if (k === 'props') return props(list[0]);
    if (k === 'more') moreMenu(list);
  };

  function moreMenu(list) {
    const one = list.length === 1 ? list[0] : null, paths = list.map((x) => x.p);
    const isZip = one && !one.d && /^(zip|jar|apk)$/.test(extOf(one.n));
    menu([
      { k: 'zip', g: 'zip', c: 'orange', l: 'Compresser en ZIP' },
      isZip ? { k: 'unzip', g: 'box', c: 'amber', l: 'Extraire ici' } : null,
      { k: 'share', g: 'share', c: 'cyan', l: 'Partager' },
      one && !one.d ? { k: 'with', g: 'external', c: 'violet', l: 'Ouvrir avec…' } : null,
      one && !one.d && fc('isText', one.n) !== false ? { k: 'edit', g: 'edit', c: 'green', l: 'Modifier (éditeur texte)' } : null,
      one ? { k: 'props', g: 'info', c: 'blue', l: 'Propriétés' } : null,
      one && one.d ? { k: 'fav', g: 'star', c: 'gold', l: (FAV || []).includes(one.p) ? 'Retirer des favoris' : 'Ajouter aux favoris' } : null,
      top().v !== 'dir' && one ? { k: 'locate', g: 'folder', c: 'amber', l: 'Afficher dans le dossier' } : null,
      { k: 'del', g: 'trash', c: 'red', l: 'Supprimer définitivement', danger: 1 },
    ].filter(Boolean), { title: one ? one.n : plural(list.length, 'élément', 'éléments') }, (k) => {
      if (k === 'zip') { const d = dirOf(paths[0]); prompt2('Compresser en ZIP', 'zip', 'Nom de l\'archive', (one ? one.n.replace(/\.[^.]+$/, '') : 'Archive') + '.zip', (n) => { fc('zip', JSON.stringify(paths), d + '/' + (n.endsWith('.zip') ? n : n + '.zip')); exitSelect(); }); }
      if (k === 'unzip') { fc('unzip', one.p, dirOf(one.p) + '/' + one.n.replace(/\.[^.]+$/, '')); exitSelect(); }
      if (k === 'share') { fc('share', JSON.stringify(paths)); }
      if (k === 'with') fc('openWith', one.p);
      if (k === 'edit') { exitSelect(); editor(one.p); }
      if (k === 'props') props(one);
      if (k === 'fav') { FAV = FAV || []; if (FAV.includes(one.p)) FAV = FAV.filter((p) => p !== one.p); else FAV.push(one.p); LS.set('pf_fav', FAV); toast(FAV.includes(one.p) ? 'Ajouté aux favoris ⭐' : 'Retiré des favoris', 'star'); exitSelect(); }
      if (k === 'locate') { exitSelect(); stack = [{ v: 'home' }]; tab = 'local'; go({ v: 'dir', path: dirOf(one.p) }); }
      if (k === 'del') confirm2('Supprimer définitivement ?', `${plural(paths.length, 'élément', 'éléments')} — sans passer par la Poubelle.`, 'Supprimer', () => { fc('delete', JSON.stringify(paths)); exitSelect(); }, 'trash');
    });
  }

  function renameItem(x) {
    const dot = x.d ? -1 : x.n.lastIndexOf('.');
    const w = prompt2('Renommer', 'edit', 'Nouveau nom', x.n, (n) => {
      if (n === x.n) return;
      const err = fc('rename', x.p, n);
      if (err) toast(err, 'info'); else { exitSelect(); refresh(); }
    });
    const inp = $('#pin', w);
    setTimeout(() => { if (dot > 0) inp.setSelectionRange(0, dot); }, 80);
  }

  function props(x) {
    const p = J(fc('props', x.p), {}) || {};
    const w = win('Propriétés', 'info', 'cyan', `<div style="display:flex;gap:12px;align-items:center;margin-bottom:12px">${iconHtml(x)}<b style="font-family:var(--f-head);font-weight:400;font-size:15px;word-break:break-all">${esc(x.n)}</b></div>
      <div class="kv"><span>Chemin</span><b>${esc(disp(x.p))}</b><span>Réel</span><b style="font-family:var(--f-num);font-size:12px;opacity:.8">${esc(x.p)}</b><span>Type</span><b>${esc(p.mime || '')}</b>
      <span>Taille</span><b id="psz">${p.d ? 'calcul…' : fmtSize(p.s) + ' (' + (p.s || 0).toLocaleString('fr-FR') + ' octets)'}</b>${p.d ? '<span>Contenu</span><b id="pcnt">…</b>' : ''}
      <span>Modifié</span><b>${p.m ? fmtDate(p.m) : '—'}</b><span>Droits</span><b style="font-family:var(--f-num)">${p.d ? 'd' : '-'}${p.r ? 'r' : '-'}${p.w ? 'w' : '-'}${p.x ? 'x' : '-'}</b>
      ${p.d ? '' : '<span>MD5</span><b id="pmd5" style="font-family:var(--f-num);font-size:11.5px"><button class="ab small glass" data-h="MD5" type="button">Calculer</button></b><span>SHA-1</span><b id="psha" style="font-family:var(--f-num);font-size:11.5px"><button class="ab small glass" data-h="SHA-1" type="button">Calculer</button></b>'}</div>`, [['OK', 'c', null]]);
    if (p.d) fc('deepSize', x.p);
    w.addEventListener('click', (e) => { const b = e.target.closest('[data-h]'); if (b) { b.textContent = 'calcul…'; fc('hash', x.p, b.dataset.h); } });
  }

  // =================================================================== OUVERTURE
  function openItem(x) {
    if (x.d) { if (top().v === 'dir') go({ v: 'dir', path: x.p }); else { stack = [{ v: 'home' }]; tab = 'local'; go({ v: 'dir', path: x.p }); } return; }
    if (x.trash) return menu([{ k: 'r', g: 'refresh', c: 'green', l: 'Restaurer' }, { k: 'd', g: 'trash', c: 'red', l: 'Détruire définitivement', danger: 1 }], { title: x.n, sub: x.from ? 'Venait de ' + disp(dirOf(x.from)) : '' }, (k) => fc(k === 'r' ? 'restore' : 'delete', JSON.stringify([x.p])));
    const t = typeOf(x.n, false);
    if (t === 'text' || t === 'code') return editor(x.p);
    if (t === 'image') return viewer(x);
    if (t === 'zip' && extOf(x.n) === 'zip') return menu([
      { k: 'here', g: 'box', c: 'amber', l: 'Extraire dans « ' + x.n.replace(/\.[^.]+$/, '') + ' »' },
      { k: 'with', g: 'external', c: 'violet', l: 'Ouvrir avec…' },
    ], { title: x.n, sub: fmtSize(x.s) }, (k) => { if (k === 'here') fc('unzip', x.p, dirOf(x.p) + '/' + x.n.replace(/\.[^.]+$/, '')); else fc('openWith', x.p); });
    fc('open', x.p);
  }

  function editor(path) {
    const r = J(fc('readText', path), {}) || {};
    if (!r.ok) return toast(r.err || 'Lecture impossible', 'info');
    const p = panel(base(path), disp(path), 'edit', 'cyan');
    $('.pbd', p).style.display = 'flex';
    $('.pbd', p).innerHTML = `<textarea class="editor${LS.get('pf_wrap', true) ? ' wrap' : ''}" spellcheck="false" autocapitalize="off" autocomplete="off"></textarea>`;
    const ta = $('textarea', p);
    ta.value = r.t;
    let dirty = false;
    const hd = $('.phd', p);
    hd.insertAdjacentHTML('beforeend', `<button class="hbtn" data-e="wrap" type="button">${ic('menu', 'chrome', 'none')}</button><button class="hbtn" data-e="save" type="button">${ic('check', 'chrome', 'none')}</button>`);
    const sub = $('.tx small', p);
    const upd = () => { sub.textContent = `${disp(path)} · ${ta.value.split('\n').length} lignes${dirty ? ' · modifié ●' : ''}`; };
    upd();
    ta.oninput = () => { if (!dirty) { dirty = true; } upd(); };
    hd.addEventListener('click', (e) => {
      const b = e.target.closest('[data-e]'); if (!b) return;
      if (b.dataset.e === 'wrap') { ta.classList.toggle('wrap'); LS.set('pf_wrap', ta.classList.contains('wrap')); }
      if (b.dataset.e === 'save') { const err = fc('writeText', path, ta.value); if (err) toast('Enregistrement refusé : ' + err, 'info'); else { dirty = false; upd(); toast('Enregistré 🐾', 'check'); } }
    });
    const close0 = p.close;
    p.close = () => { if (dirty) confirm2('Quitter sans enregistrer ?', 'Tes modifications seront perdues.', 'Quitter', close0, 'edit'); else close0(); };
    $('[data-close]', p).onclick = p.close;
  }

  function viewer(x) {
    const imgs = items.filter((y) => !y.d && typeOf(y.n, false) === 'image');
    let i = Math.max(0, imgs.indexOf(x));
    const p = panel(x.n, '', 'gallery', 'pink');
    const bd = $('.pbd', p); bd.className = 'viewer';
    bd.innerHTML = `<img alt=""><button class="vnav l" type="button">${ic('left', 'chrome', 'none')}</button><button class="vnav r" type="button">${ic('right', 'chrome', 'none')}</button>`;
    const hd = $('.phd', p);
    hd.insertAdjacentHTML('beforeend', `<button class="hbtn" data-v="share" type="button">${ic('share', 'chrome', 'none')}</button><button class="hbtn" data-v="open" type="button">${ic('external', 'chrome', 'none')}</button>`);
    const show = () => {
      const it = imgs[i];
      $('img', bd).src = MOCK ? '' : 'https://pupfile.local/raw?p=' + encodeURIComponent(it.p);
      $('.tx b', p).textContent = it.n; $('.tx small', p).textContent = `${i + 1}/${imgs.length} · ${fmtSize(it.s)} · ${disp(dirOf(it.p))}`;
      $('.vnav.l', bd).hidden = i === 0; $('.vnav.r', bd).hidden = i === imgs.length - 1;
    };
    $('.vnav.l', bd).onclick = () => { if (i > 0) { i--; show(); } };
    $('.vnav.r', bd).onclick = () => { if (i < imgs.length - 1) { i++; show(); } };
    let x0 = null;
    bd.onpointerdown = (e) => { x0 = e.clientX; };
    bd.onpointerup = (e) => { if (x0 == null) return; const dx = e.clientX - x0; x0 = null; if (dx < -60 && i < imgs.length - 1) { i++; show(); } else if (dx > 60 && i > 0) { i--; show(); } };
    hd.addEventListener('click', (e) => { const b = e.target.closest('[data-v]'); if (!b) return; if (b.dataset.v === 'share') fc('share', JSON.stringify([imgs[i].p])); else fc('openWith', imgs[i].p); });
    show();
  }

  // =================================================================== COLLER
  function doPaste() {
    const dest = top().path; if (!clip || !dest) return;
    const conf = J(fc('conflicts', JSON.stringify(clip.paths), dest), []) || [];
    const run = (policy) => { fc('paste', JSON.stringify(clip.paths), dest, clip.move, policy); if (clip.move) clip = null; updateBars(); };
    if (!conf.length) return run('rename');
    win('Déjà présent', 'info', 'amber', `<p>${plural(conf.length, 'élément existe', 'éléments existent')} déjà ici :</p><p style="font-family:var(--f-num);font-size:12px">${esc(conf.slice(0, 6).join(', '))}${conf.length > 6 ? '…' : ''}</p>`,
      [['Ignorer', 'glass', () => run('skip')], ['Garder les deux', 'c2', () => run('rename')], ['Remplacer', 'red', () => run('overwrite')]]);
  }
  $('#pastebar').onclick = (e) => {
    const b = e.target.closest('[data-p]'); if (!b) return; haptic();
    if (b.dataset.p === 'cancel') { clip = null; updateBars(); } else doPaste();
  };

  // =================================================================== TÂCHES EN COURS
  const JOBN = { copy: ['Copie', 'copy', 'cyan'], move: ['Déplacement', 'cut', 'pink'], trash: ['À la poubelle', 'trash', 'red'], delete: ['Destruction', 'trash', 'red'], restore: ['Restauration', 'refresh', 'green'], zip: ['Compression ZIP', 'zip', 'orange'], unzip: ['Extraction', 'box', 'amber'], apk: ['Extraction APK', 'apps', 'green'] };
  const jobEls = {};
  function onJob(j) {
    let box = $('.jobs');
    if (!box) { box = document.createElement('div'); box.className = 'jobs'; document.body.appendChild(box); }
    const n = JOBN[j.kind] || ['Tâche', 'gear', 'chrome'];
    let el = jobEls[j.id];
    if (j.st === 'run') {
      if (!el) {
        el = jobEls[j.id] = document.createElement('div'); el.className = 'job';
        el.innerHTML = `${ic(n[1], n[2])}<div class="jt"><b>${n[0]}…</b><small></small><div class="gauge"><i></i></div></div><button type="button">${XSVG}</button>`;
        $('button', el).onclick = () => fc('cancel', j.id);
        box.appendChild(el);
      }
      const pct = j.total ? j.done / j.total : 0;
      $('small', el).textContent = (j.total > 100 ? `${fmtSize(j.done)} / ${fmtSize(j.total)} · ` : j.total ? `${j.done}/${j.total} · ` : '') + (j.name || '');
      $('.gauge', el).classList.toggle('ind', !j.total);
      $('.gauge i', el).style.width = (pct * 100).toFixed(1) + '%';
      return;
    }
    if (el) { el.remove(); delete jobEls[j.id]; }
    if (j.st === 'err') toast(`${n[0]} : ${j.msg}`, 'info');
    else toast(j.msg ? `${n[0]} ${j.msg.toLowerCase()}` : `${n[0]} terminée 🐾`, n[1]);
    if (j.kind === 'trash' || j.kind === 'delete') { if (clip) { clip.paths = clip.paths.filter((p) => fc('exists', p)); if (!clip.paths.length) clip = null; } }
    libCache = {}; anaData = {};
    refresh();
  }
  function refresh() { const st = $('#fbody').scrollTop; render(); $('#fbody').scrollTop = st; }

  // =================================================================== ÉVÉNEMENTS
  let longT = null, longFired = false, longXY = null;
  const body = $('#fbody');
  body.addEventListener('pointerdown', (e) => {
    const r = e.target.closest('[data-i]'); if (!r) return;
    longXY = [e.clientX, e.clientY];
    longT = setTimeout(() => { longFired = true; haptic(); toggleSel(+r.dataset.i, r); }, 480);
  });
  const cancelLong = (e) => { if (longT && (!e || e.type !== 'pointermove' || Math.hypot(e.clientX - longXY[0], e.clientY - longXY[1]) > 10)) { clearTimeout(longT); longT = null; } };
  ['pointerup', 'pointercancel', 'pointermove'].forEach((ev) => body.addEventListener(ev, cancelLong, { passive: true }));
  body.addEventListener('scroll', () => cancelLong(), { passive: true });

  document.addEventListener('click', (e) => {
    const t = e.target;
    const r = t.closest('#fbody [data-i]');
    if (r) {
      if (longFired) { longFired = false; return; }
      const i = +r.dataset.i;
      if (sel.size || t.closest('.chk')) { toggleSel(i, r); return; }
      openItem(items[i]); return;
    }
    const b = t.closest('[data-open],[data-go],[data-lib],[data-tabgo],[data-act],[data-pkg],[data-ana]');
    if (!b || !b.closest('#app')) return;
    const d = b.dataset;
    if (d.open) { if (sel.size) exitSelect(); if (top().v === 'dir') { stack[stack.length - 1] = top(); go({ v: 'dir', path: d.open }); } else go({ v: 'dir', path: d.open }); return; }
    if (d.go) { go({ v: d.go, root: (VOLS[0] || {}).root }); return; }
    if (d.lib) { go({ v: 'lib', cat: d.lib }); return; }
    if (d.tabgo) { setTab(d.tabgo); return; }
    if (d.pkg) { const a = (APPS || []).find((x) => x.pkg === d.pkg); if (a) appMenu(a); return; }
    if (d.ana) { top().root = d.ana; render(); return; }
    const a = d.act;
    if (a === 'perm') fc('askPerm');
    if (a === 'sort') {
      const S = { name: 'Nom', date: 'Date', size: 'Taille', type: 'Type' };
      menu([...Object.entries(S).map(([k, l]) => ({ k, g: k === sortK ? 'check' : 'sortaz', c: k === sortK ? 'green' : 'chrome', l })), { k: 'rev', g: 'rotate', c: 'cyan', l: sortDesc ? 'Ordre croissant ↑' : 'Ordre décroissant ↓' }], { title: 'Trier par' }, (k) => {
        if (k === 'rev') sortDesc = !sortDesc; else sortK = k;
        LS.set('pf_sort', sortK); LS.set('pf_desc', sortDesc); render();
      });
    }
    if (a === 'hidden') { hidden = !hidden; LS.set('pf_hidden', hidden); toast(hidden ? 'Fichiers cachés visibles' : 'Fichiers cachés masqués', 'eye'); render(); }
    if (a === 'grid') { LS.set('pf_grid_' + viewKey(), !gridFor(viewKey())); render(); }
    if (a === 'deep') { const v = volOf(top().path || '') || VOLS[0]; deepSearch(v.root, filterQ); closeSearch(); }
    if (a === 'restoreall') fc('restore', JSON.stringify(items.map((x) => x.p)));
    if (a === 'emptytrash') confirm2('Vider la poubelle ?', `${plural(items.length, 'élément sera détruit', 'éléments seront détruits')} pour de bon.`, 'Vider', () => fc('delete', JSON.stringify(items.map((x) => x.p))), 'trash');
    if (a === 'reanalyze') { delete anaData[top().root]; render(); }
    if (a === 'appsys') { appSys = !appSys; APPS = null; render(); }
    if (a === 'appsort') menu([['name', 'Nom'], ['size', 'Taille'], ['date', 'Mise à jour']].map(([k, l]) => ({ k, g: k === appSort ? 'check' : 'sortaz', c: k === appSort ? 'green' : 'chrome', l })), { title: 'Trier les applis' }, (k) => { appSort = k; render(); });
  });

  $('#ftabs').onclick = (e) => { const b = e.target.closest('[data-tab]'); if (b) setTab(b.dataset.tab); };
  $('#fback').onclick = () => back();
  function goHome() { stack = [{ v: 'home' }]; tab = 'local'; render(true); }

  $('#fab').onclick = () => {
    haptic();
    const dir = top().path;
    menu([{ k: 'dir', g: 'folder', c: 'amber', l: 'Nouveau dossier' }, { k: 'file', g: 'edit', c: 'cyan', l: 'Nouveau fichier texte' }, { k: 'fav', g: 'star', c: 'gold', l: (FAV || []).includes(dir) ? 'Retirer ce dossier des favoris' : 'Ajouter ce dossier aux favoris' }], { title: disp(dir) }, (k) => {
      if (k === 'dir') prompt2('Nouveau dossier', 'folder', 'Nom', 'Nouveau dossier', (n) => { const err = fc('mkdir', dir, n); if (err) toast(err, 'info'); else refresh(); });
      if (k === 'file') prompt2('Nouveau fichier', 'edit', 'Nom', 'nouveau.txt', (n) => { const err = fc('mkfile', dir, n); if (err) toast(err, 'info'); else { refresh(); editor(dir + '/' + n); } });
      if (k === 'fav') { FAV = FAV || []; if (FAV.includes(dir)) FAV = FAV.filter((p) => p !== dir); else FAV.push(dir); LS.set('pf_fav', FAV); toast(FAV.includes(dir) ? 'Ajouté aux favoris ⭐' : 'Retiré des favoris', 'star'); }
    });
  };

  // recherche dans l'en-tête
  let searchOpen = false;
  function openSearch() {
    if (searchOpen) return closeSearch();
    searchOpen = true;
    const row = document.createElement('div'); row.className = 'fsearch'; row.id = 'srow';
    const where = top().v === 'apps' ? 'Filtrer les applis…' : top().v === 'dir' ? 'Filtrer ce dossier…' : 'Chercher dans les gamelles…';
    row.innerHTML = `<label class="vsearch"><span class="pw">${ic('search', 'amber', 'none')}</span><input id="sq" placeholder="${where}" autocomplete="off" enterkeyhint="search"></label>`;
    $('#frow').after(row);
    const inp = $('#sq'); inp.value = filterQ; inp.focus();
    inp.oninput = () => { filterQ = inp.value; const v = top().v; if (v === 'dir' || v === 'lib' || v === 'apps' || v === 'search') render(); };
    inp.onkeydown = (e) => { if (e.key === 'Enter' && inp.value.trim()) { const v = top().v; if (v === 'home' || v === 'libhome' || v === 'dir') { const vol = volOf(top().path || '') || VOLS[0]; deepSearch(top().v === 'dir' ? top().path : vol.root, inp.value.trim()); closeSearch(); } inp.blur(); } };
    $('#fsearch').classList.add('on');
  }
  function closeSearch() { searchOpen = false; const r = $('#srow'); if (r) r.remove(); $('#fsearch').classList.remove('on'); if (filterQ && top().v !== 'search') { filterQ = ''; render(); } filterQ = ''; }
  $('#fsearch').onclick = openSearch;

  $('#fmenu').onclick = () => menu([
    { k: 'hidden', g: 'eye', c: 'cyan', l: hidden ? 'Masquer les fichiers cachés' : 'Afficher les fichiers cachés' },
    { k: 'trash', g: 'trash', c: 'red', l: 'Poubelle' },
    { k: 'ana', g: 'pulse', c: 'violet', l: 'Analyser les gamelles' },
    { k: 'refresh', g: 'refresh', c: 'green', l: 'Actualiser' },
    { k: 'about', g: 'info', c: 'blue', l: 'À propos des gamelles' },
  ], { title: 'PupFile', sub: 'Menu' }, (k) => {
    if (k === 'hidden') { hidden = !hidden; LS.set('pf_hidden', hidden); render(); }
    if (k === 'trash') { stack = [{ v: 'home' }]; tab = 'local'; go({ v: 'trash' }); }
    if (k === 'ana') { stack = [{ v: 'home' }]; tab = 'local'; go({ v: 'analyze', root: (VOLS[0] || {}).root }); }
    if (k === 'refresh') { libCache = {}; anaData = {}; APPS = null; refresh(); }
    if (k === 'about') win('Les gamelles', 'bowl', 'amber', `<div class="kv"><span>/dev/hda</span><b>🍖 Gamelle Nourriture — la mémoire du téléphone</b><span>/dev/sda</span><b>💧 Gamelle Eau — la carte micro SD</b><span>/dev/sdb…</span><b>🦴 Gamelle Récompenses — clés USB et disques externes</b><span>Poubelle</span><b>🗑️ Les fichiers jetés, récupérables</b></div>`, [['OK', 'c', null]]);
  });

  // =================================================================== UI GÉNÉRIQUES
  function panel(title, sub, g, c) {
    const p = document.createElement('div'); p.className = 'panel';
    p.innerHTML = `<div class="bg" aria-hidden="true"><i class="beam b1"></i><i class="beam b2"></i><div class="pawfloor"></div></div><div class="phd"><button class="hbtn" data-close type="button">${ic('left', 'chrome', 'none')}</button><span style="width:38px;height:38px;flex:none">${ic(g, c)}</span><div class="tx"><b>${esc(title)}</b><small>${esc(sub || '')}</small></div></div><div class="pbd"></div>`;
    p.close = () => p.remove();
    $('[data-close]', p).onclick = () => p.close();
    $('#layer').appendChild(p);
    return p;
  }
  function win(title, g, c, html, buttons) {
    const veil = document.createElement('div'); veil.className = 'veil';
    const w = document.createElement('div'); w.className = 'win';
    w.style.setProperty('--wc', PAL[c] ? PAL[c][1] : c);
    w.innerHTML = `<div class="tb">${ic(g, c)}<div class="tx"><div class="t chrome">${esc(title)}</div></div><button class="xb" type="button">${XSVG}</button></div><div class="wb">${html}</div><div class="wf">${(buttons || []).map(([l, cls], i) => `<button class="ab ${cls || ''}" data-wb="${i}" type="button">${esc(l)}</button>`).join('')}</div>`;
    const close = () => { veil.remove(); w.remove(); };
    w.close = close;
    veil.onclick = close; $('.xb', w).onclick = close;
    $('.wf', w).onclick = (e) => { const b = e.target.closest('[data-wb]'); if (!b) return; const fn = buttons[+b.dataset.wb][2]; close(); if (fn) fn(); };
    $('#layer').append(veil, w);
    return w;
  }
  function prompt2(title, g, label, val, cb) {
    const w = win(title, g, 'amber', `<div class="lbl">${esc(label)}</div><input class="vin" id="pin" value="${esc(val || '')}" maxlength="200" autocapitalize="off">`, [['Annuler', 'glass', null], ['OK', '', () => { const v = $('#pin', w).value.trim(); if (v) cb(v); }]]);
    const i = $('#pin', w); setTimeout(() => { i.focus(); if (!i.selectionEnd || i.selectionEnd === i.selectionStart) i.select(); }, 60);
    i.onkeydown = (e) => { if (e.key === 'Enter') { const v = i.value.trim(); w.close(); if (v) cb(v); } };
    return w;
  }
  function confirm2(title, msg, ok, cb, g) { win(title, g || 'info', 'red', `<p>${esc(msg)}</p>`, [['Annuler', 'glass', null], [ok, 'red', cb]]); }
  function menu(list, hd, cb) {
    const veil = document.createElement('div'); veil.className = 'veil';
    const m = document.createElement('div'); m.className = 'menu';
    m.style.left = '10px'; m.style.right = '10px'; m.style.bottom = 'calc(var(--sb) + 14px)';
    const head = hd ? `<div class="mh">${hd.img ? `<img src="${hd.img}" alt="" style="width:38px;height:38px;border-radius:10px">` : ''}<div style="min-width:0"><b style="display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(hd.title)}</b>${hd.sub ? `<small>${esc(hd.sub)}</small>` : ''}</div></div>` : '';
    m.innerHTML = head + list.map((it) => `<button data-mk="${esc(it.k)}" class="${it.danger ? 'danger' : ''}" type="button">${ic(it.g, it.c)}<span style="flex:1">${esc(it.l)}</span></button>`).join('');
    const close = () => { veil.remove(); m.remove(); };
    veil.onclick = close;
    m.onclick = (e) => { const b = e.target.closest('[data-mk]'); if (!b) return; close(); haptic(); cb(b.dataset.mk); };
    $('#layer').append(veil, m);
  }
  function toast(msg, g) {
    const t = document.createElement('div'); t.className = 'toast';
    t.innerHTML = `${ic(g || 'folder', 'amber')}<span>${esc(msg)}</span>`;
    $('#toasts').appendChild(t);
    setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 320); }, 2400);
  }

  // =================================================================== PONT NATIF
  window.FileUI = {
    on(ev, data) {
      if (ev === 'job') onJob(J(data, {}) || {});
      else if (ev === 'lib') { const o = J(data, {}) || {}; libCache[o.cat] = o.items || []; if (top().v === 'lib' && top().cat === o.cat) render(); }
      else if (ev === 'analyze') { const o = J(data, {}) || {}; if (o.root) { anaData[o.root] = o; if (top().v === 'analyze') render(); } }
      else if (ev === 'analyzeStep') { const s = $('#anastep'); if (s) s.textContent = 'Je renifle : ' + data; }
      else if (ev === 'search') { searchRes = J(data, null); if (top().v === 'search') render(); }
      else if (ev === 'searchStep') { const s = $('#sstep'); if (s) s.textContent = 'dans … /' + data; }
      else if (ev === 'size') { const o = J(data, {}) || {}; const a = $('#psz'), b = $('#pcnt'); if (a) a.textContent = fmtSize(o.s) + ' (' + (o.s || 0).toLocaleString('fr-FR') + ' octets)'; if (b) b.textContent = `${plural(o.files, 'fichier', 'fichiers')}, ${plural(o.dirs, 'dossier', 'dossiers')}`; }
      else if (ev === 'hash') { const o = J(data, {}) || {}; const el = $(o.algo === 'MD5' ? '#pmd5' : '#psha'); if (el) el.textContent = o.v || 'erreur'; }
      else if (ev === 'saved') toast('APK enregistré dans ' + disp(data), 'apps');
      else if (ev === 'resume') { if (top().v !== 'search') refresh(); }
    },
    back() {
      const lay = $('#layer');
      if (lay.lastElementChild) {
        const last = lay.lastElementChild;
        if (last.classList.contains('panel')) { last.close(); return true; }
        const v = last.previousElementSibling; last.remove(); if (v && v.classList.contains('veil')) v.remove(); return true;
      }
      if (sel.size) { exitSelect(); return true; }
      if (searchOpen) { closeSearch(); return true; }
      if (back()) return true;
      if (tab !== 'local') { setTab('local'); return true; }
      return false;
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };

  // =================================================================== DÉMARRAGE
  $('#flogo').innerHTML = ic('folder', 'amber');
  $('#fback').innerHTML = ic('left', 'chrome', 'none');
  $('#fsearch').innerHTML = ic('search', 'chrome', 'none');
  $('#fmenu').innerHTML = ic('menu', 'chrome', 'none');
  $('#fab').innerHTML = ic('plus', 'chrome', 'none');
  const qs = new URLSearchParams(location.search);
  if (qs.get('path')) go({ v: 'dir', path: qs.get('path') }); else render();

  // =================================================================== aperçu navigateur (hors APK)
  function mockF() {
    const now = Date.now();
    const fs = {
      '/storage/emulated/0': [['Android', 1, 3], ['DCIM', 1, 2], ['Download', 1, 4], ['Documents', 1, 1], ['Music', 1, 12], ['Pictures', 1, 30], ['Puppyplay', 1, 5], ['.thumbnails', 1, 9], ['notes.txt', 0, 2048], ['PuppyPhone.apk', 0, 2050564], ['backup.zip', 0, 154000000]],
      '/storage/emulated/0/Download': [['PuppyPhone-v14.apk', 0, 2050564], ['facture.pdf', 0, 230000], ['photo_soirée.jpg', 0, 3400000], ['clip.mp4', 0, 88000000]],
    };
    return {
      perm: () => true,
      vols: () => JSON.stringify([{ root: '/storage/emulated/0', dev: 'hda', kind: 'food', label: 'Gamelle Nourriture', total: 128e9, free: 41e9, trash: 3 }, { root: '/storage/1A2B-3C4D', dev: 'sda', kind: 'water', label: 'Gamelle Eau', total: 256e9, free: 190e9, trash: 0 }, { root: '/storage/5E6F-7A8B', dev: 'sdb', kind: 'treat', label: 'Gamelle Récompenses', total: 64e9, free: 6e9, trash: 0 }]),
      exists: () => true,
      list: (p) => JSON.stringify({ ok: true, path: p, items: (fs[p] || [['vide.txt', 0, 10]]).map(([n, d, s], i) => d ? { n, d: true, c: s, m: now - i * 9e6 } : { n, d: false, s, m: now - i * 9e6 }).filter((x) => hidden || !x.n.startsWith('.')) }),
      trashList: () => JSON.stringify([{ p: '/storage/emulated/0/.PupPoubelle/1_vieux.jpg', n: 'vieux.jpg', from: '/storage/emulated/0/DCIM/vieux.jpg', t: now - 4e7, d: false, s: 2e6 }]),
      apps: () => JSON.stringify([{ pkg: 'fr.piika.puppyphone', n: 'PuppyPhone', v: '14', s: 2050564, u: now, launch: true }, { pkg: 'com.whatsapp', n: 'WhatsApp', v: '2.26', s: 98e6, u: now - 5e8, launch: true }]),
      props: (p) => JSON.stringify({ p, n: base(p), d: false, s: 2048, m: now, r: true, w: true, x: false, mime: 'text/plain' }),
      isText: () => true, readText: () => JSON.stringify({ ok: true, t: 'Liste de courses 🐾\n- croquettes\n- os à mâcher\n' }),
    };
  }
})();
