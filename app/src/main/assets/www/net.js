/* PuppyInternet — interface (barre, accueil, onglets, menu, favoris, historique, réglages). */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const I = PupIcons.icon, PAL = PupIcons.PAL;
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const norm = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const J = (s, d) => { try { return s == null ? d : JSON.parse(s); } catch (e) { return d; } };
  const MOCK = !window.Net;
  const N = window.Net || mockNet();
  const call = (fn, ...a) => { try { return N[fn] ? N[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const XSVG = '<svg viewBox="0 0 20 20"><path d="M4 4L16 16M16 4L4 16" stroke="#fff" stroke-width="3.4" stroke-linecap="round"/></svg>';
  const ENGINES = { ddg: 'DuckDuckGo', google: 'Google', qwant: 'Qwant', ecosia: 'Ecosia', brave: 'Brave', startpage: 'Startpage' };
  const ENGINE_URL = { ddg: 'https://duckduckgo.com/?q=%s', google: 'https://www.google.com/search?q=%s', qwant: 'https://www.qwant.com/?q=%s', ecosia: 'https://www.ecosia.org/search?q=%s', brave: 'https://search.brave.com/search?q=%s', startpage: 'https://www.startpage.com/do/search?query=%s' };
  const COLS = ['pink', 'cyan', 'violet', 'amber', 'green', 'red', 'blue', 'orange'];

  let st = J(call('state'), {}) || {};
  let mode = null, autoStart = false;
  let favs = J(call('get', 'favs'), null);
  if (!favs) {
    favs = [
      { url: 'https://www.youtube.com', title: 'YouTube' },
      { url: 'https://fr.wikipedia.org', title: 'Wikipédia' },
      { url: 'https://www.google.com/maps', title: 'Maps' },
      { url: 'https://www.reddit.com', title: 'Reddit' },
    ];
    call('set', 'favs', JSON.stringify(favs));
  }
  const saveFavs = () => call('set', 'favs', JSON.stringify(favs));
  const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return u || ''; } };
  const colorOf = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return COLS[h % COLS.length]; };
  const isFav = (u) => favs.some((f) => f.url === u);
  const act = () => st.active || { blank: true };
  const haptic = () => call('haptic');

  // ------------------------------------------------------------------ barre
  function renderBar() {
    const a = act();
    $('#htxt').textContent = a.blank ? 'Chercher ou taper une adresse' : (hostOf(a.url) || a.url);
    $('#htxt').style.opacity = a.blank ? '.7' : '1';
    $('#lk').innerHTML = a.blank ? I('search', 'cyan', { shape: 'none' }) : a.secure ? I('lock', 'green', { shape: 'none' }) : I('lock', 'red', { shape: 'none' });
    const sh = $('#shield');
    const prev = +$('#bc').textContent;
    $('#bc').textContent = st.adblock === false ? 'OFF' : (a.blocked || 0);
    sh.classList.toggle('off', st.adblock === false);
    if ((a.blocked || 0) > prev) { sh.classList.remove('pop'); void sh.offsetWidth; sh.classList.add('pop'); }
    $('#rel').innerHTML = a.loading ? I('plus', 'red', { shape: 'none' }).replace('class="pi"', 'class="pi" style="transform:rotate(45deg)"') : I('refresh', 'cyan', { shape: 'none' });
    $('#rel').style.visibility = a.blank ? 'hidden' : 'visible';
    $('#tc').textContent = (st.tabs || []).length || 1;
    $('#prog').classList.toggle('on', !!a.loading);
    $('#progi').style.width = (a.loading ? Math.max(8, a.progress || 0) : 100) + '%';
  }

  // ------------------------------------------------------------------ panneaux
  const ov = $('#ov'), ovin = $('#ovin');
  function setMode(m) {
    if (m === 'tabs') call('capture');
    mode = m;
    if (m) { call('expand', true); ov.hidden = false; render(); ovin.scrollTop = 0; }
    else { ov.hidden = true; ovin.innerHTML = ''; call('expand', false); }
  }
  function render() {
    ovin.className = '';
    if (mode === 'start') renderStart();
    else if (mode === 'type') renderType();
    else if (mode === 'tabs') renderTabs();
    else if (mode === 'menu') renderMenu();
    else if (mode === 'favs') renderFavs();
    else if (mode === 'history') renderHistory();
    else if (mode === 'settings') renderSettings();
    else if (mode === 'guard') renderGuard();
    else if (mode === 'dict') renderDict();
  }
  function favTile(f, i) {
    return `<button class="fav" data-fav="${i}" type="button"><span class="orbt" style="--c:${PAL[colorOf(hostOf(f.url))][1]}">${esc((f.title || hostOf(f.url) || '?').trim()[0].toUpperCase())}</span><span class="lb">${esc(f.title || hostOf(f.url))}</span></button>`;
  }

  function renderStart() {
    const a = act();
    ovin.innerHTML = `
      <div class="logo-net neon">PUPPY<span>INTERNET</span></div>
      <div class="tagline">zéro pub · rien ne s'ouvre de force · 🐾</div>
      <button class="vsearch bigsearch" data-go="type" type="button"><span class="pw">${I('search', 'cyan', { shape: 'none' })}</span><span class="ph">Chercher avec ${esc(ENGINES[st.engine] || 'DuckDuckGo')} ou taper une adresse…</span><span class="go">${I('paw', 'chrome', { shape: 'none' })}</span></button>
      <div class="secth">${I('star', 'amber', { shape: 'orb' })}<span class="chrome">FAVORIS</span></div>
      <div class="favs">${favs.map(favTile).join('')}${!a.blank && !isFav(a.url) ? `<button class="fav add" data-act="addfav" type="button"><span class="orbt">+</span><span class="lb">Ajouter cette page</span></button>` : ''}</div>
      <div class="statcard">${I('shield', 'green')}<div><b>${(st.blockedTotal || 0).toLocaleString('fr-FR')}</b><small>pubs, traqueurs et pop-up bloqués au total · liste de ${(st.hosts || 0).toLocaleString('fr-FR')} domaines</small></div></div>
      <div class="quick">
        <button class="ab small glass" data-go="history" type="button">Historique</button>
        <button class="ab small c2" data-go="tabs" type="button">Onglets</button>
        <button class="ab small violet" data-go="settings" type="button">Réglages</button>
      </div>`;
  }

  function renderType() {
    const a = act();
    ovin.innerHTML = `
      <label class="vsearch bigsearch"><span class="pw">${I('search', 'cyan', { shape: 'none' })}</span><input id="qi" type="url" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" enterkeyhint="go" placeholder="Chercher ou taper une adresse…" value="${esc(a.blank ? '' : a.url)}"></label>
      <div class="sugg" id="sugg"></div>
      <div class="secth">${I('star', 'amber', { shape: 'orb' })}<span class="chrome">FAVORIS</span></div>
      <div class="favs">${favs.map(favTile).join('')}</div>`;
    const qi = $('#qi');
    const hist = J(call('history'), []) || [];
    const draw = () => {
      const q = qi.value.trim(), n = norm(q);
      let html = '';
      if (q) {
        const looksUrl = /^[a-z][a-z0-9+.-]*:\/\//i.test(q) || (!/\s/.test(q) && /^[a-z0-9-]+(\.[a-z0-9-]+)+/i.test(q));
        if (looksUrl) html += `<button class="srow" data-q="${esc(q)}" type="button">${I('globe', 'blue', { shape: 'orb' })}<span class="tx"><b>Aller sur ${esc(q)}</b><small>ouvrir l'adresse</small></span></button>`;
        html += `<button class="srow" data-q="${esc(q)}" data-search="1" type="button">${I('search', 'pink', { shape: 'orb' })}<span class="tx"><b>${esc(q)}</b><small>rechercher avec ${esc(ENGINES[st.engine] || 'DuckDuckGo')}</small></span></button>`;
        const seen = new Set();
        [...favs, ...hist].filter((h) => norm(h.title).includes(n) || norm(h.url).includes(n)).forEach((h) => {
          if (seen.has(h.url) || seen.size >= 8) return;
          seen.add(h.url);
          html += `<button class="srow" data-u="${esc(h.url)}" type="button">${I(isFav(h.url) ? 'star' : 'clock', isFav(h.url) ? 'amber' : 'cyan', { shape: 'orb' })}<span class="tx"><b>${esc(h.title || hostOf(h.url))}</b><small>${esc(h.url)}</small></span></button>`;
        });
      }
      $('#sugg').innerHTML = html;
    };
    qi.addEventListener('input', draw);
    qi.addEventListener('keydown', (e) => { if (e.key === 'Enter' && qi.value.trim()) { e.preventDefault(); go(qi.value); } });
    draw();
    setTimeout(() => { qi.focus(); qi.select(); }, 60);
  }

  function renderTabs() {
    const tabs = st.tabs || [];
    ovin.innerHTML = `
      <div class="secth">${I('tabs', 'cyan', { shape: 'orb' })}<span class="chrome c2">ONGLETS · ${tabs.length}</span></div>
      <div class="tgrid">${tabs.map((t, i) => `
        <div class="tcard ${t.active ? 'on' : ''}" data-tab="${t.id}" style="animation-delay:${i * 40}ms">
          <button class="xb" data-close="${t.id}" type="button" aria-label="Fermer">${XSVG}</button>
          <div class="th" ${t.url && t.thumb ? `style="background-image:url('https://pupnet.local/thumb?id=${t.id}&t=${t.thumb}')"` : ''}>${!t.url || !t.thumb ? I(t.url ? 'globe' : 'pupnet', t.url ? 'blue' : 'pink', { shape: 'orb' }) : ''}</div>
          <div class="tt">${t.icon && !MOCK ? `<img src="https://pupnet.local/fav?id=${t.id}" alt="">` : I('paw', 'pink', { shape: 'none' })}<span>${esc(t.url ? (t.title || hostOf(t.url)) : 'Accueil Puppy')}</span></div>
        </div>`).join('')}
      </div>
      <div class="actions">
        <button class="ab red" data-act="closeall" type="button">${I('trash', 'chrome', { shape: 'none' })} Tout fermer</button>
        <button class="ab green" data-act="newtab" type="button">${I('plus', 'chrome', { shape: 'none' })} Nouvel onglet</button>
      </div>`;
  }

  function renderMenu() {
    const a = act();
    ovin.className = 'sheet';
    const item = (k, g, c, label, o) => `<button class="mi ${o && o.on ? 'on' : ''}" data-m="${k}" type="button" ${o && o.dis ? 'disabled' : ''}>${I(g, c)}<span>${label}</span></button>`;
    ovin.innerHTML = `<div class="msheet">
      <div class="mhead"><span class="fi">${a.icon && !MOCK ? `<img src="https://pupnet.local/fav?id=${a.id}" alt="">` : I('pupnet', 'pink', { shape: 'none' })}</span>
        <span class="tx"><b>${esc(a.blank ? 'Accueil Puppy' : (a.title || hostOf(a.url)))}</b><small>${esc(a.blank ? 'nouvel onglet' : a.url)}</small></span>
        <span class="shield ${st.adblock === false ? 'off' : ''}" style="pointer-events:none"><span class="si">${I('shield', 'green', { shape: 'none' })}</span><b>${st.adblock === false ? 'OFF' : (a.blocked || 0)}</b></span></div>
      <div class="mgrid">
        ${item('back', 'left', 'blue', 'Retour', { dis: !a.canBack })}
        ${item('fwd', 'right', 'blue', 'Avancer', { dis: !a.canFwd })}
        ${item('reload', 'refresh', 'cyan', 'Actualiser', { dis: a.blank })}
        ${item('fav', 'star', 'amber', isFav(a.url) ? 'Retirer favori' : 'Favori', { dis: a.blank, on: isFav(a.url) })}
        ${item('share', 'share', 'green', 'Partager', { dis: a.blank })}
        ${item('pin', 'home', 'pink', 'Sur l\'accueil', { dis: a.blank })}
        ${item('desktop', 'desktop', 'violet', st.desktop ? 'Version mobile' : 'Version PC', { on: st.desktop })}
        ${item('external', 'external', 'orange', 'Autre appli', { dis: a.blank })}
        ${item('favs', 'star', 'gold', 'Mes favoris')}
        ${item('history', 'clock', 'cyan', 'Historique')}
        ${item('downloads', 'download', 'green', 'Téléchar&shy;gements')}
        ${item('settings', 'gear', 'violet', 'Réglages')}
        ${item('newtab', 'plus', 'green', 'Nouvel onglet')}
        ${item('closetab', 'trash', 'red', 'Fermer l\'onglet')}
        ${item('adblock', 'shield', st.adblock === false ? 'chrome' : 'green', st.adblock === false ? 'Bloqueur OFF' : 'Bloqueur ON', { on: st.adblock !== false })}
        ${item('skin', 'sparkle', st.skinHere ? 'pink' : 'chrome', st.skinHere ? 'Thème puppy ici' : 'Thème d\'origine ici', { on: !!st.skinHere, dis: a.blank })}
        ${item('decoedit', 'paw', 'amber', 'Placer les chiots', { dis: a.blank || !st.skinHere })}
        ${item('guard', 'shield', st.guardHere === 'rouge' ? 'red' : st.guardHere === 'verte' ? 'green' : 'cyan', 'Chien de garde', { on: !!st.guardHere, dis: a.blank })}
        ${item('phone', 'paw', 'pink', 'PuppyPhone')}
      </div></div>`;
  }

  function renderFavs() {
    ovin.innerHTML = `<div class="secth">${I('star', 'amber', { shape: 'orb' })}<span class="chrome">MES FAVORIS · ${favs.length}</span></div>
      <div class="sugg">${favs.map((f, i) => `<div class="srow" data-fav="${i}"><span class="fav" style="pointer-events:none"><span class="orbt" style="--c:${PAL[colorOf(hostOf(f.url))][1]};width:40px;height:40px;font-size:17px">${esc((f.title || hostOf(f.url) || '?')[0].toUpperCase())}</span></span><span class="tx"><b>${esc(f.title || hostOf(f.url))}</b><small>${esc(f.url)}</small></span><button class="del" data-delfav="${i}" type="button">✕</button></div>`).join('') ||
      `<div class="empty">${I('star', 'amber')}Aucun favori. Menu → ★ Favori pour en ajouter.</div>`}</div>`;
  }

  function renderHistory() {
    const hist = J(call('history'), []) || [];
    let html = `<div class="secth">${I('clock', 'cyan', { shape: 'orb' })}<span class="chrome c2">HISTORIQUE</span></div>`;
    if (!hist.length) html += `<div class="empty">${I('clock', 'cyan')}Rien pour l'instant.</div>`;
    let day = '';
    hist.slice(0, 200).forEach((h) => {
      const d = new Date(h.t).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
      if (d !== day) { html += `<div class="day">${esc(d)}</div>`; day = d; }
      html += `<button class="srow" data-u="${esc(h.url)}" type="button">${I('globe', colorOf(hostOf(h.url)), { shape: 'orb' })}<span class="tx"><b>${esc(h.title || hostOf(h.url))}</b><small>${new Date(h.t).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })} · ${esc(hostOf(h.url))}</small></span></button>`;
    });
    if (hist.length) html += `<button class="ab red wide" data-clear="history" type="button" style="margin-top:6px">${I('trash', 'chrome', { shape: 'none' })} Effacer l'historique</button>`;
    ovin.innerHTML = html;
  }

  function renderSettings() {
    const sw = (k, on) => `<button class="sw ${on ? 'on' : ''}" data-set="${k}" data-v="${!on}" type="button"><i></i></button>`;
    ovin.innerHTML = `
      <div class="secth">${I('gear', 'violet', { shape: 'orb' })}<span class="chrome">RÉGLAGES</span></div>
      <div class="cp">
        <div class="cp-h">${I('pupnet', 'pink')}<div><b class="chrome">Navigateur par défaut</b><small>Pour que tous les liens s'ouvrent dans PuppyInternet à la place de Chrome.</small></div></div>
        ${st.isDefault ? `<div class="ok">PuppyInternet est ton navigateur ✓</div>` : `<button class="ab wide" data-act="default" type="button">Devenir mon navigateur</button>`}
      </div>
      <div class="cp">
        <div class="cp-h">${I('shield', 'green')}<div><b>Bloqueur de pubs & traqueurs</b><small>${(st.hosts || 0).toLocaleString('fr-FR')} domaines bloqués + pubs masquées + pop-up interdites. Total : ${(st.blockedTotal || 0).toLocaleString('fr-FR')} bloqués.</small></div></div>
        <div class="row"><span>Bloqueur</span>${sw('adblock', st.adblock !== false)}</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('sparkle', 'pink')}<div><b>Thème puppyplay sur les sites</b><small>Habille tous les sites aux couleurs du PuppyPhone, comme Dark Reader : mode sombre, liens néon, liseré rose et cyan en haut, pattes en filigrane, boutons arrondis. Purement visuel, rien n'est modifié côté site.</small></div></div>
        <div class="row"><span>Thème puppyplay</span>${sw('skin', st.skin !== false)}</div>
        <div class="row"><span>Mode sombre automatique<small style="display:block;font-weight:400;color:var(--muted);font-size:12px">Assombrit les sites clairs</small></span>${sw('skinDark', st.skinDark !== false)}</div>
        <p style="margin:6px 0 0;font-size:12.5px;color:var(--muted)">🏦 Les sites sensibles (banques, paiement, impôts, Ameli, CAF…) gardent leur look officiel : c'est plus sûr pour repérer une fausse page. Menu → « Thème puppy ici » pour l'activer ou le couper site par site.${st.skinOffN ? ` ${st.skinOffN} site(s) exclu(s) à la main.` : ''}</p>
        ${st.skinOffN ? `<button class="ab small glass" data-act="skinreset" type="button" style="margin-top:8px">Réinitialiser les exceptions</button>` : ''}
        <div class="row"><span>Accessoires puppy<small style="display:block;font-weight:400;color:var(--muted);font-size:12px">Chiots sur les logos, pattes autour des J'aime (jamais cliquables)</small></span>${sw('deco', st.deco !== false)}</div>
        <div class="row"><span>Vocabulaire puppy<small style="display:block;font-weight:400;color:var(--muted);font-size:12px">lit → panier, assiette → gamelle… (souligné, touche pour voir l'original)</small></span>${sw('words', st.words !== false)}</div>
        <button class="ab wide c2" data-act="dict" type="button" style="margin-top:6px">${I('news', 'chrome', { shape: 'none' })}Dictionnaire puppy</button>
        <p style="margin:6px 0 0;font-size:12.5px;color:var(--muted)">🐾 Pour déplacer, agrandir ou cacher les chiots d'un site : Menu → « Placer les chiots ».</p>
      </div>
      <div class="cp">
        <div class="cp-h">${I('shield', 'cyan')}<div><b>Chien de garde des CGU</b><small>Vérifie une fois par jour les règles officielles des sites visités (robots.txt pour les IA, protocole TDMRep, balises « noai ») et te prévient si elles changent. Sans IA.</small></div></div>
        <div class="row"><span>Chien de garde</span>${sw('guard', st.guard !== false)}</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('lock', 'red')}<div><b>Ouverture des autres applis</b><small>Toujours bloquée : aucun site ne peut lancer une appli (WhatsApp, Play Store, appli d'un magasin…) tout seul. Tu décides avec « Ouvrir quand même ».</small></div></div>
        <div class="ok">🔒 Verrouillé</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('search', 'cyan')}<div><b>Moteur de recherche</b><small>Utilisé quand tu tapes autre chose qu'une adresse.</small></div></div>
        <div class="choices c3">${Object.entries(ENGINES).map(([k, n]) => `<button class="choice ${st.engine === k ? 'on' : ''}" data-set="engine" data-v="${k}" type="button">${I('search', colorOf(k), { shape: 'orb' })}<b>${n}</b></button>`).join('')}</div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('people', 'blue')}<div><b>Confidentialité & affichage</b><small>Réglages appliqués à tous les onglets.</small></div></div>
        <div class="row"><span>Bloquer les cookies tiers<small>les pisteurs d'un site à l'autre</small></span>${sw('block3p', st.block3p !== false)}</div>
        <div class="row"><span>JavaScript<small>à couper seulement si un site abuse</small></span>${sw('js', st.js !== false)}</div>
        <div class="row"><span>Version PC des sites</span>${sw('desktop', !!st.desktop)}</div>
        <span class="lbl" style="margin-top:0">Taille du texte · <b id="zv">${st.zoom || 100} %</b></span><input type="range" id="zoom" min="70" max="170" step="10" value="${st.zoom || 100}">
      </div>
      <div class="cp">
        <div class="cp-h">${I('download', 'amber')}<div><b>Sauvegarde</b><small>Exporte tes réglages, tes onglets et tes favoris dans un fichier. <b>Les mots de passe ne sont jamais inclus</b> (PuppyInternet ne les enregistre pas).</small></div></div>
        <div class="actions"><button class="ab amber" data-act="export" type="button">${I('download', 'chrome', { shape: 'none' })} Exporter</button><button class="ab glass" data-act="import" type="button">${I('upload', 'chrome', { shape: 'none' })} Importer</button></div>
      </div>
      <div class="cp">
        <div class="cp-h">${I('trash', 'red')}<div><b>Effacer</b><small>Les cookies déconnectent des sites.</small></div></div>
        <div class="quick"><button class="ab small glass" data-clear="history" type="button">Historique</button><button class="ab small glass" data-clear="cookies" type="button">Cookies</button><button class="ab small glass" data-clear="cache" type="button">Cache</button></div>
      </div>
      <div class="hero"><div class="logo neon" style="font-size:20px">PUPPY<span>INTERNET</span></div><div class="kicker">v1 · dans PuppyPhone · 🐾</div></div>`;
    const z = $('#zoom');
    const upd = () => { z.style.setProperty('--v', ((z.value - 70) / 100 * 100) + '%'); $('#zv').textContent = z.value + ' %'; };
    upd();
    z.oninput = upd;
    z.onchange = () => call('setSetting', 'zoom', z.value);
  }

  // ------------------------------------------------------------------ actions
  function go(text) { haptic(); call('go', text); setMode(null); }
  function openUrl(u) { go(u); }
  function backup() {
    return JSON.stringify({
      app: 'PuppyInternet', version: 1, exported: new Date().toISOString(),
      note: 'Mots de passe jamais inclus.',
      settings: { engine: st.engine, adblock: st.adblock, desktop: st.desktop, js: st.js, block3p: st.block3p, zoom: st.zoom },
      favorites: favs,
      tabs: (st.tabs || []).filter((t) => t.url).map((t) => ({ url: t.url, title: t.title })),
    }, null, 2);
  }
  function importBackup(txt) {
    const b = J(txt, null);
    if (!b || b.app !== 'PuppyInternet') { call('toast', 'Ce fichier n\'est pas une sauvegarde PuppyInternet'); return; }
    const s = b.settings || {};
    for (const k of ['adblock', 'desktop', 'js', 'block3p']) if (typeof s[k] === 'boolean') call('setSetting', k, String(s[k]));
    if (s.engine && ENGINES[s.engine]) call('setSetting', 'engine', s.engine);
    if (s.zoom) call('setSetting', 'zoom', String(s.zoom));
    if (Array.isArray(b.favorites)) {
      for (const f of b.favorites) if (f && /^https?:/.test(f.url) && !isFav(f.url)) favs.push({ url: f.url, title: String(f.title || '') });
      saveFavs();
    }
    if (Array.isArray(b.tabs)) call('restoreTabs', JSON.stringify(b.tabs));
    call('toast', '🐾 Sauvegarde importée : ' + (b.tabs || []).length + ' onglets, ' + (b.favorites || []).length + ' favoris');
    if (mode) render();
  }

  document.addEventListener('click', (e) => {
    const t = e.target.closest('button,[data-tab],[data-fav]');
    if (!t) return;
    const d = t.dataset;
    if (d.close) { e.stopPropagation(); haptic(); call('close', +d.close); return; }
    if (d.delfav != null) { e.stopPropagation(); favs.splice(+d.delfav, 1); saveFavs(); render(); return; }
    if (d.go) { setMode(d.go); return; }
    if (d.tab) { haptic(); call('select', +d.tab); setMode(null); return; }
    if (d.fav != null && favs[+d.fav]) { openUrl(favs[+d.fav].url); return; }
    if (d.q) { go(d.search ? (ENGINE_URL[st.engine] || ENGINE_URL.ddg).replace('%s', encodeURIComponent(d.q)) : d.q); return; }
    if (d.u) { openUrl(d.u); return; }
    if (d.clear) { call('clear', d.clear); if (mode === 'history') render(); return; }
    if (d.set) { call('setSetting', d.set, d.v); return; }
    if (d.act === 'addfav') { const a = act(); favs.push({ url: a.url, title: a.title || hostOf(a.url) }); saveFavs(); render(); return; }
    if (d.act === 'newtab') { call('newTab', ''); setMode('start'); autoStart = true; return; }
    if (d.act === 'closeall') { call('closeAll'); setMode('start'); autoStart = true; return; }
    if (d.act === 'default') { call('askDefault'); return; }
    if (d.act === 'skinreset') { call('skinReset'); return; }
    if (d.act === 'dict') { setMode('dict'); return; }
    if (d.act === 'export') { call('exportBackup', backup()); return; }
    if (d.act === 'import') { call('importBackup'); return; }
    if (d.m) {
      const a = act();
      haptic();
      switch (d.m) {
        case 'back': call('back'); setMode(null); break;
        case 'fwd': call('forward'); setMode(null); break;
        case 'reload': call('reload'); setMode(null); break;
        case 'fav':
          if (isFav(a.url)) favs = favs.filter((f) => f.url !== a.url); else favs.push({ url: a.url, title: a.title || hostOf(a.url) });
          saveFavs(); render(); break;
        case 'share': call('share'); setMode(null); break;
        case 'pin': call('pinHome'); setMode(null); break;
        case 'desktop': call('setSetting', 'desktop', String(!st.desktop)); setMode(null); break;
        case 'external': call('openExternal', a.url); setMode(null); break;
        case 'favs': setMode('favs'); break;
        case 'history': setMode('history'); break;
        case 'downloads': call('openDownloads'); setMode(null); break;
        case 'settings': setMode('settings'); break;
        case 'newtab': call('newTab', ''); setMode('start'); autoStart = true; break;
        case 'closetab': call('close', a.id); setMode(null); break;
        case 'adblock': call('setSetting', 'adblock', String(st.adblock === false)); break;
        case 'skin': call('skinToggle'); setMode(null); break;
        case 'guard': setMode('guard'); break;
        case 'decoedit': call('decoEdit'); setMode(null); break;
        case 'phone': call('home'); setMode(null); break;
      }
    }
  });
  $('#bhome').onclick = () => { haptic(); if (mode === 'start') { setMode(null); } else setMode('start'); };
  $('#hostbtn').onclick = () => { haptic(); setMode('type'); };
  $('#shield').onclick = () => { haptic(); setMode('settings'); };
  $('#rel').onclick = () => { haptic(); call('reload'); };
  $('#btabs').onclick = () => { haptic(); setMode(mode === 'tabs' ? null : 'tabs'); };
  $('#bmenu').onclick = () => { haptic(); setMode(mode === 'menu' ? null : 'menu'); };

  let noticeTimer;
  function notice(n) {
    const box = $('#notice');
    let html;
    if (n.kind === 'app') {
      const what = String(n.what || '').replace(/^com\.|^android\./, '');
      html = `${I('lock', 'amber', { shape: 'orb' })}<span>Ouverture d'appli bloquée<small>${esc(what || n.url)}</small></span><button class="ab small amber" data-ext="1" type="button">Ouvrir quand même</button><button class="nx" type="button">✕</button>`;
    } else {
      html = `${I('shield', 'green', { shape: 'orb' })}<span>${esc(n.text || '')}</span><button class="nx" type="button">✕</button>`;
    }
    box.innerHTML = html;
    box.hidden = false;
    $('.nx', box).onclick = () => { box.hidden = true; };
    const ext = $('[data-ext]', box);
    if (ext) ext.onclick = () => { box.hidden = true; call('openExternal', n.url); };
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { box.hidden = true; }, n.kind === 'app' ? 7000 : 3000);
  }

  // ------------------------------------------------------------------ Dictionnaire puppy (personnalisable)
  const LANGS = [['fr', '🇫🇷 FR'], ['en', '🇬🇧 EN'], ['es', '🇪🇸 ES'], ['de', '🇩🇪 DE'], ['it', '🇮🇹 IT'], ['pt', '🇵🇹 PT']];
  let dictLang = 'fr', dictState = null, dictSaveT = 0, resetArmed = false;
  function dictSave() { clearTimeout(dictSaveT); dictSaveT = setTimeout(() => { call('dictSet', JSON.stringify(dictState)); }, 500); }
  function renderDict() {
    if (!dictState) dictState = J(call('dictGet'), {}) || {};
    const list = dictState[dictLang] = dictState[dictLang] || [];
    const fr = dictLang === 'fr';
    ovin.innerHTML = `<div class="secth">${I('news', 'pink', { shape: 'orb' })}<span class="chrome">DICTIONNAIRE PUPPY</span></div>
      <div class="cp"><div class="cp-h">${I('bone', 'gold')}<div><b>Mot d'origine → devient</b><small>Les mots se remplacent sur les pages, uniquement à l'affichage. ${fr ? 'Indique si le mot puppy est masculin ou féminin : « le/la », « mon/ma », « ce/cette » s\'accordent tout seuls.' : ''}</small></div></div>
        <div class="seg dlangs">${LANGS.map(([k, l]) => `<button class="${k === dictLang ? 'on' : ''}" data-dlang="${k}" type="button">${l}</button>`).join('')}</div></div>
      <div class="dlist">${list.map((e, i) => `<div class="drow${e.on === false ? ' off' : ''}" data-di="${i}">
          <button class="sw${e.on !== false ? ' on' : ''}" data-dsw="${i}" type="button"><i></i></button>
          <input class="din" data-dk="o" value="${esc(e.o || '')}" placeholder="origine" autocapitalize="off" autocomplete="off">
          <span class="darr">➜</span>
          <input class="din" data-dk="r" value="${esc(e.r || '')}" placeholder="devient" autocapitalize="off" autocomplete="off">
          ${fr ? `<button class="dg" data-dg="${i}" type="button">${e.g === 'f' ? 'la' : 'le'}</button>` : ''}
          <button class="del" data-ddel="${i}" type="button">✕</button></div>`).join('') || `<div class="empty">${I('news', 'pink')}Aucun mot pour cette langue.</div>`}</div>
      <button class="ab wide green" data-dadd="1" type="button">${I('plus', 'chrome', { shape: 'none' })}Ajouter un mot</button>
      <button class="ab wide red" data-dreset="1" type="button" style="margin-top:10px">${I('refresh', 'chrome', { shape: 'none' })}${resetArmed ? 'Sûr ? Touche encore pour tout remettre à l\'origine' : 'Réparer le dictionnaire'}</button>
      <p style="font-size:12px;color:var(--muted);margin:8px 2px">« Réparer » efface tes modifications de toutes les langues et remet le dictionnaire d'origine.</p>`;
  }
  ovin.addEventListener('input', (e) => {
    if (mode !== 'dict') return;
    const inp = e.target.closest('.din'); if (!inp) return;
    const i = +inp.closest('[data-di]').dataset.di;
    dictState[dictLang][i][inp.dataset.dk] = inp.value;
    dictSave();
  });
  ovin.addEventListener('click', (e) => {
    if (mode !== 'dict') return;
    const b = e.target.closest('[data-dlang],[data-dsw],[data-dg],[data-ddel],[data-dadd],[data-dreset]'); if (!b) return;
    e.stopPropagation(); haptic();
    const d = b.dataset, list = dictState[dictLang];
    if (d.dlang) { dictLang = d.dlang; resetArmed = false; }
    if (d.dsw) { const x = list[+d.dsw]; x.on = x.on === false; dictSave(); }
    if (d.dg) { const x = list[+d.dg]; x.g = x.g === 'f' ? 'm' : 'f'; dictSave(); }
    if (d.ddel) { list.splice(+d.ddel, 1); dictSave(); }
    if (d.dadd) { list.unshift(dictLang === 'fr' ? { o: '', r: '', g: 'm', on: true } : { o: '', r: '', on: true }); }
    if (d.dreset) {
      if (!resetArmed) { resetArmed = true; setTimeout(() => { resetArmed = false; if (mode === 'dict') render(); }, 4000); }
      else { resetArmed = false; call('dictReset'); dictState = J(call('dictDefault'), {}) || {}; }
    }
    render();
    if (d.dadd) { const f = ovin.querySelector('.din'); if (f) f.focus(); }
  }, true);

  // ------------------------------------------------------------------ Chien de garde des CGU
  let tosLinks = [];
  function renderGuard() {
    const g = J(call('guardInfo'), {}) || {};
    const h = g.here, a = act();
    const fmtD = (t) => t ? new Date(t).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';
    const badge = (s) => s === 'rouge' ? `<span class="gbadge red">LISTE ROUGE</span>` : s === 'verte' ? `<span class="gbadge green">LISTE VERTE</span>` : `<span class="gbadge">PAS ENCORE VÉRIFIÉ</span>`;
    const proofs = (arr) => (arr || []).map((p) => `<div class="gproof"><small>${esc(p.where)} · ${esc(p.who || '')}</small><code>${esc(p.text)}</code><button class="ab small glass" data-gopen="${esc(p.src)}" type="button">Voir la preuve</button></div>`).join('');
    const all = (g.all || []).sort((x, y) => y.t - x.t);
    const red = all.filter((x) => x.status === 'rouge'), green = all.filter((x) => x.status === 'verte');
    let html = `<div class="secth">${I('shield', 'cyan', { shape: 'orb' })}<span class="chrome c2">CHIEN DE GARDE 🐕‍🦺</span></div>`;
    if (!a.blank) {
      html += `<div class="cp"><div class="cp-h">${I('globe', h && h.status === 'rouge' ? 'red' : 'green')}<div><b>${esc(g.host || '')}</b><small>${h ? 'Vérifié le ' + fmtD(h.t) : 'Vérification automatique à la fin du chargement'}</small></div></div>
        ${badge(h && h.status)}
        <p class="gtxt">${!h ? '' : h.status === 'rouge' ? 'L\'éditeur refuse officiellement les IA et/ou la fouille automatique de ses pages. Les futures fonctions IA de PuppyInternet resteront coupées ici.' : 'Aucune interdiction IA ou fouille déclarée par l\'éditeur dans ses règles officielles.'}</p>
        ${h && h.status === 'rouge' ? proofs(h.proofs) : ''}
        ${h && h.changedAt ? `<div class="galert">⚠️ Changement détecté le ${fmtD(h.changedAt)} : liste ${esc(h.oldStatus || '?')} → liste ${esc(h.status)}${(h.oldProofs || []).length ? `<details><summary>Anciennes règles</summary>${proofs(h.oldProofs)}</details>` : ''}</div>` : ''}
        <div class="gacts"><button class="ab small" data-gact="recheck" type="button">${I('refresh', 'chrome', { shape: 'none' })}Revérifier</button><button class="ab small c2" data-gact="find" type="button">${I('search', 'chrome', { shape: 'none' })}Trouver les CGU</button><button class="ab small violet" data-gact="sniff" type="button">${I('paw', 'chrome', { shape: 'none' })}Renifler cette page</button></div>
        ${tosLinks.length ? `<div class="gtos"><small>Liens trouvés sur la page :</small>${tosLinks.map((l) => `<button class="srow" data-gsniff="${esc(l.u)}" type="button">${I('news', 'amber', { shape: 'orb' })}<span class="tx"><b>${esc(l.t || 'Conditions')}</b><small>${esc(l.u)}</small></span></button>`).join('')}</div>` : ''}
        ${g.tos ? `<div class="gtosinfo">📜 CGU reniflées le ${fmtD(g.tos.t)} · ${g.tos.count} passage(s) à lire${(g.tos.kinds || []).length ? ' (' + esc(g.tos.kinds.join(', ')) + ')' : ''}${g.tos.changedAt ? `<br><b style="color:#ffb627">⚠️ Le texte a changé depuis ta lecture du ${fmtD(g.tos.prevT)}</b>` : ''}<br><button class="srow" data-gopen="${esc(g.tos.url)}" type="button" style="margin-top:6px">${I('external', 'cyan', { shape: 'orb' })}<span class="tx"><b>Rouvrir les CGU</b><small>${esc(g.tos.url)}</small></span></button></div>` : ''}
      </div>`;
    }
    html += `<div class="cp"><div class="cp-h">${I('info', 'violet')}<div><b>Bon à savoir</b><small>Le thème puppyplay, les accessoires et le vocabulaire ne sont pas concernés par ces règles : ils changent seulement l'affichage sur ton téléphone. Les règles robots.txt et TDMRep visent les robots et l'IA. Ce n'est pas un avis juridique : en cas de doute, lis les CGU (bouton « Renifler »).</small></div></div></div>`;
    if ((g.log || []).length) html += `<div class="day">Changements de règles</div>` + g.log.slice(0, 20).map((l) => `<div class="glog">${fmtD(l.t)} · <b>${esc(l.host)}</b> : ${esc(l.from)} → <b class="${l.to === 'rouge' ? 'r' : 'g'}">${esc(l.to)}</b>${(l.proofs || []).length ? `<details><summary>Preuves</summary>${proofs(l.proofs)}</details>` : ''}</div>`).join('');
    html += `<div class="day">Liste rouge · ${red.length}</div>` + (red.map((x) => `<div class="glist r"><b>${esc(x.host)}</b><small>${fmtD(x.t)}</small><button class="del" data-gforget="${esc(x.host)}" type="button">✕</button></div>`).join('') || `<div class="empty" style="padding:8px">Aucun site.</div>`);
    html += `<div class="day">Liste verte · ${green.length}</div>` + (green.slice(0, 80).map((x) => `<div class="glist g"><b>${esc(x.host)}</b><small>${fmtD(x.t)}</small><button class="del" data-gforget="${esc(x.host)}" type="button">✕</button></div>`).join('') || `<div class="empty" style="padding:8px">Aucun site.</div>`);
    html += `<button class="ab wide glass" data-gact="export" type="button" style="margin-top:10px">${I('download', 'chrome', { shape: 'none' })}Enregistrer les listes (fichier)</button>`;
    ovin.innerHTML = html;
  }
  ovin.addEventListener('click', (e) => {
    const b = e.target.closest('[data-gact],[data-gopen],[data-gsniff],[data-gforget]'); if (!b || mode !== 'guard') return;
    e.stopPropagation(); haptic();
    const d = b.dataset;
    if (d.gact === 'recheck') { call('guardRecheck'); toast('Le chien renifle les règles… 🐕‍🦺'); }
    if (d.gact === 'find') call('guardFindTos');
    if (d.gact === 'sniff') { call('guardSniffHere'); setMode(null); }
    if (d.gact === 'export') call('guardExport');
    if (d.gopen) { call('newTab', d.gopen); setMode(null); }
    if (d.gsniff) { call('guardOpenSniff', d.gsniff); tosLinks = []; setMode(null); }
    if (d.gforget) { call('guardForget', d.gforget); setTimeout(render, 150); }
  }, true);
  function toast(m) { call('toast', m); }

  // ------------------------------------------------------------------ pont natif
  window.NetUI = {
    on(ev, data) {
      if (ev === 'state') {
        st = J(data, st) || st;
        renderBar();
        const a = act();
        if (a.blank && !mode) { autoStart = true; setMode('start'); }
        else if (!a.blank && autoStart && mode === 'start') { autoStart = false; setMode(null); }
        else if (mode === 'tabs' || mode === 'menu' || (mode === 'start' && !document.activeElement.matches('input'))) render();
      } else if (ev === 'collapse') { autoStart = false; if (mode) setMode(null); }
      else if (ev === 'notice') notice(J(data, {}));
      else if (ev === 'import') importBackup(data);
      else if (ev === 'guard' || ev === 'guardAlert') { if (mode === 'guard') render(); }
      else if (ev === 'tosLinks') { tosLinks = J(data, []) || []; if (mode !== 'guard') setMode('guard'); else render(); }
    },
    back() {
      if (!mode) return false;
      if (mode === 'start' && act().blank) return false;
      if (mode === 'dict') { setMode('settings'); return true; }
      if (mode === 'favs' || mode === 'history' || mode === 'settings' || mode === 'guard') { setMode('menu'); return true; }
      setMode(null);
      return true;
    },
    insets(t, b) {
      const r = document.documentElement.style;
      r.setProperty('--st', Math.max(t, 20) + 'px');
      r.setProperty('--sb', Math.max(b, 0) + 'px');
    },
  };

  $('#bhome').innerHTML = I('paw', 'chrome', { shape: 'none' });
  $('#shi').innerHTML = I('shield', 'green', { shape: 'none' });
  $('#bmenu').innerHTML = I('menu', 'chrome', { shape: 'none' });
  renderBar();
  if (act().blank) { autoStart = true; setMode('start'); }

  // ------------------------------------------------------------------ aperçu navigateur (hors APK)
  function mockNet() {
    const s = { tabs: [{ id: 1, title: 'Le Havre — Wikipédia', url: 'https://fr.wikipedia.org/wiki/Le_Havre', active: true, thumb: 0 }, { id: 2, title: '', url: '', active: false }],
      active: { id: 1, url: 'https://fr.wikipedia.org/wiki/Le_Havre', title: 'Le Havre — Wikipédia', loading: true, progress: 60, blocked: 14, blank: false, canBack: true, canFwd: false, secure: true },
      blockedTotal: 12873, hosts: 151234, adblock: true, desktop: false, js: true, block3p: true, zoom: 100, engine: 'ddg', isDefault: false };
    const mem = {};
    return {
      state: () => JSON.stringify(s), get: (k) => mem[k] || null, set: (k, v) => { mem[k] = v; },
      history: () => JSON.stringify([{ url: 'https://fr.wikipedia.org/wiki/Le_Havre', title: 'Le Havre — Wikipédia', t: Date.now() - 6e4 }, { url: 'https://www.youtube.com/', title: 'YouTube', t: Date.now() - 864e5 }]),
      expand() {}, capture() {}, haptic() {}, toast() {}, go() {}, select() {}, close() {}, newTab() {}, setSetting() {},
    };
  }
})();
