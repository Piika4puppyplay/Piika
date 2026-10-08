/* PupDeco — habillage puppyplay local (PuppyInternet). Purement visuel :
   - accessoires de chiots dessinés par-dessus les logos et les boutons J'aime (calque séparé, jamais cliquable) ;
   - vocabulaire puppy (lit → panier…) en remplaçant seulement le texte affiché, jamais dans les champs ni les formulaires.
   Rien n'est envoyé au site, aucune fonction n'est modifiée. */
(function () {
  'use strict';
  if (window.__pupDeco) { window.__pupDeco.cfg(window.__pupCfg || {}); return; }
  const CFG = Object.assign({ deco: true, words: true }, window.__pupCfg || {});
  const host = location.hostname.replace(/^(www|m|mobile)\./, '');

  // ============================================================== DESSINS (originaux)
  const FUR = '<linearGradient id="f" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffe7c9"/><stop offset="1" stop-color="#e3a462"/></linearGradient><linearGradient id="d" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#c97a3a"/><stop offset="1" stop-color="#8a4a1c"/></linearGradient>';
  const svg = (vb, body) => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}"><defs>${FUR}<filter id="g"><feDropShadow dx="0" dy="1.5" stdDeviation="1.5" flood-color="#000" flood-opacity=".5"/></filter></defs><g filter="url(#g)">${body}</g></svg>`);
  const face = (mood) => {
    const eyes = mood === 'sad' ? '<path d="M38 40q5-4 10 0M52 40q5-4 10 0" stroke="#1a0b2e" stroke-width="3" fill="none" stroke-linecap="round"/><ellipse cx="43" cy="45" rx="4" ry="5" fill="#1a0b2e"/><ellipse cx="57" cy="45" rx="4" ry="5" fill="#1a0b2e"/>'
      : '<ellipse cx="42" cy="42" rx="5" ry="6" fill="#1a0b2e"/><ellipse cx="58" cy="42" rx="5" ry="6" fill="#1a0b2e"/><circle cx="43.5" cy="40" r="1.8" fill="#fff"/><circle cx="59.5" cy="40" r="1.8" fill="#fff"/>';
    const mouth = mood === 'sad' ? '<path d="M44 60q6-4 12 0" stroke="#1a0b2e" stroke-width="2.5" fill="none" stroke-linecap="round"/>'
      : '<path d="M50 55v4M50 59q-5 5-9 1M50 59q5 5 9 1" stroke="#1a0b2e" stroke-width="2.5" fill="none" stroke-linecap="round"/><path d="M47 62q3 9 6 0z" fill="#ff5e9e"/>';
    return `<path d="M24 26c-10 2-14 22-6 32 5 6 12 2 12-6z" fill="url(#d)"/><path d="M76 26c10 2 14 22 6 32-5 6-12 2-12-6z" fill="url(#d)"/>
      <ellipse cx="50" cy="44" rx="28" ry="25" fill="url(#f)"/><ellipse cx="50" cy="56" rx="15" ry="11" fill="#fff6ea"/>
      ${eyes}<ellipse cx="50" cy="53" rx="5.5" ry="4" fill="#1a0b2e"/>${mouth}<ellipse cx="36" cy="52" rx="4" ry="2.5" fill="#ff7ab8" opacity=".55"/><ellipse cx="64" cy="52" rx="4" ry="2.5" fill="#ff7ab8" opacity=".55"/>`;
  };
  const paw = (x, y, r) => `<g transform="translate(${x} ${y}) scale(${r})"><ellipse cx="0" cy="0" rx="11" ry="9" fill="url(#f)" stroke="#b56a2c" stroke-width="1.5"/><path d="M-5 -4v6M0 -6v7M5 -4v6" stroke="#b56a2c" stroke-width="1.5" stroke-linecap="round"/></g>`;
  const ART = {
    // chiot qui dépasse derrière, pattes posées sur le bord
    peek: svg('0 0 100 76', face('happy') + paw(30, 70, 1) + paw(70, 70, 1)),
    // chiot câlin : tête + deux bras qui entourent
    hugHead: svg('0 0 100 76', face('happy') + paw(26, 70, .9) + paw(74, 70, .9)),
    hugArm: svg('0 0 40 40', `<path d="M4 6c14 0 26 10 26 22" stroke="url(#f)" stroke-width="12" fill="none" stroke-linecap="round"/>` + paw(30, 30, .85)),
    // oreilles de chien + collier néon
    earL: svg('0 0 40 50', '<path d="M30 6C12 4 2 24 8 42c4 10 14 6 14-4L28 14z" fill="url(#d)"/><path d="M26 12C16 12 10 26 13 38" stroke="#ffb3d6" stroke-width="3" fill="none" opacity=".6"/>'),
    earR: svg('0 0 40 50', '<path d="M10 6c18-2 28 18 22 36-4 10-14 6-14-4L12 14z" fill="url(#d)"/><path d="M14 12c10 0 16 14 13 26" stroke="#ffb3d6" stroke-width="3" fill="none" opacity=".6"/>'),
    collar: svg('0 0 100 20', '<rect x="2" y="4" width="96" height="10" rx="5" fill="#ff3fa4" stroke="#fff" stroke-width="1.5"/><circle cx="20" cy="9" r="2" fill="#fff"/><circle cx="80" cy="9" r="2" fill="#fff"/><circle cx="50" cy="15" r="5" fill="#ffd34d" stroke="#8a5a00" stroke-width="1.5"/>'),
    // pattes qui tiennent un bouton
    pawL: svg('0 0 30 30', paw(15, 15, 1.15)),
    pawR: svg('0 0 30 30', paw(15, 15, 1.15)),
    likeHead: svg('0 0 100 76', face('happy')),
    sadHead: svg('0 0 100 76', face('sad')),
    bone: svg('0 0 60 30', '<g fill="#fff3fa" stroke="#ff3fa4" stroke-width="2"><rect x="14" y="10" width="32" height="10" rx="5"/><circle cx="13" cy="10" r="7"/><circle cx="13" cy="20" r="7"/><circle cx="47" cy="10" r="7"/><circle cx="47" cy="20" r="7"/></g><rect x="15" y="11" width="30" height="8" fill="#fff3fa"/>'),
    stickPaw: svg('0 0 40 40', '<circle cx="20" cy="20" r="18" fill="#ff3fa4" stroke="#fff" stroke-width="2.5"/><g fill="#fff"><ellipse cx="20" cy="24" rx="7" ry="6"/><circle cx="12" cy="16" r="3"/><circle cx="17" cy="11" r="3"/><circle cx="23" cy="11" r="3"/><circle cx="28" cy="16" r="3"/></g>'),
  };

  // ============================================================== CIBLES
  const LOGOS = [
    [/(^|\.)youtube\.com$/, '#logo-icon, ytd-topbar-logo-renderer #logo, ytm-home-logo, .mobile-topbar-header-endpoint, a[aria-label*="YouTube"] yt-icon', 'peek'],
    [/(^|\.)(x|twitter)\.com$/, 'header a[aria-label="X"], a[href="/home"][aria-label] svg, header h1 a svg, [data-testid="TopNavBar"] svg[viewBox="0 0 24 24"]', 'hug'],
    [/(^|\.)grok\.(com|x\.ai)$/, 'header a[href="/"] svg, nav a[href="/"] svg, a[aria-label*="Grok"] svg, header svg', 'ears'],
    [/(^|\.)instagram\.com$/, 'svg[aria-label="Instagram"]', 'peek'],
    [/(^|\.)facebook\.com$/, 'svg[aria-label="Facebook"], a[aria-label="Facebook"], #header-notices + div svg', 'ears'],
    [/(^|\.)reddit\.com$/, '#reddit-logo, a[aria-label="Home"] svg, faceplate-tracker[noun="reddit_logo"] svg', 'peek'],
    [/(^|\.)google\.[a-z.]+$/, 'img[alt="Google"], svg[aria-label="Google"], #hplogo', 'ears'],
    [/(^|\.)wikipedia\.org$/, '.mw-logo, .branding-box, .mw-wiki-logo', 'peek'],
    [/(^|\.)amazon\.[a-z.]+$/, '#nav-logo-sprites, #nav-logo, .nav-logo-link', 'peek'],
    [/(^|\.)twitch\.tv$/, 'a[aria-label="Twitch Home"] svg, a[data-a-target="home-link"] svg', 'hug'],
    [/(^|\.)tiktok\.com$/, '[data-e2e="tiktok-logo"], a[data-e2e="tiktok-logo"] svg', 'ears'],
    [/(^|\.)github\.com$/, '.octicon-mark-github', 'peek'],
  ];
  const GENERIC_LOGO = 'header [class*="logo" i] img, header [class*="logo" i] svg, header img[alt*="logo" i], [id*="logo" i] img, [id*="logo" i] svg, a[class*="logo" i], [class*="brand" i] img, [aria-label*="logo" i]';
  const RX_LIKE = /(^|\s)(j[’']aime|like|liker|aimer|upvote|vote positif|me gusta|gefällt mir|mi piace|curtir|gostei)(\s|$|\b)/i;
  const RX_DISLIKE = /(je n[’']aime pas|dislike|downvote|vote négatif|no me gusta|non mi piace|não gostei)/i;
  const RX_FOLLOW = /(^|\s)(s[’']abonner|abonne|subscribe|follow|suivre|seguir|abonnieren|segui)(\s|$|\b)/i;

  let layer = null;
  let decos = []; // {el (cible), parts:[{img, place}]}
  const isVisible = (r) => r.width > 2 && r.height > 2 && r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;

  function ensureLayer() {
    if (layer && layer.isConnected) return layer;
    layer = document.createElement('div');
    layer.id = 'pup-deco';
    layer.setAttribute('aria-hidden', 'true');
    layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483645;contain:layout style;overflow:hidden';
    (document.body || document.documentElement).appendChild(layer);
    return layer;
  }
  function img(src, cls) {
    const i = document.createElement('img');
    i.src = src; i.alt = ''; i.className = cls || '';
    i.style.cssText = 'position:absolute;left:0;top:0;pointer-events:none;user-select:none;will-change:transform;filter:drop-shadow(0 0 4px rgba(255,63,164,.55));transition:opacity .2s';
    ensureLayer().appendChild(i);
    return i;
  }

  // placement : f(rect) → [x, y, w, h]
  const KITS = {
    peek: () => [{ src: ART.peek, at: (r) => { const w = Math.max(28, Math.min(56, r.height * 1.9)); return r.top - w * .62 < 4 ? [r.right - w * .28, r.top + r.height / 2 - w * .5, w, w * .76] : [r.right - w * .75, r.top - w * .62, w, w * .76]; } }],
    hug: () => [
      { src: ART.hugArm, at: (r) => { const s = Math.max(12, Math.min(30, r.height * .9)); return [r.left - s * .45, r.top + r.height / 2 - s / 2, s, s]; }, flip: true },
      { src: ART.hugArm, at: (r) => { const s = Math.max(12, Math.min(30, r.height * .9)); return [r.right - s * .55, r.top + r.height / 2 - s / 2, s, s]; } },
      { src: ART.hugHead, at: (r) => { const w = Math.max(24, Math.min(46, r.height * 1.5)); return [r.right - w * .22, r.top + r.height / 2 - w * .5, w, w * .76]; } },
    ],
    ears: () => [
      { src: ART.earL, at: (r) => { const s = Math.max(14, Math.min(30, r.height * .9)); return [r.left - s * .35, Math.max(2, r.top - s * .7), s * .8, s]; } },
      { src: ART.earR, at: (r) => { const s = Math.max(14, Math.min(30, r.height * .9)); return [r.right - s * .45, Math.max(2, r.top - s * .7), s * .8, s]; } },
      { src: ART.collar, at: (r) => { const w = Math.max(20, Math.min(120, r.width * .7)); return [r.left + r.width / 2 - w / 2, r.bottom - w * .05, w, w * .2]; } },
    ],
    like: () => [
      { src: ART.pawL, at: (r) => { const s = Math.max(14, Math.min(22, r.height * .6)); return [r.left - s * .45, r.top + r.height / 2 - s / 2, s, s]; } },
      { src: ART.pawR, at: (r) => { const s = Math.max(14, Math.min(22, r.height * .6)); return [r.right - s * .55, r.top + r.height / 2 - s / 2, s, s]; } },
      { src: ART.likeHead, at: (r) => { const w = Math.max(22, Math.min(36, r.height * 1)); return [r.left + r.width / 2 - w / 2, Math.max(2, r.top - w * .6), w, w * .76]; } },
    ],
    dislike: () => [
      { src: ART.pawL, at: (r) => { const s = Math.max(14, Math.min(22, r.height * .6)); return [r.left - s * .45, r.top + r.height / 2 - s / 2, s, s]; } },
      { src: ART.pawR, at: (r) => { const s = Math.max(14, Math.min(22, r.height * .6)); return [r.right - s * .55, r.top + r.height / 2 - s / 2, s, s]; } },
      { src: ART.sadHead, at: (r) => { const w = Math.max(22, Math.min(34, r.height * .95)); return [r.left + r.width / 2 - w / 2, Math.max(2, r.top - w * .6), w, w * .76]; } },
    ],
    follow: () => [{ src: ART.bone, at: (r) => { const w = Math.max(22, Math.min(34, r.height * .9)); return [r.right - w * .6, Math.max(2, r.top - w * .3), w, w / 2]; } }],
    sticker: () => [{ src: ART.stickPaw, at: (r) => { const s = Math.max(12, Math.min(22, r.height * .6)); return [r.right - s * .5, r.top - s * .4, s, s]; } }],
  };

  function attach(el, kit) {
    if (decos.some((d) => d.el === el)) return;
    const parts = KITS[kit]().map((p) => ({ p, img: img(p.src) }));
    decos.push({ el, kit, parts, vis: true });
  }

  function labelOf(el) { return ((el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('data-testid'))) || '').trim(); }

  function scan() {
    if (!CFG.deco || !document.body) return;
    // nettoyage des cibles disparues
    decos = decos.filter((d) => { if (d.el.isConnected) return true; d.parts.forEach((x) => x.img.remove()); return false; });
    // logos
    const rule = LOGOS.find((l) => l[0].test(host));
    let logo = null;
    if (rule) logo = Array.from(document.querySelectorAll(rule[1])).find((e) => isVisible(e.getBoundingClientRect()));
    if (!logo) {
      logo = Array.from(document.querySelectorAll(GENERIC_LOGO)).find((e) => { const r = e.getBoundingClientRect(); return isVisible(r) && r.top < 180 && r.width >= 16 && r.width <= 360 && r.height <= 140; });
      if (logo) attach(logo, 'sticker');
    } else attach(logo, rule[2]);
    // boutons J'aime / Je n'aime pas / S'abonner
    let n = decos.filter((d) => d.kit !== 'peek' && d.kit !== 'hug' && d.kit !== 'ears' && d.kit !== 'sticker').length;
    const cands = document.querySelectorAll('button[aria-label], [role="button"][aria-label], a[aria-label], [data-testid="like"], [data-testid="unlike"], like-button-view-model button, dislike-button-view-model button, button[title]');
    for (const el of cands) {
      if (n >= 40) break;
      const r = el.getBoundingClientRect();
      if (!isVisible(r) || r.width > 260 || r.height > 90) continue;
      const lb = labelOf(el), tid = el.getAttribute('data-testid') || '';
      let kit = null;
      if (RX_DISLIKE.test(lb) || el.closest('dislike-button-view-model')) kit = 'dislike';
      else if (tid === 'like' || tid === 'unlike' || RX_LIKE.test(lb) || el.closest('like-button-view-model')) kit = 'like';
      else if (RX_FOLLOW.test(lb)) kit = 'follow';
      if (kit && !decos.some((d) => d.el === el)) { attach(el, kit); n++; }
    }
    // une cible masquée par une fenêtre du site ne garde pas ses accessoires
    for (const d of decos) {
      const r = d.el.getBoundingClientRect();
      let vis = isVisible(r);
      if (vis) { const t = document.elementFromPoint(Math.min(innerWidth - 1, Math.max(0, r.left + r.width / 2)), Math.min(innerHeight - 1, Math.max(0, r.top + r.height / 2))); vis = !!t && (t === d.el || d.el.contains(t) || t.contains(d.el)); }
      d.vis = vis;
    }
    place();
  }

  function place() {
    for (const d of decos) {
      const r = d.el.getBoundingClientRect();
      const show = d.vis && isVisible(r);
      for (const x of d.parts) {
        if (!show) { x.img.style.opacity = '0'; continue; }
        const [l, t, w, h] = x.p.at(r);
        x.img.style.width = w + 'px'; x.img.style.height = h + 'px';
        x.img.style.transform = `translate(${l}px,${t}px)${x.p.flip ? ' scaleX(-1)' : ''}`;
        x.img.style.opacity = '1';
      }
    }
  }
  let rafQ = false;
  const onMove = () => { if (rafQ) return; rafQ = true; requestAnimationFrame(() => { rafQ = false; place(); }); };
  addEventListener('scroll', onMove, { passive: true, capture: true });
  addEventListener('resize', onMove, { passive: true });

  // ============================================================== VOCABULAIRE PUPPY
  // Remplacements choisis pour garder le même genre et le même nombre : la phrase reste correcte.
  const W = {
    fr: { lit: 'panier', lits: 'paniers', canapé: 'panier', canapés: 'paniers', assiette: 'gamelle', assiettes: 'gamelles', maison: 'niche', maisons: 'niches', chambre: 'niche', chambres: 'niches',
      promenade: 'balade', promenades: 'balades', récompense: 'friandise', récompenses: 'friandises', cadeau: 'os', cadeaux: 'os', main: 'patte', mains: 'pattes', équipe: 'meute', équipes: 'meutes',
      nourriture: 'pâtée', humain: 'maître', humains: 'maîtres', visage: 'museau', visages: 'museaux', cheveux: 'poils', vêtement: 'pelage', vêtements: 'pelages', manteau: 'pelage', manteaux: 'pelages',
      boutique: 'animalerie', boutiques: 'animaleries', sommeil: 'dodo', ami: 'toutou', amis: 'toutous', copain: 'toutou', copains: 'toutous', bisou: 'câlin', bisous: 'câlins' },
    en: { bed: 'basket', beds: 'baskets', couch: 'basket', couches: 'baskets', plate: 'bowl', plates: 'bowls', house: 'kennel', houses: 'kennels', team: 'pack', teams: 'packs', friend: 'buddy', friends: 'buddies',
      hand: 'paw', hands: 'paws', gift: 'bone', gifts: 'bones', reward: 'treat', rewards: 'treats', food: 'kibble', face: 'snout', faces: 'snouts', hair: 'fur', human: 'hooman', humans: 'hoomans', people: 'hoomans', nap: 'snooze', kiss: 'lick', kisses: 'licks' },
    es: { cama: 'cesta', camas: 'cestas', plato: 'cuenco', platos: 'cuencos', casa: 'caseta', casas: 'casetas', mano: 'pata', manos: 'patas', amigo: 'cachorro', amigos: 'cachorros', regalo: 'hueso', regalos: 'huesos', comida: 'croqueta' },
    de: { Bett: 'Körbchen', Betten: 'Körbchen', Teller: 'Napf', Hand: 'Pfote', Hände: 'Pfoten', Händen: 'Pfoten', Team: 'Rudel', Teams: 'Rudel', Freund: 'Kumpel', Freunde: 'Kumpel', Essen: 'Futter' },
    it: { casa: 'cuccia', case: 'cucce', mano: 'zampa', mani: 'zampe', amico: 'cucciolo', amici: 'cuccioli', regalo: 'osso', regali: 'ossa' },
    pt: { cama: 'caminha', camas: 'caminhas', casa: 'casinha', casas: 'casinhas', mão: 'pata', mãos: 'patas', equipe: 'matilha', equipes: 'matilhas', amigo: 'cãozinho', amigos: 'cãezinhos', presente: 'osso', presentes: 'ossos', comida: 'ração' },
  };
  const lang = ((document.documentElement.getAttribute('lang') || navigator.language || 'fr').slice(0, 2)).toLowerCase();
  const dict = W[lang] || null;
  let RX = null;
  if (dict) {
    const keys = Object.keys(dict).sort((a, b) => b.length - a.length).map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    RX = new RegExp('(?<![\\p{L}\\p{N}_])(' + keys.join('|') + ')(?![\\p{L}\\p{N}_])', lang === 'de' ? 'gu' : 'giu');
  }
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEXTAREA', 'INPUT', 'SELECT', 'OPTION', 'CODE', 'PRE', 'KBD', 'SAMP', 'SVG', 'MATH', 'TITLE', 'IFRAME', 'CANVAS']);
  const orig = new WeakMap(); // nœud texte → texte d'origine
  const touched = new Set();
  const isVowel = (c) => /[aeiouyhâàäéèêëîïôöûùüœ]/i.test(c || '');
  function matchCase(src, rep) {
    if (src === src.toUpperCase() && src !== src.toLowerCase()) return rep.toUpperCase();
    if (src[0] === src[0].toUpperCase() && src[0] !== src[0].toLowerCase()) return rep[0].toUpperCase() + rep.slice(1);
    return rep;
  }
  function skipNode(n) {
    for (let e = n.parentElement; e; e = e.parentElement) {
      if (SKIP.has(e.tagName) || e.isContentEditable || e.tagName === 'FORM' || e.id === 'pup-deco' || e.getAttribute('aria-hidden') === 'true' && e.tagName === 'svg') return true;
      if (e === document.body) break;
    }
    return false;
  }
  function puppify(n) {
    if (!RX || n.nodeType !== 3) return;
    const v = n.nodeValue;
    if (!v || v.length < 2 || !/\p{L}/u.test(v)) return;
    const base = orig.has(n) && n.__pupOut === v ? orig.get(n) : v;
    if (n.__pupOut === v) return; // déjà fait
    RX.lastIndex = 0;
    if (!RX.test(base)) return;
    if (skipNode(n)) return;
    RX.lastIndex = 0;
    let out = base.replace(RX, (m) => {
      const key = lang === 'de' ? m : m.toLowerCase();
      const rep = dict[key] || dict[m];
      return rep ? matchCase(m, rep) : m;
    });
    if (lang === 'fr') {
      // élisions : « l'assiette » → « la gamelle », « l'humain » → « le maître », « mon équipe » → « ma meute », « cet humain » → « ce maître »
      out = out.replace(/(^|[\s(«"])([lL])[’'](gamelles?|meutes?)(?![\p{L}])/gu, (m0, a, l, w) => a + (l === 'L' ? 'L' : 'l') + (w.endsWith('s') ? 'es ' : 'a ') + w)
        .replace(/(^|[\s(«"])([lL])[’'](maîtres?)(?![\p{L}])/gu, (m0, a, l, w) => a + (l === 'L' ? 'L' : 'l') + 'e ' + w)
        .replace(/(^|[\s(«"])([dD])[’'](gamelles?|meutes?|maîtres?)(?![\p{L}])/gu, (m0, a, d, w) => a + d + 'e ' + w)
        .replace(/(^|[\s(«"])([mtsMTS])on (gamelles?|meutes?)(?![\p{L}])/gu, (m0, a, c, w) => a + c + 'a ' + w)
        .replace(/(^|[\s(«"])([cC])et (maîtres?|toutous?)(?![\p{L}])/gu, (m0, a, c, w) => a + c + 'e ' + w);
    }
    if (out === base) return;
    orig.set(n, base);
    n.__pupOut = out;
    n.nodeValue = out;
    touched.add(n);
  }
  function walk(root) {
    if (!CFG.words || !RX || !root) return;
    const tw = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let n, c = 0;
    while ((n = tw.nextNode())) { puppify(n); if (++c > 6000) break; }
    paintHighlights();
  }
  // soulignement discret des mots remplacés (API Highlight : aucun élément ajouté dans la page)
  const VALRX = dict ? new RegExp('(?<![\\p{L}])(' + Array.from(new Set(Object.values(dict))).sort((a, b) => b.length - a.length).map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')(?![\\p{L}])', 'giu') : null;
  function paintHighlights() {
    if (!VALRX || !window.CSS || !CSS.highlights || typeof Highlight === 'undefined') return;
    const h = new Highlight();
    let k = 0;
    for (const n of touched) {
      if (!n.isConnected || n.nodeValue !== n.__pupOut) { touched.delete(n); continue; }
      const re = VALRX; re.lastIndex = 0;
      let m;
      while ((m = re.exec(n.nodeValue)) && k < 3000) { const r = new Range(); r.setStart(n, m.index); r.setEnd(n, m.index + m[0].length); h.add(r); k++; }
    }
    CSS.highlights.set('pupword', h);
  }
  function revertWords() {
    for (const n of touched) if (n.isConnected && n.nodeValue === n.__pupOut) { n.nodeValue = orig.get(n); n.__pupOut = null; }
    touched.clear();
    if (window.CSS && CSS.highlights) CSS.highlights.delete('pupword');
  }
  // appui sur un mot puppy → bulle avec le mot d'origine (sans bloquer le clic du site)
  addEventListener('pointerup', (e) => {
    if (!CFG.words || !touched.size || !document.caretRangeFromPoint) return;
    const r = document.caretRangeFromPoint(e.clientX, e.clientY);
    if (!r || !touched.has(r.startContainer)) return;
    const n = r.startContainer, o = orig.get(n);
    if (!o) return;
    const s = n.nodeValue, i = r.startOffset;
    let a = i, b = i; while (a > 0 && /\p{L}/u.test(s[a - 1])) a--; while (b < s.length && /\p{L}/u.test(s[b])) b++;
    const word = s.slice(a, b);
    if (!word || !Object.values(dict).some((x) => x.toLowerCase() === word.toLowerCase())) return;
    // retrouve le mot d'origine à la même position approximative
    const words = o.match(RX) || [];
    const before = (s.slice(0, a).match(new RegExp('(?<![\\p{L}])(' + Object.values(dict).map((x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')(?![\\p{L}])', 'giu')) || []).length;
    const was = words[before] || '';
    if (!was) return;
    tip(e.clientX, e.clientY, `🐾 ${word} = « ${was} »`);
  }, { passive: true, capture: true });
  function tip(x, y, text) {
    const t = document.createElement('div');
    t.textContent = text;
    t.style.cssText = `position:fixed;left:${Math.max(8, Math.min(innerWidth - 220, x - 100))}px;top:${Math.max(8, y - 54)}px;z-index:2147483647;pointer-events:none;max-width:220px;padding:7px 12px;border-radius:12px;font:600 13px system-ui,sans-serif;color:#fff;background:linear-gradient(180deg,rgba(255,255,255,.35),rgba(255,255,255,.05) 50%,rgba(0,0,0,.1) 51%),#7a1650;border:1px solid #fff;box-shadow:0 0 18px #ff3fa4`;
    document.documentElement.appendChild(t);
    setTimeout(() => t.remove(), 2200);
  }
  const hlStyle = document.createElement('style');
  hlStyle.textContent = '::highlight(pupword){text-decoration:underline dotted #ff3fa4;text-decoration-thickness:1.5px;text-underline-offset:3px}';
  (document.head || document.documentElement).appendChild(hlStyle);

  // ============================================================== OBSERVATION
  let pending = new Set(), timer = 0, scanTimer = 0;
  const mo = new MutationObserver((ms) => {
    for (const m of ms) {
      if (m.target && m.target.id === 'pup-deco' || (m.target.parentElement && m.target.parentElement.id === 'pup-deco')) continue;
      if (m.type === 'characterData') { if (m.target.nodeValue !== m.target.__pupOut) pending.add(m.target); }
      else m.addedNodes.forEach((a) => { if (a.id !== 'pup-deco') pending.add(a); });
    }
    if (!timer) timer = setTimeout(flush, 350);
  });
  function flush() {
    timer = 0;
    const list = Array.from(pending); pending.clear();
    if (CFG.words) for (const n of list) { if (n.nodeType === 3) { orig.delete(n); n.__pupOut = null; puppify(n); } else if (n.nodeType === 1) walk(n); }
    if (CFG.words) paintHighlights();
    clearTimeout(scanTimer); scanTimer = setTimeout(scan, 120);
  }
  function start() {
    if (!document.body) { setTimeout(start, 200); return; }
    mo.observe(document.body, { childList: true, subtree: true, characterData: true });
    if (CFG.words) walk(document.body);
    scan();
    setInterval(() => { if (!document.hidden) scan(); }, 1500);
  }
  start();

  window.__pupDeco = {
    cfg(c) {
      const wasW = CFG.words, wasD = CFG.deco;
      Object.assign(CFG, c || {});
      if (wasW && !CFG.words) revertWords();
      if (!wasW && CFG.words) walk(document.body);
      if (wasD && !CFG.deco) { decos.forEach((d) => d.parts.forEach((x) => x.img.remove())); decos = []; }
      if (!wasD && CFG.deco) scan();
    },
    off() { this.cfg({ deco: false, words: false }); },
  };
})();
