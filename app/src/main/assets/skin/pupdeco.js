/* PupDeco — habillage puppyplay local (PuppyInternet). Purement visuel :
   - accessoires de chiots dessinés par-dessus les logos et les boutons J'aime (calque séparé, jamais cliquable) ;
   - vocabulaire puppy (lit → panier…) en remplaçant seulement le texte affiché, jamais dans les champs ni les formulaires.
   Rien n'est envoyé au site, aucune fonction n'est modifiée. */
(function () {
  'use strict';
  if (window.__pupDeco) { window.__pupDeco.cfg(window.__pupCfg || {}); return; }
  const CFG = Object.assign({ deco: true, words: true }, window.__pupCfg || {});
  const host = location.hostname.replace(/^(www|m|mobile)\./, '');

  // ============================================================== DESSINS (originaux, style skeuomorphique puppyplay)
  // Volume 3D (dégradés radiaux), reflets verre Vista, mèches de poils, coussinets brillants, liseré néon rose.
  const DEFS = `
    <radialGradient id="fur" cx=".38" cy=".3" r=".85"><stop offset="0" stop-color="#fff6e8"/><stop offset=".45" stop-color="#f6cf9a"/><stop offset=".8" stop-color="#d9934f"/><stop offset="1" stop-color="#9c5a24"/></radialGradient>
    <radialGradient id="furD" cx=".4" cy=".25" r=".9"><stop offset="0" stop-color="#d98c4a"/><stop offset=".6" stop-color="#9a531f"/><stop offset="1" stop-color="#5a2c0c"/></radialGradient>
    <radialGradient id="muz" cx=".45" cy=".35" r=".75"><stop offset="0" stop-color="#ffffff"/><stop offset=".7" stop-color="#fff0dc"/><stop offset="1" stop-color="#e8c49a"/></radialGradient>
    <radialGradient id="eye" cx=".4" cy=".35" r=".7"><stop offset="0" stop-color="#5a3a8a"/><stop offset=".55" stop-color="#1e0e36"/><stop offset="1" stop-color="#05020c"/></radialGradient>
    <radialGradient id="nose" cx=".35" cy=".3" r=".8"><stop offset="0" stop-color="#6a5070"/><stop offset=".5" stop-color="#241530"/><stop offset="1" stop-color="#05020a"/></radialGradient>
    <radialGradient id="bean" cx=".35" cy=".3" r=".8"><stop offset="0" stop-color="#ffd0e6"/><stop offset=".6" stop-color="#ff7ab8"/><stop offset="1" stop-color="#c2185b"/></radialGradient>
    <radialGradient id="cheek" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#ff7ab8" stop-opacity=".75"/><stop offset="1" stop-color="#ff7ab8" stop-opacity="0"/></radialGradient>
    <linearGradient id="gloss" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".85"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
    <linearGradient id="neonC" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#ff3fa4"/><stop offset=".5" stop-color="#ff8fd0"/><stop offset="1" stop-color="#ff3fa4"/></linearGradient>
    <linearGradient id="gold" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff6c9"/><stop offset=".45" stop-color="#ffd34d"/><stop offset=".55" stop-color="#d99a10"/><stop offset="1" stop-color="#ffe9a0"/></linearGradient>
    <linearGradient id="boneG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset=".5" stop-color="#fff1f7"/><stop offset=".52" stop-color="#f2c9dc"/><stop offset="1" stop-color="#fbe3ee"/></linearGradient>
    <radialGradient id="orbP" cx=".5" cy=".3" r=".75"><stop offset="0" stop-color="#ffb3d9"/><stop offset=".5" stop-color="#ff3fa4"/><stop offset="1" stop-color="#7a0a44"/></radialGradient>
    <filter id="sh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="2" stdDeviation="1.6" flood-color="#000" flood-opacity=".55"/><feDropShadow dx="0" dy="0" stdDeviation="2.2" flood-color="#ff3fa4" flood-opacity=".75"/></filter>
    <filter id="soft"><feGaussianBlur stdDeviation=".6"/></filter>`;
  const svg = (vb, body) => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}"><defs>${DEFS}</defs><g filter="url(#sh)">${body}</g></svg>`);
  // mèches de poils (traits fins clairs/foncés)
  const tufts = (cx, cy, rx, ry, n, col) => { let d = ''; for (let i = 0; i < n; i++) { const a = -Math.PI * (.15 + .7 * i / Math.max(1, n - 1)); const x = cx + Math.cos(a) * rx, y = cy + Math.sin(a) * ry; d += `M${x.toFixed(1)} ${y.toFixed(1)}l${(Math.cos(a) * 3.2).toFixed(1)} ${(Math.sin(a) * 3.2 - 1).toFixed(1)}`; } return `<path d="${d}" stroke="${col}" stroke-width="1.3" stroke-linecap="round" fill="none" opacity=".7"/>`; };
  const ear = (side) => side < 0
    ? `<path d="M30 22C14 20 6 40 11 58c3 11 15 11 18 2l6-26z" fill="url(#furD)" stroke="#4a2208" stroke-width=".8"/><path d="M27 28C18 30 14 42 16 54" stroke="#ffb3d6" stroke-width="3" fill="none" opacity=".45" stroke-linecap="round"/>`
    : `<path d="M70 22c16-2 24 18 19 36-3 11-15 11-18 2l-6-26z" fill="url(#furD)" stroke="#4a2208" stroke-width=".8"/><path d="M73 28c9 2 13 14 11 26" stroke="#ffb3d6" stroke-width="3" fill="none" opacity=".45" stroke-linecap="round"/>`;
  const face = (mood) => {
    const sad = mood === 'sad';
    const eyes = sad
      ? `<path d="M35 36q7-5 13-1M52 35q6-4 13 1" stroke="#3a1a0c" stroke-width="2.6" fill="none" stroke-linecap="round"/>
         <ellipse cx="42" cy="44" rx="5.5" ry="6.5" fill="url(#eye)"/><ellipse cx="58" cy="44" rx="5.5" ry="6.5" fill="url(#eye)"/>
         <circle cx="43.6" cy="41.5" r="2.2" fill="#fff"/><circle cx="59.6" cy="41.5" r="2.2" fill="#fff"/><path d="M38 50q1 5 3 7" stroke="#7fdcff" stroke-width="2" fill="none" stroke-linecap="round" opacity=".85"/>`
      : `<ellipse cx="42" cy="42" rx="6.2" ry="7.4" fill="url(#eye)"/><ellipse cx="58" cy="42" rx="6.2" ry="7.4" fill="url(#eye)"/>
         <circle cx="44" cy="39" r="2.6" fill="#fff"/><circle cx="60" cy="39" r="2.6" fill="#fff"/><circle cx="40.5" cy="45.5" r="1.2" fill="#fff" opacity=".8"/><circle cx="56.5" cy="45.5" r="1.2" fill="#fff" opacity=".8"/>
         <path d="M36 33q6-4 11-1M53 32q6-3 11 1" stroke="#7a3d12" stroke-width="1.6" fill="none" stroke-linecap="round" opacity=".7"/>`;
    const mouth = sad
      ? `<path d="M43 63q7-5 14 0" stroke="#3a1a0c" stroke-width="2.4" fill="none" stroke-linecap="round"/>`
      : `<path d="M50 56v4M50 60q-5 6-10 1.5M50 60q5 6 10 1.5" stroke="#3a1a0c" stroke-width="2.3" fill="none" stroke-linecap="round"/>
         <path d="M46.5 63q3.5 11 7 0z" fill="#ff5e9e" stroke="#c2185b" stroke-width=".8"/><path d="M50 63.5v5" stroke="#c2185b" stroke-width=".8"/><ellipse cx="48.6" cy="65" rx="1" ry="1.6" fill="#fff" opacity=".6"/>`;
    return `${ear(-1)}${ear(1)}
      <ellipse cx="50" cy="45" rx="29" ry="26" fill="url(#fur)" stroke="#7a3d12" stroke-width=".8"/>
      ${tufts(50, 45, 27, 25, 9, '#fff6e8')}
      <path d="M40 22q10-6 20 0q-4 8-10 8t-10-8z" fill="#d9934f" opacity=".55"/>
      <ellipse cx="50" cy="57" rx="16" ry="12" fill="url(#muz)"/>
      <ellipse cx="34" cy="53" rx="6" ry="4" fill="url(#cheek)"/><ellipse cx="66" cy="53" rx="6" ry="4" fill="url(#cheek)"/>
      ${eyes}
      <ellipse cx="50" cy="53.5" rx="6.2" ry="4.6" fill="url(#nose)"/><ellipse cx="48" cy="51.8" rx="2.4" ry="1.2" fill="#fff" opacity=".75"/>
      ${mouth}
      <ellipse cx="42" cy="28" rx="16" ry="7" fill="url(#gloss)" opacity=".55" transform="rotate(-12 42 28)"/>`;
  };
  // patte avec coussinets roses brillants
  const paw = (x, y, r) => `<g transform="translate(${x} ${y}) scale(${r})">
      <ellipse cx="0" cy="0" rx="12" ry="10" fill="url(#fur)" stroke="#7a3d12" stroke-width=".9"/>
      <ellipse cx="0" cy="2.5" rx="5" ry="3.8" fill="url(#bean)"/>
      <ellipse cx="-6.5" cy="-3" rx="2.4" ry="2.7" fill="url(#bean)"/><ellipse cx="-2.2" cy="-6" rx="2.4" ry="2.7" fill="url(#bean)"/><ellipse cx="2.2" cy="-6" rx="2.4" ry="2.7" fill="url(#bean)"/><ellipse cx="6.5" cy="-3" rx="2.4" ry="2.7" fill="url(#bean)"/>
      <ellipse cx="-3" cy="-5" rx="6" ry="2.6" fill="url(#gloss)" opacity=".5"/></g>`;
  const collarBand = `<rect x="2" y="4" width="96" height="11" rx="5.5" fill="url(#neonC)" stroke="#fff" stroke-width="1.4"/>
      <rect x="6" y="5.2" width="88" height="3.4" rx="1.7" fill="url(#gloss)" opacity=".7"/>
      ${[16, 30, 70, 84].map((x) => `<circle cx="${x}" cy="9.5" r="1.9" fill="#fff"/><circle cx="${x - .5}" cy="9" r=".7" fill="#ffd0e6"/>`).join('')}
      <circle cx="50" cy="16" r="6" fill="url(#gold)" stroke="#8a5a00" stroke-width="1.2"/><path d="M47 15.5h6M50 13v5" stroke="#8a5a00" stroke-width="1" opacity=".6"/><ellipse cx="48" cy="13.6" rx="2" ry="1" fill="#fff" opacity=".8"/>`;
  const ART = {
    peek: svg('0 0 100 80', face('happy') + paw(30, 72, 1) + paw(70, 72, 1)),
    hugHead: svg('0 0 100 80', face('happy') + paw(26, 72, .9) + paw(74, 72, .9)),
    hugArm: svg('-4 -4 50 50', `<path d="M5 7c15 0 28 11 28 24" stroke="#7a3d12" stroke-width="14" fill="none" stroke-linecap="round"/><path d="M5 7c15 0 28 11 28 24" stroke="url(#fur)" stroke-width="12" fill="none" stroke-linecap="round"/><path d="M8 6c10 1 18 6 22 13" stroke="#fff6e8" stroke-width="3" fill="none" stroke-linecap="round" opacity=".6"/>` + paw(33, 33, .85)),
    earL: svg('0 0 40 56', `<path d="M31 6C12 3 1 26 7 46c4 12 16 9 17-2L30 16z" fill="url(#furD)" stroke="#4a2208" stroke-width=".9"/><path d="M27 12C15 13 9 28 12 42" stroke="#ffb3d6" stroke-width="4" fill="none" opacity=".45" stroke-linecap="round"/><path d="M24 9C16 10 11 18 10 26" stroke="#fff" stroke-width="2" fill="none" opacity=".45" stroke-linecap="round"/>`),
    earR: svg('0 0 40 56', `<path d="M9 6c19-3 30 20 24 40-4 12-16 9-17-2L10 16z" fill="url(#furD)" stroke="#4a2208" stroke-width=".9"/><path d="M13 12c12 1 18 16 15 30" stroke="#ffb3d6" stroke-width="4" fill="none" opacity=".45" stroke-linecap="round"/><path d="M16 9c8 1 13 9 14 17" stroke="#fff" stroke-width="2" fill="none" opacity=".45" stroke-linecap="round"/>`),
    collar: svg('0 0 100 24', collarBand),
    pawL: svg('0 0 30 30', paw(15, 15, 1.1)),
    pawR: svg('0 0 30 30', paw(15, 15, 1.1)),
    likeHead: svg('0 0 100 80', face('happy')),
    sadHead: svg('0 0 100 80', face('sad')),
    bone: svg('0 0 64 32', `<g stroke="#ff3fa4" stroke-width="2"><rect x="15" y="10.5" width="34" height="11" rx="5.5" fill="url(#boneG)"/><circle cx="14" cy="10.5" r="7.5" fill="url(#boneG)"/><circle cx="14" cy="21.5" r="7.5" fill="url(#boneG)"/><circle cx="50" cy="10.5" r="7.5" fill="url(#boneG)"/><circle cx="50" cy="21.5" r="7.5" fill="url(#boneG)"/></g><rect x="16" y="12" width="32" height="8" fill="url(#boneG)"/><ellipse cx="30" cy="12.5" rx="14" ry="2" fill="#fff" opacity=".9"/>`),
    stickPaw: svg('0 0 44 44', `<circle cx="22" cy="22" r="19" fill="url(#orbP)" stroke="#fff" stroke-width="2.6"/><g fill="#fff"><ellipse cx="22" cy="26" rx="7.5" ry="6.2"/><ellipse cx="13.5" cy="18" rx="3.1" ry="3.5"/><ellipse cx="18.5" cy="12.5" rx="3.1" ry="3.5"/><ellipse cx="25.5" cy="12.5" rx="3.1" ry="3.5"/><ellipse cx="30.5" cy="18" rx="3.1" ry="3.5"/></g><ellipse cx="22" cy="11" rx="13" ry="6" fill="url(#gloss)" opacity=".75"/>`),
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
