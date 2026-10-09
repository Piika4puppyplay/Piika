/* Ta niche — interface */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const I = PupIcons.icon;
  const ic = (g, c, sh) => I(g, c, sh ? { shape: sh } : undefined);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const PEL = [['auto', 'Chacun son pelage', '#ff3fa4', '#29e6ff', '#0b0614'], ['rose', 'Néon Rose', '#ff3fa4', '#29e6ff', '#0b0614'], ['cyan', 'Laser Turquoise', '#29e6ff', '#ff3fa4', '#030b12'], ['violet', 'Violet Velours', '#9b5cff', '#29e6ff', '#0b0418'], ['cuir', 'Cuir & Rivets', '#ff3fa4', '#29e6ff', '#120a10'], ['or', 'Or Royal', '#ffb627', '#ff3fa4', '#0e0802'], ['vert', 'Vert Fluo', '#3dffb0', '#9b5cff', '#020e0a']]
    .map(([id, nom, acc, acc2, bg]) => ({ id, nom, acc, acc2, bg }));
  const N = window.Niche || {
    info: () => JSON.stringify({ pelage: 'rose', pelages: PEL, lastBackup: Date.now() - 864e5 * 3, lastBackupName: 'niche_2026-10-06_21h14.puppy', live: false, liveId: 'aurore', siesteLum: 0, siesteDuree: 60, version: 24, sons: true, sonsVol: 60, sonsNav: true, sonsClavier: false, sonsCharge: true, sonsVerrou: false, silent: false, verrou: false, verrouCadre: true, verrouPattes: true, verrouChiot: true, verrouEtoiles: true, verrouCharge: true, verrouForce: 1, a11y: true, veille: false, siesteCharge: false, veilleStyle: 'verre', veilleQuand: 0, veilleDuree: 30, veilleLum: 0 }),
    pelage() {}, backup() { const st = ['Réglages des Pup-apps…', 'Fichiers de la niche…', 'Mémoire des pages (playlists, favoris…)…', 'Rangement dans Téléchargements…']; st.forEach((m, i) => setTimeout(() => NicheUI.on('backup', JSON.stringify({ st: 'step', msg: m })), 500 * (i + 1))); setTimeout(() => NicheUI.on('backup', JSON.stringify({ st: 'done', name: 'niche_2026-10-09_02h10.puppy' })), 2600); },
    pickRestore() { setTimeout(() => NicheUI.on('restore', JSON.stringify({ st: 'ready', man: { date: Date.now() - 864e5, device: 'samsung SM-G986B', version: 23 } })), 500); }, cancelRestore() {}, restoreNow() {}, setStr() {}, veillePreview() {}, aodSave: (st) => 'pup_aod_' + st + '.gif', openAod() {}, lockToo: () => true, setInt() {}, decoPreview: () => true, openA11y() {}, wallpaper() {}, siestePreview() {}, dreamSettings() {}, set() {}, close() {},
  };
  const nc = (fn, ...a) => { try { return N[fn] ? N[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };
  const fmtD = (t) => t ? new Date(t).toLocaleString('fr-FR', { weekday: 'short', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }) : 'jamais';
  const ago = (t) => { if (!t) return ''; const d = (Date.now() - t) / 864e5; return d < 1 ? "aujourd'hui" : d < 2 ? 'hier' : `il y a ${Math.floor(d)} jours`; };
  const PAW = '<svg viewBox="0 0 24 24"><ellipse cx="12" cy="16" rx="5.4" ry="4.6"/><circle cx="5.6" cy="10.4" r="2.3"/><circle cx="9.4" cy="6.4" r="2.3"/><circle cx="14.6" cy="6.4" r="2.3"/><circle cx="18.4" cy="10.4" r="2.3"/></svg>';

  let S = J(nc('info'), {}) || {};
  let bk = { st: 'idle', steps: [], name: '' };

  function toast(msg) {
    let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 3200);
  }

  // terrier avec os, pelle et monticule de terre (dessin original)
  const DIG = `<svg viewBox="0 0 320 150" preserveAspectRatio="xMidYMax meet"><defs>
      <radialGradient id="dg" cx=".5" cy=".2" r=".9"><stop offset="0" stop-color="#8a5a34"/><stop offset=".6" stop-color="#4a2a14"/><stop offset="1" stop-color="#1e0e06"/></radialGradient>
      <linearGradient id="bn" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff"/><stop offset=".6" stop-color="#f3e6d6"/><stop offset="1" stop-color="#c9b39a"/></linearGradient>
      <linearGradient id="mt" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#6b6480"/><stop offset=".4" stop-color="#fbf9ff"/><stop offset="1" stop-color="#7a7290"/></linearGradient></defs>
    <ellipse cx="160" cy="150" rx="190" ry="30" fill="#0a0410"/>
    <path d="M60 150Q90 92 160 88T262 150z" fill="url(#dg)"/>
    <path d="M60 150Q90 92 160 88T262 150" fill="none" stroke="rgba(255,200,150,.35)" stroke-width="2"/>
    <g fill="#2a160a"><circle cx="110" cy="128" r="4"/><circle cx="150" cy="112" r="3"/><circle cx="200" cy="124" r="5"/><circle cx="178" cy="138" r="3"/><circle cx="128" cy="140" r="3"/></g>
    <g class="bone"><g transform="translate(160 104)"><rect x="-30" y="-7" width="60" height="14" rx="7" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/>
      <circle cx="-30" cy="-7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/><circle cx="-30" cy="7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/>
      <circle cx="30" cy="-7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/><circle cx="30" cy="7" r="9" fill="url(#bn)" stroke="#7a5a3a" stroke-width="1.5"/>
      <rect x="-28" y="-3" width="56" height="3" rx="1.5" fill="#fff" opacity=".8"/></g></g>
    <path d="M60 150Q100 118 160 116T262 150z" fill="url(#dg)" opacity=".92"/>
    <g class="shovel"><rect x="246" y="10" width="7" height="70" rx="3" fill="#a0632c" stroke="#3a1a08"/><rect x="238" y="4" width="23" height="8" rx="4" fill="#5c3412" stroke="#2a1206"/>
      <path d="M236 80h27l-3 26q-10 10-21 0z" fill="url(#mt)" stroke="#2a2438" stroke-width="1.5"/></g>
    <g class="dirtfly" fill="#6a4020"><circle cx="230" cy="100" r="4"/><circle cx="222" cy="92" r="3"/><circle cx="238" cy="94" r="2.5"/></g>
    <g transform="translate(26 70)"><ellipse cx="22" cy="58" rx="22" ry="5" fill="#000" opacity=".5"/>
      <path d="M8 10C0 10-3 26 2 36c3 6 10 5 11-1l3-18z" fill="#9c5a24"/><path d="M36 10c8 0 11 16 6 26-3 6-10 5-11-1l-3-18z" fill="#9c5a24"/>
      <ellipse cx="22" cy="26" rx="16" ry="15" fill="#f6cf9a"/><ellipse cx="22" cy="33" rx="9" ry="7" fill="#fff6ea"/>
      <ellipse cx="16" cy="24" rx="3" ry="3.6" fill="#1e0e36"/><ellipse cx="28" cy="24" rx="3" ry="3.6" fill="#1e0e36"/><circle cx="17" cy="22.6" r="1.2" fill="#fff"/><circle cx="29" cy="22.6" r="1.2" fill="#fff"/>
      <ellipse cx="22" cy="30.5" rx="3.6" ry="2.6" fill="#1a0b2e"/><path d="M19.5 34.5q2.5 6 5 0z" fill="#ff5e9e"/>
      <path d="M8 42q14 6 28 0l2 14H6z" fill="#f6cf9a"/><ellipse cx="12" cy="56" rx="6" ry="3.6" fill="#fff6ea"/><ellipse cx="32" cy="56" rx="6" ry="3.6" fill="#fff6ea"/>
      <path d="M6 41q16 7 32 0" stroke="${'#ff3fa4'}" stroke-width="4" fill="none"/><circle cx="22" cy="46" r="3" fill="#ffd34d" stroke="#8a5a00"/></g>
  </svg>`;


  const SONS = [['tap', '🐾', 'Patte', 'toucher'], ['pouic', '🦆', 'Pouic', 'interrupteurs'], ['jappe', '🐶', 'Jappement', 'accueil'], ['wouf', '🐕', 'Wouf', ''], ['wouf2', '🐕‍🦺', 'Wouf wouf', 'charge pleine'],
    ['croc', '🦴', 'Croquette', 'supprimer'], ['grelot', '🔔', 'Grelot', 'réussite'], ['couine', '🥺', 'Couine', 'batterie faible'], ['halete', '😛', 'Halète', 'branché'], ['ouvre', '💨', 'Swouf', 'PupTasks']];
  const sw = (k, on, dis) => `<button class="sw${on ? ' on' : ''}" data-sw="${k}" type="button"${dis ? ' disabled' : ''}><i></i></button>`;
  const row = (k, t, sub, dis) => `<div class="row${dis ? ' dim' : ''}"><span>${t}<small>${sub}</small></span>${sw(k, S[k], dis)}</div>`;
  function renderSons() {
    const off = !S.sons;
    $('#s-sons').innerHTML = `<section class="ncard">
      <h2>${ic('speaker', 'orange')}La boîte à couinements<em>${S.sons ? 'Allumée' : 'Éteinte'}</em></h2>
      <p class="hint">Des petits bruits de chiot <b>100 % originaux</b> (fabriqués par calcul, aucun vrai chien n'a été dérangé 🐶) pour les Pup-apps, la barre PupNav, le clavier et la charge. Touche une balle pour l'écouter.</p>
      <div class="toybox">${SONS.map(([n, e, l, u]) => `<button class="toy" data-son="${n}" data-nosound type="button"><span class="ball">${e}</span><b>${l}</b>${u ? `<small>${u}</small>` : ''}</button>`).join('')}</div>
      ${S.silent ? '<p class="hint">🔕 Ton téléphone est en <b>silencieux / vibreur</b> : la boîte se tait toute seule tant qu\'il l\'est.</p>' : ''}
      ${row('sons', 'Boîte à couinements', 'Interrupteur général : éteins-le et tout redevient silencieux comme avant')}
      <div class="row${off ? ' dim' : ''}"><span>Volume<small>${S.sonsVol} %</small></span></div>
      <input type="range" min="10" max="100" step="5" value="${S.sonsVol}" id="svol" style="--v:${(S.sonsVol - 10) / .9}%" ${off ? 'disabled' : ''}>
      ${row('sonsNav', 'Barre PupNav', 'Patte pour Retour, jappement pour la Niche, swouf pour PupTasks', off)}
      ${row('sonsCharge', 'Charge', 'Halètement en branchant, pouic en débranchant, wouf wouf à 100 %, couinement à 15 %', off)}
      ${row('sonsClavier', 'PupKeyboard', 'Pattes sur les touches, croquette pour effacer (remplace le son du clavier)', off)}
      ${row('sonsVerrou', 'Déverrouillage', 'Un petit jappement quand tu ouvres le téléphone', off)}
    </section>`;
  }

  const PAWS = (n, cls) => Array.from({ length: n }, (_, i) => `<i class="lp ${cls}" style="--i:${i}">${PAW}</i>`).join('');
  function renderVerrou() {
    const off = !S.verrou, F = ['Discret', 'Normal', 'Éclatant'];
    const now = new Date(), hh = String(now.getHours()).padStart(2, '0'), mm = String(now.getMinutes()).padStart(2, '0');
    $('#s-verrou').innerHTML = `<section class="ncard">
      <h2>${ic('lock', 'cyan')}La déco du verrou<em>${S.verrou ? 'Posée' : 'Test · éteinte'}</em></h2>
      <div class="lockprev${off ? ' off' : ''}" style="--f:${[.42, .7, 1][S.verrouForce || 0]}">
        <div class="lscreen">
          <div class="lclock"><b>${hh}:${mm}</b><small>${esc(now.toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' }))}</small></div>
          <div class="lnotif"></div><div class="lnotif s"></div>
          <i class="lfinger"></i><i class="lsc l"></i><i class="lsc r"></i>
          ${S.verrouCadre ? '<i class="lframe"></i>' : ''}
          ${S.verrouPattes ? `<div class="ltrail a">${PAWS(5, '')}</div><div class="ltrail b">${PAWS(5, '')}</div>` : ''}
          ${S.verrouEtoiles ? '<i class="lstar s1"></i><i class="lstar s2"></i><i class="lstar s3"></i><i class="lstar s4"></i>' : ''}
          ${S.verrouChiot ? `<div class="lpup">${PEEK}</div>` : ''}
        </div>
        <span class="phone">${off ? 'ÉTEINTE · VERROU ONE UI NORMAL' : 'ÉCRAN DE VERROUILLAGE'}</span>
      </div>
      <p class="hint">Des petites touches puppy posées <b>par-dessus</b> l'écran de verrouillage de One UI, qui reste <b>100 % intact</b> : code, empreinte, raccourcis et notifications marchent comme avant, la déco ne capte aucun toucher. Elle n'apparaît que quand l'écran de verrouillage est allumé (jamais écran éteint ni pendant la sieste) et bouge un peu à chaque allumage pour ne pas marquer l'écran.</p>
      ${!S.a11y ? `<div class="warnbox">⚠️ La déco passe par le service <b>PupNav</b> (accessibilité), comme la barre. Il n'est pas activé pour l'instant.<button class="ab small amber" data-a="a11y" type="button">Ouvrir l'accessibilité</button></div>` : ''}
      ${row('verrou', 'Décorer l\'écran de verrouillage', 'Éteins-le et l\'écran de verrouillage redevient exactement comme avant')}
      ${row('verrouCadre', 'Cadre néon', 'Un liseré lumineux qui suit les coins de l\'écran', off)}
      ${row('verrouPattes', 'Traces de pattes', 'Des pattes qui marchent le long des bords', off)}
      ${row('verrouChiot', 'Chiot qui pointe le museau', 'Il se penche depuis le bord droit et cligne des yeux', off)}
      ${row('verrouEtoiles', 'Étoiles', 'Quelques scintillements dans les coins du haut', off)}
      ${row('verrouCharge', 'Cacher pendant la charge', 'Laisse l\'écran de charge One UI tout seul quand le téléphone est branché', off)}
      <div class="seg${off ? ' dim' : ''}" id="vforce">${F.map((f, i) => `<button type="button" data-force="${i}" class="${(S.verrouForce || 0) === i ? 'on' : ''}"${off ? ' disabled' : ''}>${f}</button>`).join('')}</div>
      <button class="ab wide c2" data-a="decoprev" type="button" ${off ? 'disabled' : ''}>${ic('eye', 'chrome', 'none')}Aperçu 7 secondes sur l'écran</button>
    </section>`;
  }
  // chiot qui se penche (dessin original)
  const PEEK = `<svg viewBox="0 0 100 100"><defs><radialGradient id="pk" cx=".38" cy=".3" r=".85"><stop offset="0" stop-color="#fff6e8"/><stop offset=".45" stop-color="#f6cf9a"/><stop offset=".8" stop-color="#d9934f"/><stop offset="1" stop-color="#9c5a24"/></radialGradient><radialGradient id="pe" cx=".4" cy=".25" r=".9"><stop offset="0" stop-color="#d98c4a"/><stop offset="1" stop-color="#5a2c0c"/></radialGradient></defs>
    <circle cx="50" cy="50" r="48" fill="var(--acc)" opacity=".18"/>
    <path d="M30 26C14 24 6 44 11 62c3 11 15 11 18 2l6-26z" fill="url(#pe)"/><path d="M70 26c16-2 24 18 19 36-3 11-15 11-18 2l-6-26z" fill="url(#pe)"/>
    <ellipse cx="50" cy="49" rx="29" ry="26" fill="url(#pk)"/><ellipse cx="50" cy="61" rx="16" ry="12" fill="#fff6ea"/>
    <g class="eyes"><ellipse cx="41" cy="46" rx="5.5" ry="6.6" fill="#1e0e36"/><ellipse cx="59" cy="46" rx="5.5" ry="6.6" fill="#1e0e36"/><circle cx="43" cy="43.5" r="2.2" fill="#fff"/><circle cx="61" cy="43.5" r="2.2" fill="#fff"/></g>
    <ellipse cx="50" cy="57" rx="6" ry="4.4" fill="#1a0b2e"/><path d="M50 61v3M50 64q-5 6-9 1.5M50 64q5 6 9 1.5" stroke="#3a1a0c" stroke-width="2" fill="none" stroke-linecap="round"/><path d="M47 67q3 9 6 0z" fill="#ff5e9e"/>
    <rect x="28" y="72" width="44" height="7" rx="3.5" fill="#2a1020"/><circle cx="50" cy="83" r="5" fill="var(--acc)" stroke="#fff" stroke-width="1.2"/>
    <ellipse cx="34" cy="90" rx="10" ry="6.5" fill="#fff6ea" stroke="#d9b78a"/><ellipse cx="66" cy="90" rx="10" ry="6.5" fill="#fff6ea" stroke="#d9b78a"/></svg>`;


  const CATS = [['espace', '🚀 Espace'], ['nature', '🌌 Nature'], ['neon', '🌆 Néon'], ['cocon', '🛋️ Cocooning']];
  const WALLS = [
    { id: 'cosmos', cat: 'espace', nom: "Pup dans l'espace", tag: 'PEINT · OLED', txt: "Noir OLED absolu, nébuleuse aux couleurs de ton pelage, des milliers d'étoiles, galaxie spirale, lune cratérisée et planète géante à anneaux. Ton <b>chiot astronaute</b> flotte avec son jetpack chromé (flammes et fumée animées), combinaison cousue, panneau de commande, gants en cuir et casque en verre qui reflète la nébuleuse. Étoiles filantes et antenne qui clignote." },
    { id: 'aurore', cat: 'nature', nom: 'Aurore des pattes', tag: 'PEINT · OLED', txt: "Des rideaux d'<b>aurore boréale</b> qui ondulent au-dessus de trois chaînes de montagnes enneigées, une forêt de sapins, un lac gelé qui reflète l'aurore, de la neige qui tombe… et un chiot en écharpe tricotée assis sur son rocher qui respire doucement en regardant le ciel." },
    { id: 'neon', cat: 'neon', nom: 'Boulevard néon', tag: 'PEINT · PLUIE', txt: "Soleil rétro à tranches, ville aux fenêtres allumées, montagnes en fil de fer, palmiers, enseigne <b>PUPPY</b> en tubes néon qui grésille, sol mouillé qui reflète tout sous la <b>pluie</b>, et un chiot en blouson de cuir et lunettes miroir à côté de son ghetto-blaster chromé." },
    { id: 'lune', cat: 'espace', nom: 'Base lunaire', tag: 'PEINT · OLED', txt: "Sur une Lune cratérisée, ton <b>chiot astronaute</b> au casque à oreilles plante son drapeau patte. Dôme-niche aux fenêtres chaudes, enseigne <b>PUP BASE</b> qui grésille, rover en forme de gamelle, avant-poste aux fenêtres qui s'allument, Terre et planète rose à anneaux. Un chiot qui <b>respire</b> dans sa combinaison." },
    { id: 'foret', cat: 'nature', nom: 'Forêt enchantée', tag: 'PEINT · LUCIOLES', txt: "Deux arbres géants, la lune qui perce le feuillage, une <b>cabane-niche</b> perchée avec échelle de corde, lanternes et guirlande. Champignons lumineux, <b>lucioles</b> aux couleurs de ton pelage, brume… et un chiot qui <b>dort près du feu de camp</b> sur son plaid, avec ses « z » qui s'envolent." },
    { id: 'arcade', cat: 'neon', nom: 'Pup Arcade', tag: 'PEINT · PLUIE', txt: "Façade en briques, enseigne <b>PUP ARCADE</b> à ampoules, os néon du café, cœur OPEN et patte GOOD BOY qui <b>grésille</b>. Bornes d'arcade et machine à pinces pleine de peluches chiots en vitrine, trottoir mouillé qui reflète tout sous la <b>pluie</b>, et un chiot en sweat et masque sous son parapluie néon." },
    { id: 'niche', cat: 'cocon', nom: 'La niche douillette', tag: 'PEINT · COSY', txt: "Mur de briques, parquet ciré, fenêtre sur la ville avec la <b>pluie qui glisse sur la vitre</b>, guirlande lumineuse qui scintille, néon PUPPY, étagère de livres. Un chiot <b>dort en respirant</b> dans son panier en cuir capitonné, avec ses « z » qui s'envolent." },
    { id: 'chalet', cat: 'cocon', nom: 'Chalet sous la neige', tag: 'PEINT · COSY', txt: "Chalet en rondins, grande fenêtre sur les montagnes où <b>la neige tombe</b>, rideaux tricotés, néon cozy. Cheminée en pierre au <b>feu qui crépite</b>, chaussettes suspendues, chocolats chauds, guirlande et bougies. Le chiot <b>dort en respirant</b> sous sa couverture à pattes, sur le tapis tricoté." },
  ];
  let wsel = 'cosmos', wcat = 'espace';
  function renderWall() {
    const W = WALLS.find((w) => w.id === wsel) || WALLS[0], posed = S.liveId || '';
    $('#s-wall').innerHTML = `<section class="ncard">
      <h2>${ic('moon', 'violet')}Fonds d'écran animés<em>${posed ? 'Posé : ' + esc((WALLS.find((w) => w.id === posed) || {}).nom || '') : 'Aucun posé'}</em></h2>
      <div class="seg wcats">${CATS.map(([k, l]) => `<button type="button" data-wcat="${k}" class="${k === wcat ? 'on' : ''}">${l}</button>`).join('')}</div>
      <div class="wbig"><img src="walls/${W.id}.jpg" alt=""><span class="phone">${esc(W.nom.toUpperCase())}</span></div>
      <div class="wgal">${WALLS.filter((w) => w.cat === wcat).map((w) => `<button class="wcard${w.id === wsel ? ' on' : ''}" data-wall="${w.id}" type="button"><span class="wscreen"><img src="walls/${w.id}.jpg" alt=""><i class="wnotch"></i>${w.id === posed ? '<i class="wposed">POSÉ ✓</i>' : ''}</span><b>${esc(w.nom)}</b><small>${w.tag}</small></button>`).join('')}</div>
      <p class="hint">${W.txt}</p>
      <p class="hint">Peint en haute définition, calque par calque (fond, décor, héros), avec des <b>halos aux couleurs de ton pelage</b>, avec de la profondeur quand tu glisses entre les pages (sur One UI et sur l'accueil PuppyPhone). Il se met en pause écran éteint et ralentit en mode économie d'énergie.</p>
      <button class="ab wide violet" data-a="wall" type="button">${ic('sparkle', 'chrome', 'none')}${W.id === posed ? 'Reposer « ' + esc(W.nom) + ' »' : 'Poser « ' + esc(W.nom) + ' »'}</button>
      <button class="ab wide glass" data-a="lock" type="button">${ic('lock', 'chrome', 'none')}Aussi sur l'écran de verrouillage</button>
    </section>`;
  }


  const seg = (key, opts, cur, dis) => `<div class="seg${dis ? ' dim' : ''}">${opts.map(([v, l]) => `<button type="button" data-seg="${key}" data-v="${v}" class="${String(cur) === String(v) ? 'on' : ''}"${dis ? ' disabled' : ''}>${l}</button>`).join('')}</div>`;
  function renderAod() {
    const off = !S.veille, st = S.veilleStyle || 'verre';
    $('#s-aod').innerHTML = `<section class="ncard">
      <h2>${ic('battery', 'green')}Écran éteint<em>${S.veille ? 'Veille puppy ON' : 'AOD'}</em></h2>
      <div class="aodpick">${[['verre', 'Gamelle de verre'], ['os', 'Os lumineux']].map(([k, l]) => `<button type="button" class="aodc${k === st ? ' on' : ''}" data-aod="${k}"><span class="aodscr"><img src="aod/${k}.gif" alt=""></span><b>${l}</b></button>`).join('')}</div>
      <h3 class="subh">1 · Dans l'Always On Display de Samsung</h3>
      <p class="hint">Samsung ne laisse aucune appli dessiner dans son AOD, mais il accepte <b>une image ou un GIF</b>. Je range la batterie choisie (animation de charge qui se remplit du rouge au vert) dans ta <b>Galerie › PupAOD</b>, puis tu la poses toi-même :</p>
      <ol class="howto"><li>Touche <b>Enregistrer le GIF</b>.</li><li>Ouvre <b>Écran de verrouillage › Always On Display</b>.</li><li><b>Style d'horloge</b> → <b>Image / GIF</b> → choisis <b>pup_aod_${st}.gif</b>.</li></ol>
      <p class="hint">Le chiffre ne bouge pas dans un GIF : l'AOD de Samsung affiche le vrai pourcentage à côté pendant la charge.</p>
      <div class="nrow"><button class="ab green" data-a="aodsave" type="button">${ic('download', 'chrome', 'none')}Enregistrer le GIF</button><button class="ab glass" data-a="aodopen" type="button">${ic('gear', 'chrome', 'none')}Réglages AOD</button></div>
      <h3 class="subh">2 · Veille puppy (mon AOD maison)</h3>
      <p class="hint">Quand tu éteins l'écran, PuppyPhone le rallume <b>presque noir</b> par-dessus le verrouillage One UI (code, empreinte : intacts) avec l'horloge et la batterie puppy <b>en direct</b>, puis le rééteint tout seul. Elle consomme plus que l'AOD de Samsung : laisse-la plutôt sur « En charge ».</p>
      ${!S.a11y ? `<div class="warnbox">⚠️ La Veille puppy passe par le service <b>PupNav</b> (accessibilité), comme la barre.<button class="ab small amber" data-a="a11y" type="button">Ouvrir l'accessibilité</button></div>` : ''}
      ${row('veille', 'Veille puppy', 'Éteins-la et l\'écran éteint redevient exactement comme avant')}
      <div class="row${off ? ' dim' : ''}"><span>Quand<small>« Toujours » = à chaque extinction, même débranché</small></span></div>
      ${seg('veilleQuand', [[0, '🔌 En charge'], [1, '🐾 Toujours']], S.veilleQuand || 0, off)}
      <div class="row${off ? ' dim' : ''}"><span>Durée avant de rééteindre</span></div>
      ${seg('veilleDuree', [[10, '10 s'], [30, '30 s'], [60, '1 min'], [300, '5 min']], S.veilleDuree || 30, off)}
      <div class="row${off ? ' dim' : ''}"><span>Luminosité</span></div>
      ${seg('veilleLum', [[0, 'Très sombre'], [1, 'Tamisé'], [2, 'Lumineux']], S.veilleLum || 0, off)}
      <button class="ab wide c2" data-a="veilleprev" type="button">${ic('eye', 'chrome', 'none')}Aperçu 10 secondes</button>
    </section>`;
  }

  function render() {
    const cur = S.pelage || 'auto', pels = S.pelages && S.pelages.length ? S.pelages : PEL;
    const curP = pels.find((p) => p.id === cur) || pels[0];
    const working = bk.st === 'work';
    if (!$('#s-pel')) init();
    $('#s-pel').innerHTML = `
      <section class="ncard">
        <h2>${ic('paw', 'pink')}Le pelage de la niche<em>${esc(curP.nom)}</em></h2>
        <p class="hint">Une seule couleur pour <b>toutes les Pup-apps</b>, le clavier, la barre de navigation, l'habillage des sites, le fond animé et la sieste. Choisis la médaille que ton chiot portera à son collier.</p>
        <div class="collar"></div>
        <div class="medals">${pels.map((p, i) => `<button data-nosound class="medal${p.id === cur ? ' on' : ''}${p.id === 'auto' ? ' auto' : ''}" data-pel="${esc(p.id)}" type="button" style="--a:${p.acc};--b:${p.acc2};--dl:${(i * -0.6).toFixed(1)}s"><i class="ring"></i><span class="disc">${PAW}</span><b>${esc(p.nom)}</b></button>`).join('')}</div>
      </section>`;
    { const md = $('.medals'), on = $('.medal.on'); if (md && on) md.scrollLeft = on.offsetLeft - (md.clientWidth - on.clientWidth) / 2; }
    renderSons(); renderVerrou(); renderAod();
    $('#s-bone').innerHTML = `      <section class="ncard">
        <h2>${ic('bone', 'amber')}La cachette à os<em>Sauvegarde</em></h2>
        <div class="dig${working ? ' work' : ''}${bk.st === 'done' ? ' down' : ''}" id="dig">${DIG}</div>
        <p class="hint">Enterre un os pour mettre <b>toute la niche à l'abri</b> : réglages des Pup-apps, clavier, barre, pelage, playlists, favoris, habillage des sites et chien de garde. Le fichier <b>.puppy</b> atterrit dans <b>Téléchargements › PupNiche</b>, garde-le au chaud sur ta carte SD ou ton ordi. Tes mots de passe et connexions aux sites ne sont jamais emportés.</p>
        ${working || bk.steps.length ? `<ul class="steps">${bk.steps.map((s, i) => `<li class="${i < bk.steps.length - 1 || bk.st !== 'work' ? 'done' : ''}">${esc(s)}</li>`).join('')}</ul>` : ''}
        <div class="lastbone">${ic('bone', bk.st === 'done' ? 'green' : 'chrome')}<div><b>${bk.st === 'done' ? 'Os enterré ! Bon chien 🐶' : S.lastBackup ? 'Dernier os enterré ' + ago(S.lastBackup) : "Aucun os enterré pour l'instant"}</b><small>${esc(bk.st === 'done' ? bk.name : S.lastBackup ? fmtD(S.lastBackup) + ' · ' + (S.lastBackupName || '') : 'Pense à en enterrer un avant de changer de téléphone 🐾')}</small></div></div>
        ${S.restoredAt ? `<p class="hint">🦴 Dernier os déterré : ${esc(fmtD(S.restoredAt))}</p>` : ''}
        <div class="nrow"><button class="ab green" data-a="backup" type="button" ${working ? 'disabled' : ''}>${ic('download', 'chrome', 'none')}${working ? 'Je creuse…' : 'Enterrer un os'}</button><button class="ab amber" data-a="restore" type="button" ${working ? 'disabled' : ''}>${ic('upload', 'chrome', 'none')}Déterrer un os</button></div>
      </section>`;
    renderWall();
    $('#s-sch').innerHTML = `${row('siesteCharge', 'Sieste PuppyPhone en charge', 'Quand l\'écran s\'éteint pendant la charge, la sieste s\'affiche par-dessus le verrouillage One UI, puis rééteint l\'écran toute seule')}
        <p class="hint">💡 One UI ne respecte pas la luminosité ni la durée de son propre « Écran de veille » : pour éviter la boucle, <b>désactive l'écran de veille Samsung</b> (bouton ci-dessous) et laisse PuppyPhone s'en occuper avec ce réglage. Comme un lecteur vidéo, la sieste impose sa luminosité. Il faut que <b>PupNav</b> soit activé dans l'accessibilité.</p>
        ${!S.a11y ? `<div class="warnbox">⚠️ PupNav (accessibilité) n'est pas activé.<button class="ab small amber" data-a="a11y" type="button">Ouvrir l'accessibilité</button></div>` : ''}`;
    $('#s-lum').innerHTML = ['Très sombre', 'Tamisé', 'Lumineux'].map((l, i) => `<button type="button" data-lum="${i}" class="${(S.siesteLum || 0) === i ? 'on' : ''}">${l}</button>`).join('');
    $('#s-dur').innerHTML = [[15, '15 s'], [30, '30 s'], [60, '1 min'], [120, '2 min'], [0, 'Jamais']].map(([v, l]) => `<button type="button" data-dur="${v}" class="${(S.siesteDuree == null ? 60 : S.siesteDuree) === v ? 'on' : ''}">${l}</button>`).join('');
  }
  function init() {
    $('#nbody').innerHTML = '<div id="s-pel"></div><div id="s-wall"></div><div id="s-sons"></div><div id="s-verrou"></div><div id="s-aod"></div><div id="s-bone"></div>' + `      <section class="ncard">
        <h2>${ic('battery', 'green')}La sieste du chiot<em>Charge</em></h2>
        <div class="siestprev"><iframe src="sieste.html?apercu=1" tabindex="-1" title="Aperçu de la sieste"></iframe></div>
        <p class="hint">Pendant la charge, ton chiot dort sur son coussin pendant que <b>l'horloge s'allume dans une batterie en verre</b> : rouge quand la gamelle est vide, elle vire au <b>vert</b> en se remplissant jusqu'à 100 %, puis le chiot se réveille avec son os. La scène glisse doucement toute seule pour ne jamais marquer l'écran.</p>
        <div id="s-sch"></div>
        <div class="row"><span>Luminosité de la sieste<small>Très sombre par défaut : l'OLED éteint le noir, seuls les néons brillent doucement</small></span></div>
        <div class="seg" id="s-lum"></div>
        <div class="row"><span>Éteindre l'écran après<small>La sieste s'endort en fondu, puis le téléphone se met en veille comme d'habitude</small></span></div>
        <div class="seg" id="s-dur"></div>
        <div class="nrow"><button class="ab c2" data-a="sieste" type="button">${ic('eye', 'chrome', 'none')}Voir la sieste</button><button class="ab glass" data-a="dream" type="button">${ic('gear', 'chrome', 'none')}Écran de veille Samsung</button></div>
      </section>`;
  }

  function veil(html) { const v = $('#veil'); if (!html) { v.classList.add('hidden'); v.innerHTML = ''; return; } v.innerHTML = `<div class="nwin">${html}</div>`; v.classList.remove('hidden'); }

  document.addEventListener('input', (e) => {
    if (e.target.id !== 'svol') return;
    const v = +e.target.value; e.target.style.setProperty('--v', (v - 10) / .9 + '%'); S.sonsVol = v;
    const lab = e.target.previousElementSibling && e.target.previousElementSibling.querySelector('small'); if (lab) lab.textContent = v + ' %';
  });
  document.addEventListener('change', (e) => {
    if (e.target.id !== 'svol') return;
    nc('setInt', 'sonsVol', S.sonsVol); if (window.PupSons) PupSons.set(S.sons, S.sonsVol / 100); PupSons.preview('pouic', S.sonsVol / 100);
  });
  document.addEventListener('click', (e) => {
    const m = e.target.closest('[data-pel]');
    if (m) {
      const id = m.dataset.pel; if (id === S.pelage) return;
      S.pelage = id; render(); toast('Nouveau pelage ! Toute la niche se rhabille 🐾'); window.PupSons && PupSons.play('grelot'); setTimeout(() => nc('pelage', id), 350); return;
    }
    const toy = e.target.closest('[data-son]');
    if (toy) { toy.classList.remove('boing'); void toy.offsetWidth; toy.classList.add('boing'); PupSons.preview(toy.dataset.son, (S.sonsVol || 60) / 100); return; }
    const ap = e.target.closest('[data-aod]'); if (ap) { S.veilleStyle = ap.dataset.aod; nc('setStr', 'veilleStyle', S.veilleStyle); render(); return; }
    const sg = e.target.closest('[data-seg]'); if (sg && !sg.disabled) { S[sg.dataset.seg] = +sg.dataset.v; nc('setInt', sg.dataset.seg, +sg.dataset.v); render(); return; }
    const lb = e.target.closest('[data-lum]');
    if (lb) { S.siesteLum = +lb.dataset.lum; nc('setInt', 'siesteLum', S.siesteLum); render(); return; }
    const db = e.target.closest('[data-dur]');
    if (db) { S.siesteDuree = +db.dataset.dur; nc('setInt', 'siesteDuree', S.siesteDuree); render(); return; }
    const fb = e.target.closest('[data-force]');
    if (fb && !fb.disabled) { S.verrouForce = +fb.dataset.force; nc('setInt', 'verrouForce', S.verrouForce); render(); return; }
    const wk = e.target.closest('[data-wcat]'); if (wk) { wcat = wk.dataset.wcat; const f = WALLS.find((w) => w.cat === wcat); if (f) wsel = f.id; renderWall(); return; }
    const wc0 = e.target.closest('[data-wall]'); if (wc0) { wsel = wc0.dataset.wall; renderWall(); return; }
    const b = e.target.closest('[data-a],[data-sw]'); if (!b) return;
    const a = b.dataset.a;
    if (a === 'backup') { bk = { st: 'work', steps: [], name: '' }; render(); nc('backup'); }
    if (a === 'restore') nc('pickRestore');
    if (a === 'wall') nc('wallpaper', wsel);
    if (a === 'lock') veil(`<h3>Aussi sur l'écran de verrouillage ? 🔒</h3><p>La meilleure façon : quand Android te montre l'aperçu du fond, choisis <b>« Écrans d'accueil et de verrouillage »</b> s'il te le propose.</p><p>Sinon, je peux retirer l'image propre à ton écran de verrouillage : Android y affichera alors le fond animé de l'accueil. Le verrouillage One UI (code, empreinte, horloge) ne change pas. Pour remettre une image, choisis-la comme d'habitude dans les réglages Fond d'écran.</p><div class="nrow"><button class="ab glass" data-a="ok" type="button">Annuler</button><button class="ab violet" data-a="lockgo" type="button">Retirer l'image</button></div>`);
    if (a === 'lockgo') { const ok = nc('lockToo'); veil(); toast(ok === false ? "One UI n'a pas voulu 🥺 utilise le choix « accueil et verrouillage » de l'aperçu" : 'Verrouille ton téléphone pour voir 🐾'); }
    const wc = e.target.closest('[data-wall]'); if (wc) { wsel = wc.dataset.wall; renderWall(); return; }
    if (a === 'sieste') nc('siestePreview');
    if (a === 'dream') nc('dreamSettings');
    if (a === 'rs-no') { nc('cancelRestore'); veil(); toast("L'os reste enterré, rien n'a bougé 🐶"); }
    if (a === 'rs-go') { veil(`<h3>Le chiot creuse… 🦴</h3><p>La niche redémarre avec ton os. Ne quitte pas, ça prend quelques secondes.</p>`); setTimeout(() => nc('restoreNow'), 600); }
    if (a === 'ok') veil();
    if (a === 'a11y') nc('openA11y');
    if (a === 'aodsave') { const n = nc('aodSave', S.veilleStyle || 'verre'); toast(n ? `🦴 ${n} rangé dans Galerie › PupAOD` : "Oups, je n'ai pas pu l'enregistrer 🥺"); }
    if (a === 'aodopen') nc('openAod');
    if (a === 'veilleprev') { toast('Aperçu de la Veille puppy… touche pour quitter 🐾'); setTimeout(() => nc('veillePreview'), 250); }
    if (a === 'decoprev') { if (nc('decoPreview') === false) veil(`<h3>PupNav n'est pas activé 🐶</h3><p>La déco du verrou passe par le service d'accessibilité <b>PupNav</b>, comme la barre. Active-le puis reviens ici.</p><div class="nrow"><button class="ab glass" data-a="ok" type="button">Plus tard</button><button class="ab amber" data-a="a11y" type="button">Ouvrir</button></div>`); else toast('Regarde bien, la déco s\'affiche 7 secondes 🐾'); }
    if (b.dataset.sw && !b.disabled) { const on = !b.classList.contains('on'); S[b.dataset.sw] = on; nc('set', b.dataset.sw, on); if (b.dataset.sw === 'sons' && window.PupSons) PupSons.set(on); if (b.dataset.sw === 'sons' && on) PupSons.preview('jappe', (S.sonsVol || 60) / 100); render(); }
  });

  // aperçu du fond animé (même dessin que le vrai fond, en miniature)
  let raf = 0;
  function startWall() {
    cancelAnimationFrame(raf);
    const cv = $('#wcv'); if (!cv) return;
    const pels = S.pelages && S.pelages.length ? S.pelages : PEL, P = pels.find((p) => p.id === S.pelage) || pels[0];
    const acc = P.acc, acc2 = P.acc2, bg = P.bg, dpr = Math.min(2, window.devicePixelRatio || 1);
    const W = cv.width = cv.clientWidth * dpr, H = cv.height = cv.clientHeight * dpr, c = cv.getContext('2d');
    const R = Math.random, stars = Array.from({ length: 50 }, () => [R(), R() * .6, R() * 6.28]);
    const paw = () => ({ x: R() * W, y: R() * H, r: (5 + R() * 9) * dpr, v: (10 + R() * 20) * dpr, ph: R() * 6.28, c: R() < .5 ? acc : acc2, rot: (-25 + R() * 50) * Math.PI / 180 });
    const paws = Array.from({ length: 14 }, paw);
    const al = (hex, a) => hex + Math.round(Math.max(0, Math.min(1, a)) * 255).toString(16).padStart(2, '0');
    let last = performance.now(), t0 = last;
    function frame(now) {
      const dt = Math.min(.05, (now - last) / 1000), t = (now - t0) / 1000; last = now;
      let g = c.createLinearGradient(0, 0, 0, H); g.addColorStop(0, al(acc, .22)); g.addColorStop(.55, bg); g.addColorStop(1, al(acc2, .14));
      c.fillStyle = bg; c.fillRect(0, 0, W, H); c.fillStyle = g; c.fillRect(0, 0, W, H);
      for (const s of stars) { const tw = .5 + .5 * Math.sin(t * 1.6 + s[2]); c.fillStyle = `rgba(255,255,255,${.15 + .55 * tw})`; c.beginPath(); c.arc(s[0] * W, s[1] * H, (.8 + tw) * dpr, 0, 7); c.fill(); }
      for (let i = 0; i < 3; i++) {
        const ang = Math.sin(t * (.18 + i * .07) + i * 2) * .5, bx = W * (.2 + i * .3);
        c.save(); c.translate(bx, -20); c.rotate(ang);
        const col = i === 1 ? acc2 : acc; g = c.createLinearGradient(0, 0, 0, H * .9); g.addColorStop(0, al(col, .45)); g.addColorStop(1, al(col, 0));
        c.fillStyle = g; c.fillRect(-2 * dpr, 0, 4 * dpr, H * .9); g = c.createLinearGradient(0, 0, 0, H * .7); g.addColorStop(0, al(col, .12)); g.addColorStop(1, al(col, 0)); c.fillStyle = g; c.fillRect(-18 * dpr, 0, 36 * dpr, H * .7); c.restore();
      }
      const hor = H * .7; g = c.createLinearGradient(0, hor, 0, H); g.addColorStop(0, al(acc, 0)); g.addColorStop(1, al(acc, .3)); c.fillStyle = g; c.fillRect(0, hor, W, H - hor);
      c.lineWidth = dpr; const sc = (t * .35) % 1;
      for (let i = 0; i < 10; i++) { const k = (i + sc) / 10, y = hor + (H - hor) * k * k; c.strokeStyle = al(acc, .12 + .6 * k); c.beginPath(); c.moveTo(0, y); c.lineTo(W, y); c.stroke(); }
      c.strokeStyle = al(acc2, .3); for (let i = -8; i <= 8; i++) { c.beginPath(); c.moveTo(W / 2 + i * 4 * dpr, hor); c.lineTo(W / 2 + i * W * .16, H); c.stroke(); }
      c.fillStyle = al(acc, .65); c.fillRect(0, hor - dpr, W, 2 * dpr);
      for (const q of paws) {
        q.y -= q.v * dt; if (q.y < -30 * dpr) Object.assign(q, paw(), { y: H + 20 * dpr });
        const x = q.x + Math.sin(t * .9 + q.ph) * 8 * dpr, fade = Math.min(1, Math.max(0, q.y / (H * .25))), r = q.r;
        if (fade < .02) continue;
        c.save(); c.translate(x, q.y); c.rotate(q.rot); c.globalAlpha = .8 * fade;
        g = c.createRadialGradient(0, 0, 0, 0, 0, r * 3.2); g.addColorStop(0, al(q.c, .35)); g.addColorStop(1, al(q.c, 0)); c.fillStyle = g; c.beginPath(); c.arc(0, 0, r * 3.2, 0, 7); c.fill();
        g = c.createRadialGradient(-r * .3, -r * .3, 0, 0, 0, r * 1.4); g.addColorStop(0, '#fff'); g.addColorStop(.55, q.c); g.addColorStop(1, q.c); c.fillStyle = g;
        c.beginPath(); c.ellipse(0, r * .42, r, r * .78, 0, 0, 7); c.fill();
        for (const [px, py] of [[-1.08, -.62], [-.4, -1.22], [.4, -1.22], [1.08, -.62]]) { c.beginPath(); c.arc(px * r, py * r, r * .42, 0, 7); c.fill(); }
        c.restore();
      }
      raf = requestAnimationFrame(frame);
    }
    raf = requestAnimationFrame(frame);
  }

  window.NicheUI = {
    on(ev, data) {
      const o = J(data, {}) || {};
      if (ev === 'resume') { const keep = S.pelage; S = J(nc('info'), S) || S; if (!window.Niche) S.pelage = keep; render(); }
      else if (ev === 'backup') {
        if (o.st === 'step') { bk.steps.push(o.msg); render(); }
        else if (o.st === 'done') { bk.st = 'done'; bk.name = o.name; S = J(nc('info'), S) || S; render(); toast('🦴 Os enterré dans Téléchargements › PupNiche'); window.PupSons && PupSons.play('grelot'); }
        else if (o.st === 'error') { bk.st = 'idle'; render(); veil(`<h3>Oups, la pelle a cassé 🥺</h3><p>${esc(o.msg || 'Erreur inconnue')}</p><button class="ab wide" data-a="ok" type="button">Compris</button>`); }
      } else if (ev === 'restore') {
        if (o.st === 'reading') veil('<h3>Je renifle l\'os… 👃</h3><p>Vérification du fichier .puppy</p>');
        else if (o.st === 'cancel') veil();
        else if (o.st === 'error') veil(`<h3>Ce n'est pas un os de la niche 🐶</h3><p>${esc(o.msg || '')}</p><p>Choisis un fichier <b>.puppy</b> créé avec « Enterrer un os ».</p><button class="ab wide" data-a="ok" type="button">Compris</button>`);
        else if (o.st === 'ready') {
          const m = o.man || {};
          veil(`<h3>Déterrer cet os ? 🦴</h3>
            <dl><dt>Enterré le</dt><dd>${esc(fmtD(m.date))}</dd><dt>Téléphone</dt><dd>${esc(m.device || '—')}</dd><dt>Version</dt><dd>v1.0.${esc(m.version || '?')}</dd></dl>
            <p>Les réglages actuels de la niche seront <b>remplacés</b> par ceux de l'os, puis PuppyPhone redémarre tout seul. Tes photos, musiques et SMS ne sont pas touchés.</p>
            <div class="nrow"><button class="ab glass" data-a="rs-no" type="button">Laisser enterré</button><button class="ab amber" data-a="rs-go" type="button">Déterrer !</button></div>`);
        }
      }
    },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };
  $('#nlogo').innerHTML = ic('bone', 'violet');
  render();
})();
