/* PupMusic — lecteur façon Poweramp, thème puppyplay néon. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon, PAL = PupIcons.PAL;
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const norm = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Music;
  const M = window.Music || mockM();
  const mc = (fn, ...a) => { try { return M[fn] ? M[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const haptic = () => mc('haptic');
  const LS = {
    get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { } },
  };
  const XSVG = '<svg viewBox="0 0 20 20"><path d="M4 4L16 16M16 4L4 16" stroke="#fff" stroke-width="3.4" stroke-linecap="round"/></svg>';
  const COLS = ['pink', 'cyan', 'violet', 'amber', 'green', 'red', 'blue', 'orange'];
  const colorOf = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return PAL[COLS[h % COLS.length]][1]; };
  const fmt = (ms) => { const s = Math.max(0, Math.floor((ms || 0) / 1000)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };
  const fmtLong = (ms) => { const m = Math.round((ms || 0) / 60000); return m >= 60 ? Math.floor(m / 60) + ' h ' + String(m % 60).padStart(2, '0') : m + ' min'; };
  const plural = (n, a, b) => n + ' ' + (n > 1 ? b : a);
  const ic = (g, c, sh) => I(g, c, sh ? { shape: sh } : undefined);

  // =================================================================== BIBLIOTHÈQUE
  let LIB = [], byId = new Map(), ALB = [], ART = [], GEN = [], FOL = [], YRS = [];
  const albById = new Map(), artByName = new Map(), genByName = new Map(), folByPath = new Map(), yrByN = new Map();
  let libReady = false, scanning = false;

  function ingest(arr) {
    LIB = (arr || []).map((a) => ({ id: a[0], title: a[1] || 'Sans titre', artist: a[2] || 'Artiste inconnu', album: a[3] || 'Album inconnu', albumId: a[4], dur: a[5], folder: a[6] || '',
      track: (a[7] || 0) % 1000, disc: Math.floor((a[7] || 0) / 1000), year: a[8] || 0, added: a[9] || 0, genre: a[10] || '', aartist: a[11] || '', mime: a[12] || '', br: a[13] || 0, size: a[14] || 0 }));
    byId = new Map(LIB.map((t) => [t.id, t]));
    const group = (key) => { const m = new Map(); for (const t of LIB) { const k = key(t); if (k == null) continue; let g = m.get(k); if (!g) m.set(k, g = []); g.push(t); } return m; };
    const byTrack = (a, b) => a.disc - b.disc || a.track - b.track || a.title.localeCompare(b.title, 'fr');
    const cmp = (a, b) => norm(a.name).localeCompare(norm(b.name), 'fr');
    albById.clear(); artByName.clear(); genByName.clear(); folByPath.clear(); yrByN.clear();
    ALB = [...group((t) => t.albumId).entries()].map(([id, ts]) => {
      ts.sort(byTrack);
      const cnt = new Map(); ts.forEach((t) => cnt.set(t.aartist || t.artist, (cnt.get(t.aartist || t.artist) || 0) + 1));
      const artist = [...cnt.entries()].sort((a, b) => b[1] - a[1])[0][0];
      const o = { id, name: ts[0].album, artist, year: Math.max(...ts.map((t) => t.year)), tracks: ts, first: ts[0] };
      albById.set(id, o); return o;
    }).sort(cmp);
    ART = [...group((t) => t.artist).entries()].map(([name, ts]) => {
      ts.sort((a, b) => a.album.localeCompare(b.album, 'fr') || byTrack(a, b));
      const o = { name, tracks: ts, albums: new Set(ts.map((t) => t.albumId)).size, first: ts[0] }; artByName.set(name, o); return o;
    }).sort(cmp);
    GEN = [...group((t) => t.genre || 'Sans genre').entries()].map(([name, ts]) => { const o = { name, tracks: ts, first: ts[0] }; genByName.set(name, o); return o; }).sort(cmp);
    FOL = [...group((t) => t.folder).entries()].map(([path, ts]) => {
      ts.sort((a, b) => a.title.localeCompare(b.title, 'fr'));
      const o = { path, name: path.split('/').pop() || '/', tracks: ts, first: ts[0] }; folByPath.set(path, o); return o;
    }).sort(cmp);
    YRS = [...group((t) => t.year || 0).entries()].map(([n, ts]) => { const o = { n, name: n ? String(n) : 'Année inconnue', tracks: ts, first: ts[0] }; yrByN.set(n, o); return o; }).sort((a, b) => b.n - a.n);
    libReady = true;
  }

  function scan() {
    if (scanning) return;
    if (!mc('perm')) { libReady = true; render(); return; }
    scanning = true;
    $('#lsub').textContent = 'Analyse de la bibliothèque…';
    mc('scan', LS.get('pm_min', 30));
  }

  const trackUri = (t) => t.uri || 'content://media/external/audio/media/' + t.id;
  const artUrl = (t, s) => MOCK || !t ? '' : `https://pupmusic.local/art?a=${t.albumId == null ? -1 : t.albumId}&t=${encodeURIComponent(trackUri(t))}&s=${s || 256}`;
  function artHtml(t, s, cls, seed) {
    const u = artUrl(t, s);
    return `<span class="art ${cls || ''}" style="--c:${colorOf(seed || (t && t.album) || '')}">${u ? `<img src="${u}" loading="lazy" decoding="async" alt="" onerror="this.remove()">` : ''}</span>`;
  }
  const iniHtml = (name, g) => `<span class="ini" style="--c:${colorOf(name)}">${g ? ic(g, 'chrome', 'none') : esc((String(name).replace(/[^\p{L}\p{N}]/gu, '')[0] || '#').toUpperCase())}</span>`;

  // ------------------------------------------------------------------ playlists (stockées localement)
  let PL = LS.get('pm_pl', []);
  const savePL = () => LS.set('pm_pl', PL);
  function newPlaylist(ids, cb) {
    prompt2('Nouvelle playlist', 'playlist', 'Nom de la playlist', 'Ma playlist ' + (PL.length + 1), (name) => {
      const p = { id: Date.now(), name, ids: ids || [] };
      PL.push(p); savePL(); toast(`Playlist « ${name} » créée${ids && ids.length ? ' · ' + plural(ids.length, 'titre', 'titres') : ''}`, 'playlist'); if (cb) cb(p); render();
    });
  }
  function addToPlaylist(ids) {
    const items = PL.map((p) => ({ k: 'p' + p.id, g: 'playlist', c: 'violet', l: `${p.name} <small style="opacity:.6">(${p.ids.length})</small>` }));
    items.push({ k: 'new', g: 'plus', c: 'green', l: 'Nouvelle playlist…' });
    menu(items, { title: 'Ajouter à une playlist', sub: plural(ids.length, 'titre', 'titres') }, (k) => {
      if (k === 'new') return newPlaylist(ids);
      const p = PL.find((x) => 'p' + x.id === k); if (!p) return;
      p.ids.push(...ids); savePL(); toast(`Ajouté à « ${p.name} »`, 'playlist');
    });
  }

  // =================================================================== NAVIGATION
  let stack = [{ v: 'home' }];
  let curList = [];
  const top = () => stack[stack.length - 1];
  function go(v) { stack.push(v); render(true); }
  function back() { if (stack.length > 1) { stack.pop(); render(true); return true; } return false; }
  let sortMode = LS.get('pm_sort', 'title');
  const SORTS = { title: 'Titre', artist: 'Artiste', album: 'Album', added: 'Date d\'ajout', dur: 'Durée', year: 'Année' };
  function sorted(list) {
    const l = list.slice();
    const k = sortMode;
    if (k === 'title') l.sort((a, b) => norm(a.title).localeCompare(norm(b.title), 'fr'));
    if (k === 'artist') l.sort((a, b) => norm(a.artist).localeCompare(norm(b.artist), 'fr') || norm(a.title).localeCompare(norm(b.title)));
    if (k === 'album') l.sort((a, b) => norm(a.album).localeCompare(norm(b.album), 'fr') || a.track - b.track);
    if (k === 'added') l.sort((a, b) => b.added - a.added);
    if (k === 'dur') l.sort((a, b) => b.dur - a.dur);
    if (k === 'year') l.sort((a, b) => b.year - a.year);
    return l;
  }
  const stats = () => J(mc('stats'), {}) || {};

  const CATS = [
    ['tracks', 'Titres', 'music', 'pink', () => plural(LIB.length, 'titre', 'titres')],
    ['albums', 'Albums', 'disc', 'violet', () => plural(ALB.length, 'album', 'albums')],
    ['artists', 'Artistes', 'mic', 'cyan', () => plural(ART.length, 'artiste', 'artistes')],
    ['genres', 'Genres', 'sparkle', 'amber', () => plural(GEN.length, 'genre', 'genres')],
    ['folders', 'Dossiers', 'folder', 'orange', () => plural(FOL.length, 'dossier', 'dossiers')],
    ['playlists', 'Playlists', 'playlist', 'green', () => plural(PL.length, 'playlist', 'playlists')],
    ['years', 'Années', 'calendar', 'blue', () => plural(YRS.length, 'année', 'années')],
    ['queue', 'File d\'attente', 'queue', 'red', () => plural(S.qn || 0, 'titre', 'titres')],
    ['recent', 'Ajoutés récemment', 'star', 'gold', () => '100 derniers'],
    ['top', 'Les plus écoutés', 'pulse', 'pink', () => 'Top 100'],
    ['history', 'Écoutés récemment', 'clock', 'cyan', () => 'Historique'],
  ];

  function render(scrollTop) {
    const v = top();
    const body = $('#lbody');
    $('#lback').hidden = stack.length <= 1;
    $('#mlogo').hidden = stack.length > 1;
    $('#abc').hidden = true;
    if (scrollTop) body.scrollTop = 0;
    const sub = $('#lsub');
    if (!libReady) { body.innerHTML = `<div class="empty2">${ic('disc', 'pink')}<b>Chargement…</b></div>`; return; }
    if (!mc('perm')) {
      sub.textContent = 'Autorisation requise';
      body.innerHTML = `<div class="permcard">${ic('music', 'pink')}<b>Accès à ta musique</b><p>PupMusic a besoin de l'autorisation « Musique et audio » pour lister tes morceaux.</p><button class="ab wide" data-act="perm" type="button">${ic('check', 'chrome', 'none')}Autoriser</button></div>`;
      return;
    }
    const q = $('#q').value.trim();
    if (q) return renderSearch(q);
    if (v.v === 'home') {
      sub.textContent = scanning ? 'Analyse de la bibliothèque…' : `${plural(LIB.length, 'titre', 'titres')} · ${fmtLong(LIB.reduce((a, t) => a + t.dur, 0))}`;
      const recent = ALB.slice().sort((a, b) => Math.max(...b.tracks.map((t) => t.added)) - Math.max(...a.tracks.map((t) => t.added))).slice(0, 12);
      body.innerHTML = (LIB.length ? '' : `<div class="empty2">${ic('music', 'violet')}<b>Aucun morceau trouvé</b><span>Copie de la musique dans le téléphone (dossier Music) puis relance l'analyse depuis le menu.</span></div>`) +
        `<div class="cats">${CATS.map(([k, l, g, c, n], i) => `<button class="cat${i === CATS.length - 1 && CATS.length % 2 ? ' wide' : ''}" data-go="${k}" style="--c:${PAL[c][1]}" type="button"><span class="ci">${ic(g, c)}</span><span><b>${l}</b><small>${n()}</small></span></button>`).join('')}</div>` +
        (recent.length ? `<div class="sect">Nouveautés</div><div class="strip">${recent.map(albCard).join('')}</div>` : '');
      return;
    }
    const head = (title, list, extra, heroArt) => {
      sub.textContent = title;
      curList = list;
      return `<div class="lhead">${heroArt || ''}<div class="acts"><button class="ab" data-act="playall" type="button">${ic('play', 'chrome', 'none')}Tout lire</button><button class="ab c2" data-act="shuffle" type="button">${ic('shuffle', 'chrome', 'none')}Aléatoire</button>${extra || ''}</div></div>`;
    };
    let html = '';
    switch (v.v) {
      case 'tracks': {
        const l = sorted(LIB);
        html = head('Titres', l) + `<div class="toolbar"><small>${plural(l.length, 'titre', 'titres')}</small><button class="sortbtn" data-act="sort" type="button">${ic('menu', 'cyan', 'none')}Tri : ${SORTS[sortMode]}</button></div>` + '<div id="tl"></div>';
        body.innerHTML = html; trackList($('#tl'), l, { abc: sortMode === 'title' || sortMode === 'artist' ? (t) => sortMode === 'artist' ? t.artist : t.title : null });
        return;
      }
      case 'albums': sub.textContent = 'Albums'; body.innerHTML = `<div class="acards" id="cl"></div>`; chunk($('#cl'), ALB, albCard); abcBar(ALB, (a) => a.name, '.acard'); return;
      case 'artists': sub.textContent = 'Artistes'; body.innerHTML = '<div id="cl"></div>'; chunk($('#cl'), ART, (a) => `<button class="trow" data-go="artist" data-key="${esc(a.name)}" type="button">${artHtml(a.first, 128, '', a.name)}<span class="ttx"><b>${esc(a.name)}</b><small>${plural(a.albums, 'album', 'albums')} · ${plural(a.tracks.length, 'titre', 'titres')}</small></span></button>`); abcBar(ART, (a) => a.name, '.trow'); return;
      case 'genres': sub.textContent = 'Genres'; body.innerHTML = GEN.map((g) => `<button class="trow" data-go="genre" data-key="${esc(g.name)}" type="button">${iniHtml(g.name)}<span class="ttx"><b>${esc(g.name)}</b><small>${plural(g.tracks.length, 'titre', 'titres')}</small></span></button>`).join(''); return;
      case 'years': sub.textContent = 'Années'; body.innerHTML = YRS.map((g) => `<button class="trow" data-go="year" data-key="${g.n}" type="button">${iniHtml(g.name, 'calendar')}<span class="ttx"><b>${esc(g.name)}</b><small>${plural(g.tracks.length, 'titre', 'titres')}</small></span></button>`).join(''); return;
      case 'folders': sub.textContent = 'Dossiers'; body.innerHTML = FOL.map((f) => `<button class="trow" data-go="folder" data-key="${esc(f.path)}" type="button"><span class="ini" style="--c:${PAL.amber[1]}">${ic('folder', 'chrome', 'none')}</span><span class="ttx"><b>${esc(f.name)}</b><small>${esc(f.path.replace(/^\/storage\/emulated\/0/, 'Mémoire'))} · ${f.tracks.length}</small></span></button>`).join(''); return;
      case 'playlists':
        sub.textContent = 'Playlists';
        body.innerHTML = `<div class="acts" style="margin-bottom:12px"><button class="ab green" data-act="newpl" type="button">${ic('plus', 'chrome', 'none')}Nouvelle playlist</button></div>` +
          (PL.map((p) => { const f = byId.get(p.ids.find((i) => byId.has(i))); return `<button class="trow" data-go="playlist" data-key="${p.id}" type="button">${artHtml(f, 128, '', p.name)}<span class="ttx"><b>${esc(p.name)}</b><small>${plural(p.ids.length, 'titre', 'titres')}</small></span></button>`; }).join('') || `<div class="empty2">${ic('playlist', 'green')}<b>Aucune playlist</b><span>Appuie longuement sur un titre → « Ajouter à une playlist ».</span></div>`);
        return;
      case 'queue': {
        const q = J(mc('queue'), []) || [];
        curList = q.map((t) => byId.get(t.id) || t);
        sub.textContent = 'File d\'attente';
        body.innerHTML = `<div class="acts" style="margin-bottom:12px"><button class="ab" data-act="openq" type="button">${ic('queue', 'chrome', 'none')}Réorganiser</button><button class="ab violet" data-act="q2pl" type="button">${ic('playlist', 'chrome', 'none')}En playlist</button></div><div id="tl"></div>`;
        trackList($('#tl'), curList, { queue: true });
        return;
      }
      case 'recent': { const l = LIB.slice().sort((a, b) => b.added - a.added).slice(0, 100); body.innerHTML = head('Ajoutés récemment', l) + '<div id="tl"></div>'; trackList($('#tl'), l); return; }
      case 'top': case 'history': {
        const st = stats();
        const l = LIB.filter((t) => st[t.id]).sort((a, b) => v.v === 'top' ? st[b.id][0] - st[a.id][0] : st[b.id][1] - st[a.id][1]).slice(0, 100);
        body.innerHTML = head(v.v === 'top' ? 'Les plus écoutés' : 'Écoutés récemment', l) + '<div id="tl"></div>';
        if (!l.length) $('#tl').innerHTML = `<div class="empty2">${ic('pulse', 'pink')}<b>Rien pour l'instant</b><span>Écoute quelques morceaux, ils apparaîtront ici.</span></div>`;
        else trackList($('#tl'), l, { note: (t) => v.v === 'top' ? plural(st[t.id][0], 'écoute', 'écoutes') : '' });
        return;
      }
      case 'album': {
        const a = albById.get(+v.key) || albById.get(v.key); if (!a) return back();
        const hero = `<div class="hero2">${artHtml(a.first, 400, '', a.name)}<div class="htx"><h2>${esc(a.name)}</h2><p>${esc(a.artist)}</p><small>${a.year ? a.year + ' · ' : ''}${plural(a.tracks.length, 'titre', 'titres')} · ${fmtLong(a.tracks.reduce((x, t) => x + t.dur, 0))}</small></div></div>`;
        body.innerHTML = head(a.name, a.tracks, '', hero) + '<div id="tl"></div>';
        trackList($('#tl'), a.tracks, { numbers: true });
        return;
      }
      case 'artist': {
        const a = artByName.get(v.key); if (!a) return back();
        const als = ALB.filter((x) => x.tracks.some((t) => t.artist === a.name));
        const hero = `<div class="hero2">${iniHtml(a.name)}<div class="htx"><h2>${esc(a.name)}</h2><small>${plural(als.length, 'album', 'albums')} · ${plural(a.tracks.length, 'titre', 'titres')}</small></div></div>`;
        body.innerHTML = head(a.name, a.tracks, '', hero) + (als.length > 1 ? `<div class="sect">Albums</div><div class="strip">${als.map(albCard).join('')}</div><div class="sect">Titres</div>` : '') + '<div id="tl"></div>';
        trackList($('#tl'), a.tracks);
        return;
      }
      case 'genre': case 'folder': case 'year': {
        const g = v.v === 'genre' ? genByName.get(v.key) : v.v === 'folder' ? folByPath.get(v.key) : yrByN.get(+v.key); if (!g) return back();
        body.innerHTML = head(g.name, g.tracks, '', `<div class="hero2">${v.v === 'folder' ? `<span class="ini" style="--c:${PAL.amber[1]}">${ic('folder', 'chrome', 'none')}</span>` : iniHtml(g.name, v.v === 'year' ? 'calendar' : null)}<div class="htx"><h2>${esc(g.name)}</h2><small>${plural(g.tracks.length, 'titre', 'titres')} · ${fmtLong(g.tracks.reduce((x, t) => x + t.dur, 0))}</small></div></div>`) + '<div id="tl"></div>';
        trackList($('#tl'), g.tracks);
        return;
      }
      case 'playlist': {
        const p = PL.find((x) => String(x.id) === String(v.key)); if (!p) return back();
        const l = p.ids.map((i) => byId.get(i)).filter(Boolean);
        body.innerHTML = head(p.name, l, `<button class="ab glass" data-act="plmenu" style="flex:0 0 52px;padding:0" type="button">${ic('menu', 'chrome', 'none')}</button>`, `<div class="hero2">${artHtml(l[0], 400, '', p.name)}<div class="htx"><h2>${esc(p.name)}</h2><small>${plural(l.length, 'titre', 'titres')} · ${fmtLong(l.reduce((x, t) => x + t.dur, 0))}</small></div></div>`) + '<div id="tl"></div>';
        trackList($('#tl'), l, { playlist: p });
        return;
      }
    }
  }

  function albCard(a) {
    return `<button class="acard" data-go="album" data-key="${a.id}" type="button">${artHtml(a.first, 300, '', a.name)}<b>${esc(a.name)}</b><small>${esc(a.artist)}${a.year ? ' · ' + a.year : ''}</small></button>`;
  }

  /** Rendu progressif (les grosses listes ne bloquent pas). */
  function chunk(el, list, fn, size) {
    size = size || 80;
    let i = 0;
    const more = () => {
      const part = list.slice(i, i + size); i += part.length;
      el.insertAdjacentHTML('beforeend', part.map(fn).join(''));
      if (i < list.length) {
        const s = document.createElement('div'); s.style.height = '1px'; el.appendChild(s);
        const ob = new IntersectionObserver((e) => { if (e[0].isIntersecting) { ob.disconnect(); s.remove(); more(); } }, { root: $('#lbody'), rootMargin: '900px' });
        ob.observe(s);
      }
    };
    el.innerHTML = '';
    more();
    el.renderAll = () => { while (i < list.length) { const part = list.slice(i, i + 400); i += part.length; el.insertAdjacentHTML('beforeend', part.map(fn).join('')); } };
  }

  function trackList(el, list, o) {
    o = o || {};
    curList = list;
    const nowId = S.t && S.t.id;
    chunk(el, list, (t) => {
      const i = list.indexOf(t);
      const now = o.queue ? i === S.idx : t.id === nowId;
      return `<div class="trow${now ? ' now' : ''}" data-ti="${i}" data-id="${t.id}">${o.numbers ? `<span class="no">${t.track || i + 1}</span>` : artHtml(t, 128)}<span class="ttx"><b>${now ? '<span class="eqm"><i></i><i></i><i></i></span>' : ''}${esc(t.title)}</b><small>${esc(o.numbers ? t.artist : t.artist + ' · ' + t.album)}${o.note ? ' · ' + o.note(t) : ''}</small></span><span class="du">${fmt(t.dur)}</span><button class="more" data-more="${i}" type="button">${ic('menu', 'chrome', 'none')}</button></div>`;
    });
    el.ctx = o;
    if (o.abc) abcBar(list, o.abc, '.trow');
  }

  /** Barre alphabétique rapide. */
  function abcBar(list, keyFn, sel) {
    const bar = $('#abc');
    if (list.length < 40) return;
    const L = '#ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
    const first = {};
    list.forEach((x, i) => { let c = norm(keyFn(x))[0] || '#'; c = /[a-z]/.test(c) ? c.toUpperCase() : '#'; if (first[c] == null) first[c] = i; });
    bar.innerHTML = L.map((c) => `<span style="opacity:${first[c] == null ? .3 : 1}">${c}</span>`).join('');
    bar.hidden = false;
    let pop = null;
    const pick = (e) => {
      const r = bar.getBoundingClientRect();
      const k = Math.max(0, Math.min(L.length - 1, Math.floor((e.clientY - r.top) / r.height * L.length)));
      let c = L[k], j = k;
      while (first[c] == null && j < L.length - 1) c = L[++j];
      if (first[c] == null) return;
      if (!pop) { pop = document.createElement('div'); pop.className = 'abcpop'; document.body.appendChild(pop); }
      pop.textContent = c;
      const host = $('#lbody').querySelector('#tl, #cl');
      if (host && host.renderAll) host.renderAll();
      const rows = $$(sel, $('#lbody'));
      const target = rows[first[c]];
      if (target) $('#lbody').scrollTop = target.offsetTop - 10;
    };
    bar.onpointerdown = (e) => { bar.setPointerCapture(e.pointerId); pick(e); haptic(); };
    bar.onpointermove = (e) => { if (e.buttons) pick(e); };
    bar.onpointerup = bar.onpointercancel = () => { if (pop) { pop.remove(); pop = null; } };
  }

  function renderSearch(q) {
    const n = norm(q);
    const tr = LIB.filter((t) => norm(t.title + ' ' + t.artist + ' ' + t.album).includes(n)).slice(0, 200);
    const al = ALB.filter((a) => norm(a.name + ' ' + a.artist).includes(n)).slice(0, 20);
    const ar = ART.filter((a) => norm(a.name).includes(n)).slice(0, 20);
    $('#lsub').textContent = `Recherche · ${tr.length} résultat${tr.length > 1 ? 's' : ''}`;
    const body = $('#lbody');
    body.innerHTML = (ar.length ? `<div class="sect">Artistes</div>` + ar.map((a) => `<button class="trow" data-go="artist" data-key="${esc(a.name)}" type="button">${iniHtml(a.name)}<span class="ttx"><b>${esc(a.name)}</b><small>${plural(a.tracks.length, 'titre', 'titres')}</small></span></button>`).join('') : '') +
      (al.length ? `<div class="sect">Albums</div><div class="strip">${al.map(albCard).join('')}</div>` : '') +
      (tr.length ? `<div class="sect">Titres</div><div id="tl"></div>` : '') +
      (!tr.length && !al.length && !ar.length ? `<div class="empty2">${ic('search', 'cyan')}<b>Rien trouvé</b><span>Essaie un autre mot.</span></div>` : '');
    if (tr.length) trackList($('#tl'), tr);
  }

  // ------------------------------------------------------------------ actions bibliothèque
  function playList(list, i, shuffle) {
    const ids = list.map((t) => t.id).filter((x) => x >= 0);
    if (!ids.length) return;
    haptic();
    mc('play', JSON.stringify(ids), i == null ? (shuffle ? -1 : 0) : i, !!shuffle);
    if (LS.get('pm_autoopen', true)) setTimeout(openPlayer, 120);
  }

  function trackMenu(t, ctx, i) {
    const items = [
      { k: 'play', g: 'play', c: 'pink', l: 'Lire' },
      { k: 'next', g: 'next', c: 'cyan', l: 'Lire ensuite' },
      { k: 'add', g: 'queue', c: 'violet', l: 'Ajouter à la file' },
      { k: 'pl', g: 'playlist', c: 'green', l: 'Ajouter à une playlist' },
      { k: 'album', g: 'disc', c: 'amber', l: 'Aller à l\'album' },
      { k: 'artist', g: 'mic', c: 'blue', l: 'Aller à l\'artiste' },
      { k: 'share', g: 'share', c: 'orange', l: 'Partager' },
      { k: 'info', g: 'info', c: 'cyan', l: 'Infos du fichier' },
    ];
    if (ctx && ctx.playlist) items.push({ k: 'plrm', g: 'trash', c: 'red', l: 'Retirer de la playlist', danger: 1 });
    if (ctx && ctx.queue) items.push({ k: 'qrm', g: 'trash', c: 'red', l: 'Retirer de la file', danger: 1 });
    menu(items, { title: t.title, sub: t.artist, art: t }, (k) => {
      if (k === 'play') playList(curList, i);
      if (k === 'next' || k === 'add') { mc('enqueue', JSON.stringify([t.id]), k === 'next'); toast(k === 'next' ? 'Sera lu juste après' : 'Ajouté à la file', 'queue'); }
      if (k === 'pl') addToPlaylist([t.id]);
      if (k === 'album') { closePlayer(); go({ v: 'album', key: t.albumId }); }
      if (k === 'artist') { closePlayer(); go({ v: 'artist', key: t.artist }); }
      if (k === 'share') mc('share', trackUri(t), t.title);
      if (k === 'info') trackInfo(t);
      if (k === 'plrm') { const p = ctx.playlist; p.ids.splice(p.ids.indexOf(t.id), 1); savePL(); render(); }
      if (k === 'qrm') { mc('qremove', i); setTimeout(render, 200); }
    });
  }

  function trackInfo(t) {
    const lt = byId.get(t.id) || t;
    const inf = S.t && S.t.id === t.id ? S.info || {} : {};
    const rows = [['Titre', lt.title], ['Artiste', lt.artist], ['Album', lt.album], ['Artiste album', lt.aartist], ['Genre', lt.genre], ['Année', lt.year || ''], ['Piste', lt.track ? lt.track + (lt.disc ? ' · disque ' + lt.disc : '') : ''],
      ['Durée', fmt(lt.dur)], ['Format', [inf.codec || (lt.mime || '').replace('audio/', '').toUpperCase(), inf.rate ? (inf.rate / 1000) + ' kHz' : '', inf.bits ? inf.bits + ' bits' : '', inf.ch ? (inf.ch === 1 ? 'mono' : inf.ch === 2 ? 'stéréo' : inf.ch + ' canaux') : ''].filter(Boolean).join(' · ')],
      ['Débit', inf.kbps ? inf.kbps + ' kbps' : lt.br ? Math.round(lt.br / 1000) + ' kbps' : ''], ['Taille', lt.size ? (lt.size / 1048576).toFixed(1) + ' Mo' : ''], ['Fichier', lt.path || (lt.folder ? lt.folder + '/' : '')]].filter((r) => r[1]);
    win('Infos du titre', 'info', 'cyan', `<div class="hero2" style="margin-bottom:12px">${artHtml(lt, 300, '', lt.album)}<div class="htx"><h2 style="font-size:17px">${esc(lt.title)}</h2><p>${esc(lt.artist)}</p></div></div><div class="kv">${rows.map(([k, v]) => `<span>${k}</span><b>${esc(v)}</b>`).join('')}</div>`);
  }

  $('#lbody').addEventListener('click', (e) => {
    const b = e.target.closest('[data-more],[data-ti],[data-go],[data-act]');
    if (!b) return;
    const d = b.dataset;
    if (d.more != null) { e.stopPropagation(); const host = b.closest('#tl'); const i = +d.more; trackMenu(curList[i], host && host.ctx, i); return; }
    if (d.ti != null) {
      if (longFired) { longFired = false; return; }
      const host = b.closest('#tl');
      if (host && host.ctx && host.ctx.queue) { mc('jump', +d.ti); openPlayer(); return; }
      playList(curList, +d.ti); return;
    }
    if (d.go) { if (d.go === 'queue') { go({ v: 'queue' }); return; } go({ v: d.go, key: d.key }); return; }
    const a = d.act;
    if (a === 'perm') mc('askPerm');
    if (a === 'playall') playList(curList, 0, false);
    if (a === 'shuffle') playList(curList, null, true);
    if (a === 'sort') menu(Object.entries(SORTS).map(([k, l]) => ({ k, g: k === sortMode ? 'check' : 'menu', c: k === sortMode ? 'green' : 'chrome', l })), { title: 'Trier par' }, (k) => { sortMode = k; LS.set('pm_sort', k); render(); });
    if (a === 'newpl') newPlaylist([]);
    if (a === 'openq') openQueue();
    if (a === 'q2pl') newPlaylist(curList.map((t) => t.id).filter((x) => x >= 0));
    if (a === 'plmenu') {
      const p = PL.find((x) => String(x.id) === String(top().key));
      menu([{ k: 'ren', g: 'edit', c: 'cyan', l: 'Renommer' }, { k: 'q', g: 'queue', c: 'violet', l: 'Ajouter à la file' }, { k: 'del', g: 'trash', c: 'red', l: 'Supprimer la playlist', danger: 1 }], { title: p.name }, (k) => {
        if (k === 'ren') prompt2('Renommer', 'edit', 'Nouveau nom', p.name, (n) => { p.name = n; savePL(); render(); });
        if (k === 'q') mc('enqueue', JSON.stringify(p.ids), false);
        if (k === 'del') confirm2('Supprimer la playlist ?', `« ${p.name} » sera supprimée (les morceaux restent sur le téléphone).`, 'Supprimer', () => { PL = PL.filter((x) => x !== p); savePL(); back(); });
      });
    }
  });
  // appui long sur une ligne = menu
  let longT = null, longFired = false, longXY = null;
  $('#lbody').addEventListener('pointerdown', (e) => {
    const r = e.target.closest('[data-ti]'); if (!r || e.target.closest('.more')) return;
    longXY = [e.clientX, e.clientY];
    longT = setTimeout(() => { longFired = true; haptic(); const host = r.closest('#tl'); trackMenu(curList[+r.dataset.ti], host && host.ctx, +r.dataset.ti); }, 520);
  });
  const cancelLong = (e) => { if (longT && (!e || !longXY || e.type !== 'pointermove' || Math.hypot(e.clientX - longXY[0], e.clientY - longXY[1]) > 10)) { clearTimeout(longT); longT = null; } };
  ['pointerup', 'pointercancel', 'pointermove'].forEach((ev) => $('#lbody').addEventListener(ev, cancelLong, { passive: true }));
  $('#lbody').addEventListener('scroll', () => cancelLong(), { passive: true });

  const qIn = $('#q');
  let qT = null;
  qIn.oninput = () => { $('#qx').hidden = !qIn.value; clearTimeout(qT); qT = setTimeout(() => render(true), 160); };
  $('#qx').onclick = () => { qIn.value = ''; $('#qx').hidden = true; render(true); };
  $('#lback').onclick = back;
  $('#lmenu').onclick = () => libMenu();

  function libMenu() {
    menu([
      { k: 'scan', g: 'refresh', c: 'cyan', l: 'Relancer l\'analyse' },
      { k: 'eq', g: 'eq', c: 'pink', l: 'Égaliseur' },
      { k: 'set', g: 'gear', c: 'violet', l: 'Réglages PupMusic' },
      { k: 'sleep', g: 'moon', c: 'blue', l: 'Minuterie de sommeil' },
    ], { title: 'PupMusic', sub: 'Menu' }, (k) => {
      if (k === 'scan') { libReady = true; scan(); }
      if (k === 'eq') openEq();
      if (k === 'set') openSettings();
      if (k === 'sleep') sleepMenu();
    });
  }

  // =================================================================== ÉTAT NATIF
  let S = { idx: -1, qn: 0 };
  let lastTid = null, lastQv = -1, posMs = 0, durMs = 0, playing = false;
  function onState(js) {
    const o = J(js, null); if (!o) return;
    S = o;
    playing = !!o.playing;
    posMs = o.pos || 0; durMs = o.dur || (o.t && o.t.dur) || 0;
    const tid = o.t ? o.t.id + '|' + o.t.uri : null;
    if (tid !== lastTid) { lastTid = tid; trackChanged(); }
    if (o.qv !== lastQv) { lastQv = o.qv; if (qPanel) renderQueue(); if (top().v === 'queue' && !$('#q').value) render(); }
    updateControls();
    updateMini();
    markNow();
  }

  function markNow() {
    const id = S.t && S.t.id;
    const host = $('#tl'); const isQ = host && host.ctx && host.ctx.queue;
    $$('.trow[data-ti]', $('#lbody')).forEach((r) => {
      const now = isQ ? +r.dataset.ti === S.idx : +r.dataset.id === id;
      if (now !== r.classList.contains('now')) {
        r.classList.toggle('now', now);
        const b = $('.ttx b', r); const eq = $('.eqm', b);
        if (now && !eq) b.insertAdjacentHTML('afterbegin', '<span class="eqm"><i></i><i></i><i></i></span>');
        if (!now && eq) eq.remove();
      }
    });
    document.body.classList.toggle('paused', !playing);
  }

  // =================================================================== LECTEUR
  const player = $('#player');
  let playerOpen = false;
  function openPlayer() { if (!S.t) return; playerOpen = true; player.classList.remove('down'); startVis(); }
  function closePlayer() { playerOpen = false; player.classList.add('down'); stopVis(); }

  function trackChanged() {
    const t = S.t;
    $('#ttl').textContent = t ? t.title : 'PupMusic';
    $('#art2').textContent = t ? (t.artist || 'Artiste inconnu') : 'Choisis un titre dans la bibliothèque';
    $('#alb').textContent = t ? t.album || '' : '';
    marquee();
    const img = $('#artimg'), bg = $('#pbgimg');
    const u = t ? artUrl(byId.get(t.id) || t, 720) : '';
    $('#artbig').style.setProperty('--c', colorOf(t ? t.album : ''));
    img.classList.remove('ok'); bg.classList.remove('ok');
    img.style.display = u ? '' : 'none';
    if (u) {
      img.onload = () => { img.classList.add('ok'); bg.src = u; tint(img); };
      img.onerror = () => { img.style.display = 'none'; bg.removeAttribute('src'); tint(null, t.album); };
      img.src = u;
    } else { bg.removeAttribute('src'); tint(null, t ? t.album : ''); }
    bg.onload = () => bg.classList.add('ok');
    if (lyrPanel) loadLyrics();
  }

  function marquee() {
    const mq = $('.mq'), b = $('#ttl');
    mq.classList.remove('run');
    requestAnimationFrame(() => {
      const over = b.scrollWidth - mq.clientWidth;
      if (over > 4) { mq.style.setProperty('--mqx', -over - 20 + 'px'); mq.style.setProperty('--mqd', Math.max(6, over / 25) + 's'); mq.classList.add('run'); }
    });
  }

  /** Couleur néon dynamique tirée de la pochette. */
  function tint(img, seed) {
    const body = document.body.style;
    if (!LS.get('pm_dyn', true)) { body.removeProperty('--acc'); body.removeProperty('--acc2'); return; }
    let h = null;
    if (img) {
      try {
        const c = document.createElement('canvas'); c.width = c.height = 24;
        const x = c.getContext('2d', { willReadFrequently: true }); x.drawImage(img, 0, 0, 24, 24);
        const d = x.getImageData(0, 0, 24, 24).data;
        let best = [], sx = 0, sy = 0, n = 0;
        for (let i = 0; i < d.length; i += 4) {
          const r = d[i] / 255, g = d[i + 1] / 255, b = d[i + 2] / 255, mx = Math.max(r, g, b), mn = Math.min(r, g, b);
          const s = mx === 0 ? 0 : (mx - mn) / mx;
          best.push([s * mx, r, g, b, mx, mn]);
        }
        best.sort((a, b) => b[0] - a[0]); best = best.slice(0, 60);
        for (const [w, r, g, b, mx, mn] of best) {
          if (mx === mn) continue;
          let hh = mx === r ? (g - b) / (mx - mn) : mx === g ? 2 + (b - r) / (mx - mn) : 4 + (r - g) / (mx - mn);
          hh = (hh * 60 + 360) % 360;
          sx += Math.cos(hh * Math.PI / 180) * w; sy += Math.sin(hh * Math.PI / 180) * w; n += w;
        }
        if (n > 1) h = (Math.atan2(sy, sx) * 180 / Math.PI + 360) % 360;
      } catch (e) { h = null; }
    }
    if (h == null) { let k = 0; for (const ch of String(seed || 'pup')) k = (k * 31 + ch.charCodeAt(0)) >>> 0; h = [325, 190, 265, 40, 155, 350][k % 6]; }
    body.setProperty('--acc', `hsl(${h.toFixed(0)} 100% 62%)`);
    body.setProperty('--acc2', `hsl(${((h + 150) % 360).toFixed(0)} 100% 62%)`);
  }

  function updateControls() {
    $('#bplay').innerHTML = ic(playing ? 'pause' : 'play', 'chrome', 'none');
    player.classList.toggle('playing', playing);
    $('#bshuf').classList.toggle('on', !!S.shuffle);
    $('#brep').classList.toggle('on', S.repeat > 0);
    $('#brep').innerHTML = ic('loop', 'chrome', 'none') + (S.repeat === 2 ? '<span class="one">1</span>' : '');
    const inf = S.info || {};
    const parts = [S.qn ? (S.idx + 1) + '/' + S.qn : ''];
    if (inf.codec) parts.push(inf.codec);
    if (inf.rate) parts.push((inf.rate / 1000).toString().replace('.', ',') + ' kHz');
    if (inf.bits) parts.push(inf.bits + ' bits');
    if (inf.kbps) parts.push(inf.kbps + ' kbps');
    $('#pinfo').textContent = parts.filter(Boolean).join(' · ') || '—';
    const hiRes = (inf.rate || 0) > 48000 || (inf.bits || 0) > 16;
    $('#pinfo').style.color = hiRes ? 'var(--amber)' : '';
    $$('.tool').forEach((t) => {
      const k = t.dataset.tool;
      if (k === 'sleep') t.classList.toggle('on', !!S.sleep);
      if (k === 'speed') t.classList.toggle('on', Math.abs((S.speed || 1) - 1) > .01 || Math.abs((S.pitch || 1) - 1) > .01);
      if (k === 'eq') t.classList.toggle('on', fx.on && !isFlat());
    });
    updatePos();
  }

  function updateMini() {
    const m = $('#mini');
    const t = S.t;
    m.hidden = !t;
    if (!t) return;
    const lt = byId.get(t.id) || t;
    const key = t.id + '|' + playing;
    if (m.dataset.k !== key) {
      m.dataset.k = key;
      m.innerHTML = `${artHtml(lt, 160)}<span class="ttx"><b>${esc(t.title)}</b><small>${esc(t.artist || 'Artiste inconnu')}</small></span><button class="mbtn orb" data-m="toggle" type="button">${ic(playing ? 'pause' : 'play', 'chrome', 'none')}</button><button class="mbtn" data-m="next" type="button">${ic('next', 'chrome', 'none')}</button><span class="mprog"><i></i></span>`;
    }
    updatePos();
  }
  $('#mini').onclick = (e) => {
    const b = e.target.closest('[data-m]');
    if (b) { e.stopPropagation(); haptic(); if (b.dataset.m === 'toggle') { playing = !playing; updateMini(); updateControls(); mc('toggle'); } else mc('next'); return; }
    openPlayer();
  };

  // position
  let seeking = false;
  function updatePos() {
    const f = durMs > 0 ? Math.min(1, posMs / durMs) : 0;
    if (!seeking) {
      $('#sfill').style.width = (f * 100) + '%';
      $('#sthumb').style.left = (f * 100) + '%';
      $('#tpos').textContent = fmt(posMs);
    }
    $('#tdur').textContent = fmt(durMs);
    const mp = $('#mini .mprog i'); if (mp) mp.style.width = (f * 100) + '%';
    if (lyrPanel) lyrTick();
  }
  setInterval(() => {
    if (document.hidden) return;
    const p = String(mc('pos') || '').split(',');
    if (p.length === 3) {
      posMs = +p[0]; durMs = +p[1] || durMs;
      const pl = p[2] === '1';
      if (pl !== playing) { playing = pl; updateControls(); updateMini(); markNow(); }
    }
    updatePos();
  }, 250);

  // barre de progression
  (function () {
    const sk = $('#seek');
    let tip = null;
    const at = (e) => { const r = sk.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)); };
    const show = (e) => {
      const f = at(e);
      $('#sfill').style.width = f * 100 + '%'; $('#sthumb').style.left = f * 100 + '%';
      $('#tpos').textContent = fmt(f * durMs);
      if (!tip) { tip = document.createElement('div'); tip.className = 'seektip'; document.body.appendChild(tip); }
      const r = sk.getBoundingClientRect();
      tip.style.left = (r.left + f * r.width) + 'px'; tip.style.top = (r.top - 8) + 'px';
      tip.textContent = fmt(f * durMs);
    };
    sk.onpointerdown = (e) => { if (!durMs) return; seeking = true; sk.classList.add('on'); sk.setPointerCapture(e.pointerId); show(e); };
    sk.onpointermove = (e) => { if (seeking) show(e); };
    sk.onpointerup = (e) => { if (!seeking) return; const f = at(e); seeking = false; sk.classList.remove('on'); if (tip) { tip.remove(); tip = null; } posMs = f * durMs; mc('seek', Math.round(posMs)); haptic(); };
    sk.onpointercancel = () => { seeking = false; sk.classList.remove('on'); if (tip) { tip.remove(); tip = null; } };
  })();

  // gestes sur la pochette : gauche/droite = titre suivant/précédent, bas = réduire
  (function () {
    const st = $('#stage'), fr = $('#frame');
    let x0 = 0, y0 = 0, on = false;
    st.onpointerdown = (e) => { on = true; x0 = e.clientX; y0 = e.clientY; };
    st.onpointermove = (e) => {
      if (!on) return;
      const dx = e.clientX - x0, dy = e.clientY - y0;
      if (Math.abs(dx) > Math.abs(dy)) fr.style.transform = `translateX(${dx * .6}px) rotate(${dx / 40}deg)`;
      else if (dy > 0) { player.classList.add('drag'); player.style.transform = `translateY(${dy * .8}px)`; }
    };
    const end = (e) => {
      if (!on) return; on = false;
      const dx = e.clientX - x0, dy = e.clientY - y0;
      fr.style.transform = ''; player.classList.remove('drag'); player.style.transform = '';
      if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy)) {
        fr.classList.add(dx < 0 ? 'swl' : 'swr'); haptic();
        setTimeout(() => { fr.classList.remove('swl', 'swr'); fr.style.transition = 'none'; fr.style.transform = `translateX(${dx < 0 ? 120 : -120}%)`; fr.offsetWidth; fr.style.transition = ''; fr.style.transform = ''; }, 220);
        mc(dx < 0 ? 'next' : 'prev');
      } else if (dy > 110 && Math.abs(dy) > Math.abs(dx)) closePlayer();
      else if (Math.abs(dx) < 8 && Math.abs(dy) < 8) { /* tap */ }
    };
    st.onpointerup = end; st.onpointercancel = end;
  })();

  $('#bplay').onclick = () => { haptic(); playing = !playing; updateControls(); updateMini(); mc('toggle'); };
  $('#bprev').onclick = () => { haptic(); mc('prev'); };
  $('#bnext').onclick = () => { haptic(); mc('next'); };
  $('#bshuf').onclick = () => { haptic(); S.shuffle = !S.shuffle; updateControls(); mc('shuffle', S.shuffle); toast(S.shuffle ? 'Lecture aléatoire activée' : 'Lecture aléatoire désactivée', 'shuffle'); };
  $('#brep').onclick = () => { haptic(); S.repeat = ((S.repeat || 0) + 1) % 3; updateControls(); mc('repeat', S.repeat); toast(['Répétition désactivée', 'Répéter toute la file', 'Répéter ce titre'][S.repeat], 'loop'); };
  $('#pdown').onclick = closePlayer;
  $('#pmenu').onclick = () => playerMenu();

  const TOOLS = [['eq', 'eq', 'pink', 'Égaliseur'], ['queue', 'queue', 'violet', 'File'], ['lyrics', 'lyrics', 'cyan', 'Paroles'], ['sleep', 'moon', 'blue', 'Sommeil'], ['speed', 'timer', 'amber', 'Vitesse'], ['more', 'menu', 'chrome', 'Plus']];
  $('#tools').innerHTML = TOOLS.map(([k, g, c, l]) => `<button class="tool" data-tool="${k}" type="button">${ic(g, c, 'orb')}<span>${l}</span></button>`).join('');
  $('#tools').onclick = (e) => {
    const b = e.target.closest('[data-tool]'); if (!b) return; haptic();
    const k = b.dataset.tool;
    if (k === 'eq') openEq();
    if (k === 'queue') openQueue();
    if (k === 'lyrics') openLyrics();
    if (k === 'sleep') sleepMenu();
    if (k === 'speed') openSpeed();
    if (k === 'more') playerMenu();
  };

  function playerMenu() {
    const t = S.t; if (!t) return libMenu();
    const lt = byId.get(t.id) || t;
    menu([
      { k: 'info', g: 'info', c: 'cyan', l: 'Infos du titre' },
      { k: 'pl', g: 'playlist', c: 'green', l: 'Ajouter à une playlist' },
      { k: 'album', g: 'disc', c: 'amber', l: 'Aller à l\'album' },
      { k: 'artist', g: 'mic', c: 'blue', l: 'Aller à l\'artiste' },
      { k: 'share', g: 'share', c: 'orange', l: 'Partager' },
      { k: 'vol', g: 'speaker', c: 'violet', l: 'Volume du téléphone' },
      { k: 'set', g: 'gear', c: 'chrome', l: 'Réglages PupMusic' },
    ], { title: t.title, sub: t.artist, art: lt }, (k) => {
      if (k === 'info') trackInfo(lt);
      if (k === 'pl') addToPlaylist([t.id]);
      if (k === 'album') { closePlayer(); stack = [{ v: 'home' }]; go({ v: 'album', key: lt.albumId }); }
      if (k === 'artist') { closePlayer(); stack = [{ v: 'home' }]; go({ v: 'artist', key: lt.artist }); }
      if (k === 'share') mc('share', trackUri(lt), lt.title);
      if (k === 'vol') mc('sysVolume', 0);
      if (k === 'set') openSettings();
    });
  }

  // ------------------------------------------------------------------ visualiseur (spectre néon)
  const cv = $('#spec'), cx = cv.getContext('2d');
  let visRaf = 0, bars = new Float32Array(48), peaks = new Float32Array(48), visWanted = LS.get('pm_vis', false), visTip = null;
  function startVis() {
    if (visWanted) { if (mc('hasMic')) mc('vis', true); else visWanted = false; }
    if (!visRaf) visRaf = requestAnimationFrame(drawVis);
    if (!visWanted && !visTip && !LS.get('pm_vistip', false)) {
      visTip = document.createElement('div'); visTip.className = 'vistip'; visTip.textContent = 'Touche ici pour activer le spectre néon';
      cv.after(visTip);
    }
  }
  function stopVis() { if (visRaf) cancelAnimationFrame(visRaf); visRaf = 0; mc('vis', false); }
  cv.onclick = () => {
    LS.set('pm_vistip', true); if (visTip) { visTip.remove(); visTip = null; }
    visWanted = !visWanted; LS.set('pm_vis', visWanted);
    if (visWanted) { if (mc('hasMic')) mc('vis', true); else { win('Spectre néon', 'pulse', 'pink', '<p>Android exige l\'autorisation <b>micro</b> pour lire le spectre de la musique. <b>Rien n\'est enregistré</b> : PupMusic analyse seulement le son qu\'il joue lui-même.</p>', [['Autoriser', 'c', () => mc('askMic')]]); } }
    else mc('vis', false);
  };
  let tIdle = 0;
  function drawVis() {
    visRaf = requestAnimationFrame(drawVis);
    const W = cv.clientWidth, H = cv.clientHeight, dpr = Math.min(2, devicePixelRatio || 1);
    if (cv.width !== Math.round(W * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
    cx.setTransform(dpr, 0, 0, dpr, 0, 0);
    cx.clearRect(0, 0, W, H);
    const n = 48;
    let data = null;
    if (visWanted && playing) { const s = mc('fft'); if (s) data = s.split(','); }
    tIdle += 0.04;
    for (let i = 0; i < n; i++) {
      let v;
      if (data) v = (+data[i] || 0) / 255;
      else if (MOCK && playing) v = Math.max(0, Math.sin(i * .35 + tIdle * 3) * .35 + Math.random() * .4 + (i < 8 ? .3 : 0));
      else v = playing ? .06 + Math.sin(i * .4 + tIdle * 2) * .03 + .03 : .04 + Math.sin(i * .3 + tIdle) * .02;
      bars[i] = v > bars[i] ? v : bars[i] * .86 + v * .14;
      peaks[i] = Math.max(peaks[i] - .012, bars[i]);
    }
    const st = getComputedStyle(document.body);
    const a1 = st.getPropertyValue('--acc').trim() || '#ff3fa4', a2 = st.getPropertyValue('--acc2').trim() || '#29e6ff';
    const gw = W / n, bw = Math.max(2, gw * .62);
    const g = cx.createLinearGradient(0, H, 0, 0); g.addColorStop(0, a1); g.addColorStop(1, a2);
    cx.shadowColor = a1; cx.shadowBlur = 10;
    for (let i = 0; i < n; i++) {
      const h = Math.max(2, bars[i] * (H - 6)), x = i * gw + (gw - bw) / 2;
      cx.fillStyle = g;
      cx.fillRect(x, H - h, bw, h);
      cx.fillStyle = 'rgba(255,255,255,.85)';
      cx.fillRect(x, H - Math.max(3, peaks[i] * (H - 6)) - 3, bw, 2);
    }
    cx.shadowBlur = 0;
    cx.fillStyle = 'rgba(255,255,255,.18)';
    for (let i = 0; i < n; i++) { const h = Math.max(2, bars[i] * (H - 6)); cx.fillRect(i * gw + (gw - bw) / 2, H - h, bw * .35, h); }
  }

  // =================================================================== ÉGALISEUR
  const FQ = ['31', '62', '125', '250', '500', '1k', '2k', '4k', '8k', '16k'];
  const FHZ = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  const WB = [1, 1, .8, .45, .15, 0, 0, 0, 0, 0], WT = [0, 0, 0, 0, 0, 0, .2, .55, .9, 1];
  const PRESETS = {
    'Plat': [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    'Puppy Boom 🐾': [7, 6, 4, 1, -1, 0, 1, 3, 4, 4],
    'Basses+': [8, 7, 5, 2, 0, 0, 0, 0, 0, 0],
    'Rock': [5, 4, 2, -1, -2, -1, 2, 4, 5, 5],
    'Pop': [-1, 1, 3, 4, 3, 0, -1, -1, 1, 2],
    'Électro': [6, 5, 1, 0, -2, 1, 0, 2, 5, 6],
    'Hip-hop': [6, 5, 2, 3, -1, -1, 1, 0, 2, 3],
    'Jazz': [3, 2, 1, 2, -1, -1, 0, 1, 2, 3],
    'Classique': [4, 3, 2, 1, -1, -1, 0, 2, 3, 4],
    'Voix': [-3, -2, 0, 2, 4, 4, 3, 1, 0, -1],
    'Aigus+': [0, 0, 0, 0, 0, 1, 3, 5, 7, 8],
    'Loudness': [6, 4, 0, 0, -2, 0, -1, -2, 5, 2],
    'Casque': [3, 2, 1, 0, 0, 0, 1, 2, 3, 2],
    'Petit HP': [-4, -2, 2, 4, 3, 2, 2, 3, 2, 0],
  };
  const FXDEF = { on: true, bands: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], pre: 0, bass: 0, treble: 0, boost: 0, limiter: true, virt: 0, unlock: false, preset: 'Plat' };
  let fx = Object.assign({}, FXDEF, J(mc('fxGet'), {}) || {});
  if (!Array.isArray(fx.bands) || fx.bands.length !== 10) fx.bands = FXDEF.bands.slice();
  const isFlat = () => fx.bands.every((b) => !b) && !fx.pre && !fx.bass && !fx.treble && !fx.boost && !fx.virt;
  let fxT = null;
  function sendFx() { clearTimeout(fxT); fxT = setTimeout(() => { mc('fx', JSON.stringify(fx)); updateControls(); }, 40); }
  const lim = () => ({ band: fx.unlock ? 24 : 12, preMax: fx.unlock ? 15 : 6, boost: fx.unlock ? 24 : 6 });
  function clampFx() {
    const L = lim();
    fx.bands = fx.bands.map((b) => Math.max(-L.band, Math.min(L.band, b)));
    fx.pre = Math.max(-15, Math.min(L.preMax, fx.pre));
    fx.bass = Math.max(-L.band, Math.min(L.band, fx.bass)); fx.treble = Math.max(-L.band, Math.min(L.band, fx.treble));
    fx.boost = Math.max(0, Math.min(L.boost, fx.boost));
    if (!fx.unlock) fx.limiter = true;
  }

  function openEq() {
    const p = panel('Égaliseur', S.fx || 'PupMusic DSP', 'eq', 'pink');
    const L = lim();
    document.body.classList.toggle('unlocked', !!fx.unlock);
    const userP = LS.get('pm_presets', {});
    $('.pbd', p).innerHTML = `
      <div class="row" style="margin-bottom:8px"><span>Égaliseur actif<small>${esc(S.fx || '')}</small></span><button class="sw${fx.on ? ' on' : ''}" id="eqon" type="button"><i></i></button></div>
      <canvas class="eqcurve" id="eqc"></canvas>
      <div class="presets" id="pres">${[...Object.keys(PRESETS), ...Object.keys(userP)].map((n) => `<button class="chip${fx.preset === n ? ' on' : ''}" data-p="${esc(n)}" type="button">${esc(n)}</button>`).join('')}<button class="chip add" data-p="+save" type="button">＋ Enregistrer</button></div>
      <div class="bands">${FQ.map((f, i) => `<div class="band"><span class="val" id="bv${i}"></span><div class="vsl" data-b="${i}"><i class="tr"></i><i class="zero"></i><i class="fill"></i><b class="th"></b></div><span class="fq">${f}</span></div>`).join('')}</div>
      <div class="knobs" id="knobs"></div>
      <div class="row"><span>Limiteur anti-saturation<small>${fx.unlock ? 'Désactivable en mode débridé' : 'Toujours actif hors mode débridé'}</small></span><button class="sw${fx.limiter ? ' on' : ''}" id="limsw" type="button" ${fx.unlock ? '' : 'disabled style="opacity:.5"'}><i></i></button></div>
      <div class="unlock">${ic('bolt', 'red')}<span><b>Débrider le son</b><small>EQ ±24 dB · pré-ampli +15 dB · boost +24 dB · limiteur coupable</small></span><button class="sw${fx.unlock ? ' on' : ''}" id="unl" type="button"><i></i></button></div>
      <div class="fxinfo">Moteur : ${esc(S.fx || '—')}${S.fxerr ? ' · ' + esc(S.fxerr) : ''}</div>`;
    const KN = [
      ['pre', 'Pré-ampli', -15, L.preMax, 'dB', 'var(--acc)'],
      ['bass', 'Basses', -L.band, L.band, 'dB', '#ff7a29'],
      ['treble', 'Aigus', -L.band, L.band, 'dB', '#29e6ff'],
      ['boost', 'Boost', 0, L.boost, 'dB', '#ff4d5e'],
      ['virt', 'Stéréo large', 0, 100, '%', '#9b5cff'],
      ['bal', 'Balance', -100, 100, '', '#3dffb0'],
    ];
    $('#knobs', p).innerHTML = KN.map(([k, l]) => `<div class="knob" data-k="${k}"><div class="kdial"><i class="ring"></i><i class="cap"></i><i class="ptr"></i></div><b></b><small>${l}</small></div>`).join('');
    const bal = () => Math.round((S.bal || 0) * 100);
    const kget = (k) => k === 'bal' ? bal() : fx[k];
    const kset = (k, v) => { if (k === 'bal') { S.bal = v / 100; clearTimeout(kset.t); kset.t = setTimeout(() => mc('opts', JSON.stringify({ bal: S.bal })), 50); } else { fx[k] = v; fx.preset = fx.preset && PRESETS[fx.preset] ? fx.preset : fx.preset; sendFx(); } };
    function paintKnobs() {
      KN.forEach(([k, , mn, mx, u, col]) => {
        const el = $(`.knob[data-k="${k}"]`, p); const v = kget(k);
        const f = (v - mn) / (mx - mn);
        el.style.setProperty('--kc', col); el.style.setProperty('--ka', (f * 270) + 'deg');
        $('.ptr', el).style.transform = `rotate(${-135 + f * 270}deg)`;
        $('b', el).textContent = k === 'bal' ? (v === 0 ? 'centre' : (v < 0 ? 'G ' : 'D ') + Math.abs(v)) : (v > 0 && u === 'dB' ? '+' : '') + (Math.round(v * 10) / 10) + (u ? ' ' + u : '');
      });
    }
    KN.forEach(([k, , mn, mx, u]) => {
      const el = $(`.knob[data-k="${k}"]`, p);
      let y0 = 0, v0 = 0, lastTap = 0;
      el.onpointerdown = (e) => { el.setPointerCapture(e.pointerId); y0 = e.clientY; v0 = kget(k); const now = Date.now(); if (now - lastTap < 300) { kset(k, k === 'virt' || k === 'boost' ? 0 : 0); paintKnobs(); drawCurve(); haptic(); } lastTap = now; };
      el.onpointermove = (e) => {
        if (!e.buttons) return;
        const step = u === 'dB' ? .5 : 1;
        let v = v0 + (y0 - e.clientY) / 180 * (mx - mn);
        v = Math.max(mn, Math.min(mx, Math.round(v / step) * step));
        if (v !== kget(k)) { kset(k, v); paintKnobs(); drawCurve(); }
      };
    });
    // curseurs verticaux
    function paintBands() {
      const Lb = lim().band;
      $$('.vsl', p).forEach((s) => {
        const i = +s.dataset.b, v = fx.bands[i];
        const f = 1 - (v + Lb) / (2 * Lb);
        $('.th', s).style.top = (f * 100) + '%';
        const fl = $('.fill', s);
        if (v >= 0) { fl.style.top = (f * 100) + '%'; fl.style.bottom = '50%'; } else { fl.style.top = '50%'; fl.style.bottom = ((1 - f) * 100) + '%'; }
        $('#bv' + i, p).textContent = (v > 0 ? '+' : '') + v;
      });
    }
    $$('.vsl', p).forEach((s) => {
      const i = +s.dataset.b; let lastTap = 0;
      const set = (e) => {
        const r = s.getBoundingClientRect(), Lb = lim().band;
        let v = (1 - (e.clientY - r.top) / r.height) * 2 * Lb - Lb;
        v = Math.max(-Lb, Math.min(Lb, Math.round(v * 2) / 2));
        if (v !== fx.bands[i]) { fx.bands[i] = v; fx.preset = ''; $$('.chip', p).forEach((c) => c.classList.remove('on')); paintBands(); drawCurve(); sendFx(); }
      };
      s.onpointerdown = (e) => {
        s.setPointerCapture(e.pointerId); s.classList.add('on');
        const now = Date.now(); if (now - lastTap < 300) { fx.bands[i] = 0; paintBands(); drawCurve(); sendFx(); haptic(); lastTap = 0; return; } lastTap = now;
        set(e);
      };
      s.onpointermove = (e) => { if (e.buttons) set(e); };
      s.onpointerup = s.onpointercancel = () => s.classList.remove('on');
    });
    function drawCurve() {
      const c = $('#eqc', p); if (!c) return;
      const W = c.clientWidth, H = c.clientHeight, dpr = Math.min(2, devicePixelRatio || 1);
      c.width = W * dpr; c.height = H * dpr;
      const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0);
      const Lb = lim().band * 1.5;
      const g = FHZ.map((f, i) => fx.bands[i] + fx.bass * WB[i] + fx.treble * WT[i]);
      const at = (hz) => { const lf = Math.log(hz); if (hz <= FHZ[0]) return g[0]; if (hz >= FHZ[9]) return g[9]; for (let i = 0; i < 9; i++) if (hz <= FHZ[i + 1]) { const t = (lf - Math.log(FHZ[i])) / (Math.log(FHZ[i + 1]) - Math.log(FHZ[i])); const s = t * t * (3 - 2 * t); return g[i] + (g[i + 1] - g[i]) * s; } return 0; };
      const Y = (db) => H / 2 - db / Lb * (H / 2 - 8);
      x.strokeStyle = 'rgba(255,255,255,.08)'; x.lineWidth = 1;
      for (let d = -Lb; d <= Lb; d += 6) { x.beginPath(); x.moveTo(0, Y(d)); x.lineTo(W, Y(d)); x.stroke(); }
      FHZ.forEach((f) => { const px = (Math.log(f) - Math.log(20)) / (Math.log(20000) - Math.log(20)) * W; x.beginPath(); x.moveTo(px, 0); x.lineTo(px, H); x.stroke(); });
      x.strokeStyle = 'rgba(255,255,255,.3)'; x.beginPath(); x.moveTo(0, Y(0)); x.lineTo(W, Y(0)); x.stroke();
      const st = getComputedStyle(document.body), a1 = st.getPropertyValue('--acc').trim() || '#ff3fa4', a2 = st.getPropertyValue('--acc2').trim() || '#29e6ff';
      const pts = [];
      for (let px = 0; px <= W; px += 2) { const hz = Math.exp(Math.log(20) + px / W * (Math.log(20000) - Math.log(20))); pts.push([px, Y(fx.on ? at(hz) + fx.pre : 0)]); }
      const gr = x.createLinearGradient(0, 0, W, 0); gr.addColorStop(0, a2); gr.addColorStop(1, a1);
      x.beginPath(); x.moveTo(0, Y(0)); pts.forEach(([a, b]) => x.lineTo(a, b)); x.lineTo(W, Y(0)); x.closePath();
      const fg = x.createLinearGradient(0, 0, 0, H); fg.addColorStop(0, 'rgba(255,255,255,.18)'); fg.addColorStop(1, 'rgba(255,255,255,0)');
      x.fillStyle = fg; x.fill();
      x.beginPath(); pts.forEach(([a, b], k) => k ? x.lineTo(a, b) : x.moveTo(a, b));
      x.strokeStyle = gr; x.lineWidth = 3; x.shadowColor = a1; x.shadowBlur = 12; x.stroke(); x.shadowBlur = 0;
      if (fx.unlock && !fx.limiter) { x.fillStyle = '#ff4d5e'; x.font = '700 11px "Share Tech Mono",monospace'; x.fillText('⚠ LIMITEUR COUPÉ', 8, 14); }
    }
    paintBands(); paintKnobs(); requestAnimationFrame(drawCurve);
    $('#pres', p).onclick = (e) => {
      const c = e.target.closest('[data-p]'); if (!c) return;
      const n = c.dataset.p;
      if (n === '+save') return prompt2('Enregistrer le préréglage', 'eq', 'Nom', 'Mon son', (name) => { const u = LS.get('pm_presets', {}); u[name] = fx.bands.slice(); LS.set('pm_presets', u); fx.preset = name; sendFx(); p.remove(); openEq(); });
      const b = PRESETS[n] || LS.get('pm_presets', {})[n]; if (!b) return;
      fx.bands = b.slice(); fx.preset = n; clampFx();
      $$('.chip', p).forEach((x) => x.classList.toggle('on', x === c));
      paintBands(); drawCurve(); sendFx(); haptic();
    };
    $('#pres', p).addEventListener('contextmenu', (e) => {
      const c = e.target.closest('[data-p]'); if (!c) return; e.preventDefault();
      const n = c.dataset.p, u = LS.get('pm_presets', {});
      if (u[n]) confirm2('Supprimer le préréglage ?', `« ${n} »`, 'Supprimer', () => { delete u[n]; LS.set('pm_presets', u); p.remove(); openEq(); });
    });
    $('#eqon', p).onclick = (e) => { fx.on = !fx.on; e.currentTarget.classList.toggle('on', fx.on); drawCurve(); sendFx(); };
    $('#limsw', p).onclick = (e) => { if (!fx.unlock) return; fx.limiter = !fx.limiter; e.currentTarget.classList.toggle('on', fx.limiter); drawCurve(); sendFx(); };
    $('#unl', p).onclick = () => {
      if (fx.unlock) { fx.unlock = false; fx.limiter = true; clampFx(); sendFx(); p.remove(); openEq(); return; }
      win('Débrider le son ?', 'bolt', 'red', `<div class="warnbox" style="margin-bottom:10px">⚠ Mode à tes risques et périls</div><p>Le mode débridé autorise des gains <b>très élevés</b> (jusqu'à +24 dB de boost) et permet de <b>couper le limiteur</b>.</p><p>Le son peut <b>saturer</b>, abîmer les <b>haut-parleurs</b> du téléphone ou d'une enceinte, et <b>blesser tes oreilles</b> au casque. Monte progressivement.</p>`,
        [['Annuler', 'glass', null], ['J\'accepte à mes risques et périls', 'red', () => { fx.unlock = true; sendFx(); p.remove(); openEq(); toast('Son débridé 🔥 — vas-y doucement', 'bolt'); }]]);
    };
  }

  // =================================================================== FILE D'ATTENTE
  let qPanel = null;
  function openQueue() {
    qPanel = panel('File d\'attente', '', 'queue', 'violet', () => { qPanel = null; });
    const hd = $('.phd', qPanel);
    hd.insertAdjacentHTML('beforeend', `<button class="hbtn" data-qa="menu" type="button">${ic('menu', 'chrome', 'none')}</button>`);
    hd.onclick = (e) => {
      const b = e.target.closest('[data-qa]'); if (!b) return;
      menu([{ k: 'pl', g: 'playlist', c: 'green', l: 'Enregistrer en playlist' }, { k: 'clr', g: 'trash', c: 'red', l: 'Vider la file (garder le titre en cours)', danger: 1 }], { title: 'File d\'attente' }, (k) => {
        const q = J(mc('queue'), []) || [];
        if (k === 'pl') newPlaylist(q.map((t) => t.id).filter((x) => x >= 0));
        if (k === 'clr') mc('qclear');
      });
    };
    renderQueue(true);
  }
  function renderQueue(scroll) {
    if (!qPanel) return;
    const q = J(mc('queue'), []) || [];
    const bd = $('.pbd', qPanel);
    $('.phd small', qPanel).textContent = `${plural(q.length, 'titre', 'titres')} · ${fmtLong(q.reduce((a, t) => a + (t.dur || 0), 0))}`;
    bd.innerHTML = q.map((t, i) => `<div class="trow qrow${i === S.idx ? ' now' : ''}" data-qi="${i}"><span class="grip">${ic('menu', 'chrome', 'none')}</span>${artHtml(byId.get(t.id) || t, 128)}<span class="ttx"><b>${i === S.idx ? '<span class="eqm"><i></i><i></i><i></i></span>' : ''}${esc(t.title)}</b><small>${esc(t.artist || '')}</small></span><span class="du">${fmt(t.dur)}</span><button class="more" data-qx="${i}" type="button">${XSVG}</button></div>`).join('') ||
      `<div class="empty2">${ic('queue', 'violet')}<b>File vide</b></div>`;
    if (scroll) { const n = $('.now', bd); if (n) n.scrollIntoView({ block: 'center' }); }
    bd.onclick = (e) => {
      const x = e.target.closest('[data-qx]'); if (x) { e.stopPropagation(); haptic(); mc('qremove', +x.dataset.qx); return; }
      const r = e.target.closest('[data-qi]'); if (r && !e.target.closest('.grip')) { haptic(); mc('jump', +r.dataset.qi); }
    };
    // glisser-déposer avec la poignée
    $$('.grip', bd).forEach((g) => {
      g.onpointerdown = (e) => {
        e.preventDefault();
        const row = g.closest('.qrow'), from = +row.dataset.qi, h = row.offsetHeight + 3, y0 = e.clientY;
        g.setPointerCapture(e.pointerId); row.classList.add('dragging'); haptic();
        let to = from;
        g.onpointermove = (ev) => {
          const dy = ev.clientY - y0; row.style.transform = `translateY(${dy}px) scale(1.03)`;
          to = Math.max(0, Math.min(q.length - 1, from + Math.round(dy / h)));
          const r = bd.getBoundingClientRect();
          if (ev.clientY < r.top + 50) bd.scrollTop -= 8; if (ev.clientY > r.bottom - 50) bd.scrollTop += 8;
        };
        g.onpointerup = g.onpointercancel = () => { g.onpointermove = null; row.classList.remove('dragging'); row.style.transform = ''; if (to !== from) { mc('qmove', from, to); haptic(); } };
      };
    });
  }

  // =================================================================== PAROLES
  let lyrPanel = null, lyrLines = null, lyrCur = -1;
  function openLyrics() { lyrPanel = panel('Paroles', S.t ? S.t.title : '', 'lyrics', 'cyan', () => { lyrPanel = null; }); loadLyrics(); }
  function loadLyrics() {
    if (!lyrPanel) return;
    const t = S.t; $('.phd small', lyrPanel).textContent = t ? t.title + ' · ' + (t.artist || '') : '';
    const txt = t ? String(mc('lyrics', t.path || '') || '') : '';
    const bd = $('.pbd', lyrPanel);
    lyrLines = null; lyrCur = -1;
    if (!txt.trim()) { bd.innerHTML = `<div class="empty2">${ic('lyrics', 'cyan')}<b>Pas de paroles trouvées</b><span>Place un fichier <b>.lrc</b> (paroles synchronisées) ou <b>.txt</b> portant le même nom que le morceau, dans le même dossier.</span></div>`; return; }
    const lines = [];
    txt.split(/\r?\n/).forEach((l) => {
      const tags = [...l.matchAll(/\[(\d+):(\d+)(?:[.:](\d+))?\]/g)];
      const text = l.replace(/\[[^\]]*\]/g, '').trim();
      tags.forEach((m) => lines.push([(+m[1]) * 60000 + (+m[2]) * 1000 + (m[3] ? +(m[3] + '00').slice(0, 3) : 0), text]));
    });
    if (lines.length > 2) {
      lines.sort((a, b) => a[0] - b[0]); lyrLines = lines;
      bd.innerHTML = `<div class="lyr">${lines.map(([ms, l], i) => `<p data-li="${i}" data-ms="${ms}">${esc(l) || '♪'}</p>`).join('')}</div>`;
      bd.onclick = (e) => { const p = e.target.closest('[data-ms]'); if (p) mc('seek', +p.dataset.ms); };
      lyrTick();
    } else bd.innerHTML = `<div class="lyr plain">${txt.replace(/\[[^\]]*\]/g, '').split(/\r?\n/).map((l) => `<p>${esc(l) || '&nbsp;'}</p>`).join('')}</div>`;
  }
  function lyrTick() {
    if (!lyrLines || !lyrPanel) return;
    let k = -1; for (let i = 0; i < lyrLines.length; i++) { if (lyrLines[i][0] <= posMs + 150) k = i; else break; }
    if (k === lyrCur) return;
    lyrCur = k;
    $$('.lyr p', lyrPanel).forEach((p, i) => p.classList.toggle('cur', i === k));
    const c = $(`.lyr p[data-li="${k}"]`, lyrPanel); if (c) c.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  // =================================================================== MINUTERIE, VITESSE, RÉGLAGES
  function sleepMenu() {
    const left = S.sleep;
    const items = [15, 30, 45, 60, 90, 120].map((m) => ({ k: m, g: 'moon', c: 'blue', l: m < 60 ? m + ' minutes' : m / 60 + ' heure' + (m > 60 ? 's' : '') }));
    items.push({ k: -1, g: 'music', c: 'violet', l: 'À la fin du titre' });
    if (left) items.push({ k: 0, g: 'trash', c: 'red', l: 'Annuler la minuterie', danger: 1 });
    menu(items, { title: 'Minuterie de sommeil', sub: left === -1 ? 'Arrêt à la fin du titre' : left > 0 ? 'Arrêt dans ' + Math.ceil(left / 60000) + ' min' : 'Fondu puis arrêt de la musique' }, (k) => {
      mc('sleep', +k); toast(+k === 0 ? 'Minuterie annulée' : +k < 0 ? 'Arrêt à la fin du titre 🌙' : `Dodo dans ${k} min 🌙`, 'moon');
    });
  }

  function openSpeed() {
    const sp = S.speed || 1, pt = S.pitch || 1;
    const w = win('Vitesse et tonalité', 'timer', 'amber', `
      <div class="lbl">Vitesse</div><div class="rng"><input type="range" id="spd" min="0.5" max="2" step="0.05" value="${sp}"><b id="spdv"></b></div>
      <div class="lbl" style="margin-top:12px">Tonalité (pitch)</div><div class="rng"><input type="range" id="pit" min="0.5" max="2" step="0.01" value="${pt}"><b id="pitv"></b></div>
      <div class="row"><span>Garder la tonalité liée<small>Effet « vinyle » : vitesse et tonalité bougent ensemble</small></span><button class="sw" id="lnk" type="button"><i></i></button></div>`,
      [['Réinitialiser', 'glass', () => { mc('opts', JSON.stringify({ speed: 1, pitch: 1 })); S.speed = 1; S.pitch = 1; updateControls(); }], ['OK', 'c', null]]);
    const s1 = $('#spd', w), s2 = $('#pit', w), lk = $('#lnk', w);
    const paint = () => { $('#spdv', w).textContent = '×' + (+s1.value).toFixed(2); const st = 12 * Math.log2(+s2.value); $('#pitv', w).textContent = (st > 0 ? '+' : '') + st.toFixed(1) + ' dt'; [s1, s2].forEach((r) => r.style.setProperty('--v', ((r.value - r.min) / (r.max - r.min) * 100) + '%')); };
    let t = null;
    const send = () => { clearTimeout(t); t = setTimeout(() => { S.speed = +s1.value; S.pitch = +s2.value; mc('opts', JSON.stringify({ speed: S.speed, pitch: S.pitch })); updateControls(); }, 80); };
    s1.oninput = () => { if (lk.classList.contains('on')) s2.value = s1.value; paint(); send(); };
    s2.oninput = () => { paint(); send(); };
    lk.onclick = () => { lk.classList.toggle('on'); if (lk.classList.contains('on')) { s2.value = s1.value; paint(); send(); } };
    paint();
  }

  function openSettings() {
    const p = panel('Réglages', 'PupMusic', 'gear', 'violet');
    const min = LS.get('pm_min', 30);
    $('.pbd', p).innerHTML = `
      <div class="cp"><div class="cp-h">${ic('loop', 'cyan')}<div><b>Enchaînement</b><small>Comme Poweramp : sans blanc ou en fondu</small></div></div>
        <div class="row"><span>Sans blanc (gapless)<small>Les albums live s'enchaînent sans coupure</small></span><button class="sw${S.gapless !== false ? ' on' : ''}" data-o="gapless" type="button"><i></i></button></div>
        <div class="lbl">Fondu enchaîné</div><div class="rng"><input type="range" id="cf" min="0" max="12" step="1" value="${Math.round((S.cf || 0) / 1000)}"><b id="cfv"></b></div></div>
      <div class="cp"><div class="cp-h">${ic('speaker', 'pink')}<div><b>Lecture</b><small>Casque, appels et notifications</small></div></div>
        <div class="row"><span>Pause si le casque est débranché</span><button class="sw${S.noisyPause !== false ? ' on' : ''}" data-o="noisyPause" type="button"><i></i></button></div>
        <div class="row"><span>Reprendre après un appel ou une interruption</span><button class="sw${S.resumeFocus !== false ? ' on' : ''}" data-o="resumeFocus" type="button"><i></i></button></div>
        <div class="row"><span>Ouvrir le lecteur quand je lance un titre</span><button class="sw${LS.get('pm_autoopen', true) ? ' on' : ''}" data-l="pm_autoopen" type="button"><i></i></button></div></div>
      <div class="cp"><div class="cp-h">${ic('sparkle', 'amber')}<div><b>Apparence</b><small>Néon et effets</small></div></div>
        <div class="row"><span>Couleur néon tirée de la pochette</span><button class="sw${LS.get('pm_dyn', true) ? ' on' : ''}" data-l="pm_dyn" type="button"><i></i></button></div>
        <div class="row"><span>Spectre néon<small>Demande l'autorisation micro (rien n'est enregistré)</small></span><button class="sw${visWanted ? ' on' : ''}" data-l="pm_vis" type="button"><i></i></button></div></div>
      <div class="cp"><div class="cp-h">${ic('folder', 'orange')}<div><b>Bibliothèque</b><small>${plural(LIB.length, 'titre', 'titres')}</small></div></div>
        <div class="lbl">Ignorer les fichiers plus courts que</div>
        <div class="seg" id="minseg">${[0, 10, 30, 60].map((s) => `<button data-min="${s}" class="${s === min ? 'on' : ''}" type="button">${s ? s + ' s' : 'Aucun'}</button>`).join('')}</div>
        <button class="ab wide c2" id="rescan" type="button" style="margin-top:10px">${ic('refresh', 'chrome', 'none')}Relancer l'analyse</button></div>
      <div class="cp"><div class="cp-h">${ic('info', 'cyan')}<div><b>Moteur audio</b><small>${esc(S.fx || '—')}</small></div></div><p style="margin:0;color:var(--muted);font-size:13px">Lecture via le décodeur Android (MP3, AAC, FLAC, OGG, OPUS, WAV, M4A…). Les effets s'appliquent à la sortie de PupMusic uniquement.</p></div>`;
    const cf = $('#cf', p);
    const paint = () => { $('#cfv', p).textContent = +cf.value ? cf.value + ' s' : 'off'; cf.style.setProperty('--v', (cf.value / 12 * 100) + '%'); };
    cf.oninput = paint; cf.onchange = () => { S.cf = cf.value * 1000; mc('opts', JSON.stringify({ cf: S.cf })); };
    paint();
    $('.pbd', p).addEventListener('click', (e) => {
      const s = e.target.closest('.sw');
      if (s) {
        const on = !s.classList.contains('on'); s.classList.toggle('on', on);
        if (s.dataset.o) { S[s.dataset.o] = on; mc('opts', JSON.stringify({ [s.dataset.o]: on })); }
        if (s.dataset.l) {
          LS.set(s.dataset.l, on);
          if (s.dataset.l === 'pm_dyn') tint($('#artimg').classList.contains('ok') ? $('#artimg') : null, S.t && S.t.album);
          if (s.dataset.l === 'pm_vis') { visWanted = on; if (on && !mc('hasMic')) mc('askMic'); }
        }
        return;
      }
      const m = e.target.closest('[data-min]');
      if (m) { LS.set('pm_min', +m.dataset.min); $$('#minseg button', p).forEach((b) => b.classList.toggle('on', b === m)); }
      if (e.target.closest('#rescan')) { scan(); toast('Analyse en cours…', 'refresh'); }
    });
  }

  // =================================================================== UI génériques
  function panel(title, sub, g, c, onClose) {
    const p = document.createElement('div'); p.className = 'panel';
    p.innerHTML = `<div class="bg" aria-hidden="true"><i class="beam b1"></i><i class="beam b2"></i><div class="pawfloor"></div></div><div class="phd"><button class="hbtn" data-close type="button">${ic('left', 'chrome', 'none')}</button><span style="width:40px;height:40px;flex:none">${ic(g, c)}</span><div class="tx"><b>${esc(title)}</b><small>${esc(sub || '')}</small></div></div><div class="pbd"></div>`;
    p.close = () => { p.remove(); if (onClose) onClose(); };
    $('[data-close]', p).onclick = p.close;
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
    const w = win(title, g, 'violet', `<div class="lbl">${esc(label)}</div><input class="vin" id="pin" value="${esc(val || '')}" maxlength="60">`, [['Annuler', 'glass', null], ['OK', 'c', () => { const v = $('#pin', w).value.trim(); if (v) cb(v); }]]);
    const i = $('#pin', w); setTimeout(() => { i.focus(); i.select(); }, 60);
    i.onkeydown = (e) => { if (e.key === 'Enter') { const v = i.value.trim(); w.close(); if (v) cb(v); } };
  }
  function confirm2(title, msg, ok, cb) { win(title, 'info', 'red', `<p>${esc(msg)}</p>`, [['Annuler', 'glass', null], [ok, 'red', cb]]); }

  function menu(items, hd, cb) {
    const veil = document.createElement('div'); veil.className = 'veil';
    const m = document.createElement('div'); m.className = 'menu';
    m.style.left = '10px'; m.style.right = '10px'; m.style.bottom = 'calc(var(--sb) + 14px)';
    const head = hd ? `<div class="mh">${hd.art ? artHtml(hd.art, 128) : ''}<div style="min-width:0"><b style="display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(hd.title)}</b>${hd.sub ? `<small>${esc(hd.sub)}</small>` : ''}</div></div>` : '';
    m.innerHTML = head + items.map((it) => `<button data-mk="${esc(it.k)}" class="${it.danger ? 'danger' : ''}" type="button">${ic(it.g, it.c)}<span style="flex:1">${it.l}</span></button>`).join('');
    const close = () => { veil.remove(); m.remove(); };
    veil.onclick = close;
    m.onclick = (e) => { const b = e.target.closest('[data-mk]'); if (!b) return; close(); haptic(); cb(b.dataset.mk); };
    $('#layer').append(veil, m);
  }

  function toast(msg, g) {
    const t = document.createElement('div'); t.className = 'toast';
    t.innerHTML = `${ic(g || 'music', 'pink')}<span>${esc(msg)}</span>`;
    $('#toasts').appendChild(t);
    setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 320); }, 2200);
  }

  // =================================================================== PONT NATIF
  window.MusicUI = {
    on(ev, data) {
      if (ev === 'state') onState(data);
      else if (ev === 'lib') {
        scanning = false;
        const arr = J(data, []) || [];
        ingest(arr);
        try { localStorage.setItem('pm_lib', data); } catch (e) { }
        render();
        markNow();
        if (S.t) { trackChanged(); updateMini(); }
      } else if (ev === 'perm') { if (data === '1') { libReady = false; render(); scan(); } else render(); }
      else if (ev === 'mic') { if (data === '1' && visWanted) mc('vis', true); else if (data !== '1') { visWanted = false; LS.set('pm_vis', false); toast('Spectre néon désactivé (autorisation refusée)', 'pulse'); } }
      else if (ev === 'open') { if (data === 'player') setTimeout(openPlayer, 300); }
      else if (ev === 'resume') { if (playerOpen) startVis(); fx = Object.assign({}, FXDEF, J(mc('fxGet'), {}) || {}); }
    },
    back() {
      const lay = $('#layer');
      if (lay.lastElementChild) {
        const last = lay.lastElementChild;
        if (last.classList.contains('panel')) { last.close(); return true; }
        if (last.classList.contains('win') || last.classList.contains('menu')) { const v = last.previousElementSibling; last.remove(); if (v && v.classList.contains('veil')) v.remove(); return true; }
        last.remove(); return true;
      }
      if (playerOpen) { closePlayer(); return true; }
      if ($('#q').value) { $('#q').value = ''; $('#qx').hidden = true; render(true); return true; }
      return back();
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };

  // =================================================================== DÉMARRAGE
  $('#mlogo').innerHTML = ic('music', 'pink');
  $('#lback').innerHTML = ic('left', 'chrome', 'none');
  $('#lmenu').innerHTML = ic('menu', 'chrome', 'none');
  $('#pdown').innerHTML = ic('left', 'chrome', 'none').replace('class="pi"', 'class="pi" style="transform:rotate(-90deg)"');
  $('#pmenu').innerHTML = ic('menu', 'chrome', 'none');
  $('#sicon').innerHTML = ic('search', 'cyan', 'none');
  $('#qx').innerHTML = XSVG;
  $('#bprev').innerHTML = ic('prev', 'chrome', 'none');
  $('#bnext').innerHTML = ic('next', 'chrome', 'none');
  $('#bshuf').innerHTML = ic('shuffle', 'chrome', 'none');
  const cached = (() => { try { return localStorage.getItem('pm_lib'); } catch (e) { return null; } })();
  if (cached) { ingest(J(cached, [])); }
  render();
  onState(mc('state'));
  updateControls();
  scan();
  if (new URLSearchParams(location.search).get('open') === 'player') setTimeout(openPlayer, 100);

  // =================================================================== aperçu navigateur (hors APK)
  function mockM() {
    const words = ['Neon', 'Puppy', 'Midnight', 'Laser', 'Bark', 'Velvet', 'Chrome', 'Heat', 'Pulse', 'Leash', 'Glow', 'Howl', 'Disco', 'Wild', 'Night'];
    const arts = ['Pup Riot', 'Neon Pack', 'DJ Woof', 'Kennel Club', 'Leather & Lace', 'Laser Hound'];
    const lib = [];
    let id = 1;
    arts.forEach((a, ai) => { for (let al = 0; al < 2; al++) { const alb = words[(ai * 3 + al) % words.length] + ' ' + words[(ai + al * 5 + 4) % words.length]; for (let k = 1; k <= 6; k++) lib.push([id++, words[(id * 7) % words.length] + ' ' + words[(id * 3 + 1) % words.length], a, alb, ai * 10 + al, 150000 + (id * 7919) % 160000, '/storage/emulated/0/Music/' + a, k, 2018 + (ai + al) % 7, 1700000000 + id * 1000, ['Électro', 'Pop', 'Techno', 'Rock'][(ai + al) % 4], '', 'audio/flac', 900000, 30000000]); } });
    let q = lib.slice(0, 12).map((a) => a[0]), idx = 0, pos = 42000, pl = new URLSearchParams(location.search).get('play') === '1', shuf = false, rep = 0;
    const tr = (i) => { const a = lib.find((x) => x[0] === i); return { id: a[0], title: a[1], artist: a[2], album: a[3], albumId: a[4], dur: a[5], uri: '', path: '' }; };
    setInterval(() => { if (pl) pos += 250; }, 250);
    const st = () => JSON.stringify({ idx, qv: 1, qn: q.length, playing: pl, pos, dur: tr(q[idx]).dur, shuffle: shuf, repeat: rep, cf: 0, gapless: true, speed: 1, pitch: 1, bal: 0, sleep: 0, fx: 'DynamicsProcessing · 10 bandes', info: { codec: 'FLAC', rate: 96000, bits: 24, ch: 2, kbps: 2304 }, t: tr(q[idx]) });
    const emit = () => setTimeout(() => window.MusicUI && MusicUI.on('state', st()), 10);
    return {
      perm: () => true, scan: () => setTimeout(() => MusicUI.on('lib', JSON.stringify(lib)), 50), state: st, pos: () => pos + ',' + tr(q[idx]).dur + ',' + (pl ? 1 : 0),
      queue: () => JSON.stringify(q.map(tr)), toggle() { pl = !pl; emit(); }, next() { idx = (idx + 1) % q.length; pos = 0; emit(); }, prev() { idx = (idx - 1 + q.length) % q.length; pos = 0; emit(); },
      play(ids, i) { q = JSON.parse(ids); idx = Math.max(0, i); pos = 0; pl = true; emit(); }, jump(i) { idx = i; pos = 0; emit(); }, seek(ms) { pos = ms; }, shuffle(v) { shuf = v; emit(); }, repeat(r) { rep = r; emit(); },
      fxGet: () => '{}', stats: () => JSON.stringify({ 3: [9, Date.now()], 5: [4, Date.now() - 5e5] }), lyrics: () => '[00:30.00]Ligne une du refrain\n[00:40.00]Deuxième ligne néon\n[00:46.00]Woof woof sous les lasers\n[00:55.00]Fin du couplet',
      hasMic: () => true, vis() {}, fft: () => '',
    };
  }
})();
