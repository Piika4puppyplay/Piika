/* PupKeyboard — « Dressage du clavier » */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const I = PupIcons.icon;
  const ic = (g, c, sh) => I(g, c, sh ? { shape: sh } : undefined);
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const K = window.Kbd || {
    state: () => JSON.stringify({ enabled: true, current: false, learned: 42, clips: 3 }),
    get: () => JSON.stringify({ theme: 'rose', size: 2, layout: 'azerty', lang: 'fr', numRow: false, hints: true, bubble: true, vibe: 2, sound: false, soundVol: 50, autoCap: true, dblSpace: true, suggest: true, autocorrect: false, learn: true, clipHist: true }),
    set() {}, openList() {}, pick() {}, forget() {}, clearClips() {},
  };
  const kc = (fn, ...a) => { try { return K[fn] ? K[fn](...a) : undefined; } catch (e) { console.warn(fn, e); } };

  const PELAGES = [
    ['rose', 'Néon Rose', '#ff3fa4', '#2a0b24', '#4a2350', '#180a22'],
    ['cyan', 'Laser Turquoise', '#29e6ff', '#06202a', '#1d4a5c', '#07161e'],
    ['violet', 'Violet Velours', '#9b5cff', '#1e0b3a', '#4a2a80', '#1a0b33'],
    ['cuir', 'Cuir & Rivets', '#ff3fa4', '#1a0d15 url(leather.png)', '#3a2430', '#140910'],
    ['or', 'Or Royal', '#ffb627', '#241604', '#5a4012', '#1e1404'],
    ['vert', 'Vert Fluo', '#3dffb0', '#04241a', '#135a44', '#052016'],
  ];
  const SIZES = [['Chiot', '30px'], ['Ado', '38px'], ['Grand chien', '46px'], ['Molosse', '54px']];
  const VIBES = ['Immobile', 'Petite queue', 'Ça frétille', 'Gros toutou'];

  function sw(key, on) { return `<button class="sw${on ? ' on' : ''}" data-sw="${key}" type="button"><i></i></button>`; }
  function row(label, sub, key, on) { return `<div class="row"><span>${label}<small>${sub}</small></span>${sw(key, on)}</div>`; }

  function render() {
    const st = J(kc('state'), {}) || {}, s = J(kc('get'), {}) || {};
    const pel = PELAGES.find((p) => p[0] === s.theme) || PELAGES[0];
    document.body.style.setProperty('--acc', pel[2]);
    $('#kbody').innerHTML = `
      <section class="kc"><h3>${ic('paw', 'pink')}<span>Adopter le chien<small>Deux étapes et il est à la maison</small></span></h3>
        <div class="step${st.enabled ? ' ok' : ''}"><b class="n">${st.enabled ? '✓' : '1'}</b><span>Laisse entrer le chien<small>Active « PupKeyboard » dans la liste des claviers</small></span>${st.enabled ? '' : '<button class="ab small" data-a="list" type="button">Ouvrir</button>'}</div>
        <div class="step${st.current ? ' ok' : ''}"><b class="n">${st.current ? '✓' : '2'}</b><span>Choisis ton chien<small>${st.current ? 'PupKeyboard est ton clavier principal 🐶' : 'Sélectionne PupKeyboard comme clavier'}</small></span>${st.current ? '' : '<button class="ab small c2" data-a="pick" type="button">Choisir</button>'}</div>
      </section>
      <section class="kc"><h3>${ic('edit', 'cyan')}<span>Bac à sable<small>Essaie tes pattes ici</small></span></h3>
        <textarea class="sandbox" placeholder="Wouf ! Tape quelque chose… 🐾"></textarea></section>

      <section class="kc"><h3>${ic('sparkle', 'pink')}<span>Pelage<small>La couleur de ton chien</small></span></h3>
        <div class="pelages">${PELAGES.map(([id, n, c, bg, k1, k2]) => `<button class="pel${s.theme === id ? ' on' : ''}" data-theme="${id}" style="--c:${c};--bg:${bg};--k1:${k1};--k2:${k2}" type="button"><span class="mk"><i>a</i><i>z</i><i class="e">⏎</i></span>${n}</button>`).join('')}</div></section>

      <section class="kc"><h3>${ic('paw', 'amber')}<span>Taille des pattes<small>La hauteur des touches</small></span></h3>
        <div class="sizes">${SIZES.map(([n, px], i) => `<button class="sz${s.size === i ? ' on' : ''}" data-size="${i}" style="--s:${px}" type="button">${ic('paw', 'amber', 'none')}${n}</button>`).join('')}</div></section>

      <section class="kc"><h3>${ic('apps', 'violet')}<span>Race du clavier<small>Disposition et langue du flair</small></span></h3>
        <div class="seg"><button class="${s.layout !== 'qwerty' ? 'on' : ''}" data-set="layout" data-v="azerty" type="button">AZERTY</button><button class="${s.layout === 'qwerty' ? 'on' : ''}" data-set="layout" data-v="qwerty" type="button">QWERTY</button></div>
        <div class="seg"><button class="${s.lang !== 'en' ? 'on' : ''}" data-set="lang" data-v="fr" type="button">🇫🇷 Flair français</button><button class="${s.lang === 'en' ? 'on' : ''}" data-set="lang" data-v="en" type="button">🇬🇧 English nose</button></div>
        ${row('Rangée de croquettes 1 2 3', 'Une ligne de chiffres au-dessus des lettres', 'numRow', s.numRow)}
        ${row('Petits chiffres sur les touches', 'Appui long sur a, z, e… pour un chiffre', 'hints', s.hints)}
        ${row('Bulle quand je tape', 'La lettre grossit au-dessus de ta patte', 'bubble', s.bubble)}
      </section>

      <section class="kc"><h3>${ic('pulse', 'red')}<span>Frétillement<small>Vibration des touches</small></span></h3>
        <div class="seg">${VIBES.map((n, i) => `<button class="${s.vibe === i ? 'on' : ''}" data-set="vibe" data-v="${i}" data-int="1" type="button">${n}</button>`).join('')}</div>
        ${row('Clic de griffes', 'Petit son à chaque touche', 'sound', s.sound)}
        <div class="rng" ${s.sound ? '' : 'hidden'}><input type="range" min="10" max="100" step="5" value="${s.soundVol}" data-r="soundVol"><b class="big">${s.soundVol} %</b></div>
      </section>

      <section class="kc"><h3>${ic('bone', 'gold')}<span>Flair à mots<small>Suggestions et correction</small></span></h3>
        ${row('Flair à mots', 'Propose 3 mots au-dessus du clavier', 'suggest', s.suggest)}
        ${row('Rapporte le bon mot', 'Corrige tout seul à l\'espace (⌫ juste après pour annuler)', 'autocorrect', s.autocorrect)}
        ${row('Le chien se redresse', 'Majuscule automatique en début de phrase', 'autoCap', s.autoCap)}
        ${row('Double tape sur espace = point', 'Deux espaces rapides → « . »', 'dblSpace', s.dblSpace)}
        ${row('Mémoire du chien', `Retient tes mots à toi · ${st.learned || 0} appris`, 'learn', s.learn)}
        <button class="ab small glass" data-a="forget" type="button">${ic('trash', 'chrome', 'none')}Faire oublier les mots appris</button>
      </section>

      <section class="kc"><h3>${ic('paste', 'cyan')}<span>Gamelle à copier<small>Historique du presse-papiers (📋 dans la barre)</small></span></h3>
        ${row('Garder ce que je copie', `${st.clips || 0} élément${(st.clips || 0) > 1 ? 's' : ''} en gamelle`, 'clipHist', s.clipHist)}
        <button class="ab small glass" data-a="clips" type="button">${ic('trash', 'chrome', 'none')}Vider la gamelle</button>
      </section>

      <section class="kc"><h3>${ic('star', 'gold')}<span>Tours de dressage<small>Les astuces du bon chien</small></span></h3>
        <div class="tips">
          <div>${ic('rw', 'cyan')}<span><b>Glisse sur la barre d'espace</b> pour déplacer le curseur</span></div>
          <div>${ic('left', 'red')}<span><b>Glisse vers la gauche depuis ⌫</b> pour effacer un mot entier</span></div>
          <div>${ic('paw', 'pink')}<span><b>Appui long sur une lettre</b> pour les accents (é, è, ç, à…) ou le chiffre</span></div>
          <div>${ic('apps', 'violet')}<span><b>Appui long sur l'espace</b> pour changer de clavier</span></div>
          <div>${ic('gear', 'chrome')}<span><b>Appui long sur la virgule ou ?123</b> pour revenir ici</span></div>
          <div>${ic('check', 'green')}<span><b>Deux appuis sur ⇧</b> pour bloquer les MAJUSCULES</span></div>
        </div></section>`;
    $$('input[type=range]').forEach((r) => {
      const paint = () => { r.style.setProperty('--v', ((r.value - r.min) / (r.max - r.min) * 100) + '%'); r.nextElementSibling.textContent = r.value + ' %'; };
      paint(); r.oninput = paint; r.onchange = () => kc('set', JSON.stringify({ [r.dataset.r]: +r.value }));
    });
  }

  document.addEventListener('click', (e) => {
    const t = e.target;
    const a = t.closest('[data-a]');
    if (a) {
      const k = a.dataset.a;
      if (k === 'list') kc('openList');
      if (k === 'pick') kc('pick');
      if (k === 'forget') { kc('forget'); toast('Le chien a tout oublié 🐶💭'); render(); }
      if (k === 'clips') { kc('clearClips'); toast('Gamelle à copier vidée 🦴'); render(); }
      return;
    }
    const th = t.closest('[data-theme]'); if (th) { kc('set', JSON.stringify({ theme: th.dataset.theme })); render(); toast('Nouveau pelage ✨'); return; }
    const sz = t.closest('[data-size]'); if (sz) { kc('set', JSON.stringify({ size: +sz.dataset.size })); render(); return; }
    const se = t.closest('[data-set]'); if (se) { kc('set', JSON.stringify({ [se.dataset.set]: se.dataset.int ? +se.dataset.v : se.dataset.v })); render(); return; }
    const s = t.closest('[data-sw]'); if (s) { const on = !s.classList.contains('on'); kc('set', JSON.stringify({ [s.dataset.sw]: on })); render(); }
  });

  function toast(msg) {
    const d = document.createElement('div'); d.className = 'toast';
    d.innerHTML = `${ic('paw', 'pink')}<span>${msg}</span>`;
    $('#toasts').appendChild(d);
    setTimeout(() => { d.classList.add('out'); setTimeout(() => d.remove(), 320); }, 2000);
  }

  window.KbdUI = {
    on(ev) { if (ev === 'resume') { const ta = $('.sandbox'); const v = ta ? ta.value : ''; const focused = document.activeElement === ta; render(); if (v) $('.sandbox').value = v; if (focused) $('.sandbox').focus(); } },
    insets(t, b) { const r = document.documentElement.style; r.setProperty('--st', Math.max(t, 20) + 'px'); r.setProperty('--sb', Math.max(b, 0) + 'px'); },
  };
  render();
})();
