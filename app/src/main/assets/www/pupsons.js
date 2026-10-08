/* La boîte à couinements — sons puppy des Pup-apps (ajouté automatiquement après icons.js). */
(function () {
  'use strict';
  if (window.PupSons) return;
  const CFG = window.PUPSONS || { on: false, vol: .6 };
  let ctx = null;
  const buf = {}, loading = {};
  function ac() {
    if (!ctx) { try { ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return null; } }
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    return ctx;
  }
  function load(n) {
    if (buf[n] || loading[n]) return loading[n] || Promise.resolve(buf[n]);
    const c = ac(); if (!c) return Promise.resolve(null);
    loading[n] = fetch('sons/' + n + '.wav').then((r) => r.arrayBuffer()).then((a) => new Promise((ok, ko) => c.decodeAudioData(a, ok, ko)))
      .then((b) => { buf[n] = b; delete loading[n]; return b; }).catch(() => { delete loading[n]; return null; });
    return loading[n];
  }
  function raw(n, vol) {
    const c = ac(); if (!c) return;
    const go = (b) => { if (!b) return; const s = c.createBufferSource(), g = c.createGain(); s.buffer = b; g.gain.value = vol; s.connect(g).connect(c.destination); s.start(); };
    if (buf[n]) go(buf[n]); else load(n).then(go);
  }
  const PupSons = {
    get on() { return !!CFG.on; },
    play(n) { if (CFG.on) raw(n, CFG.vol); },
    preview(n, vol) { raw(n, vol == null ? CFG.vol || .6 : vol); }, // écoute dans « Ta niche », même boîte éteinte
    set(on, vol) { CFG.on = on; if (vol != null) CFG.vol = vol; },
  };
  window.PupSons = PupSons;
  if (!CFG.on) return;
  // sons automatiques : patte qui tape, jouet qui couine sur les interrupteurs, croquette croquée pour supprimer
  document.addEventListener('pointerdown', () => { ac(); ['tap', 'pouic', 'croc'].forEach(load); }, { once: true, capture: true });
  document.addEventListener('click', (e) => {
    const el = e.target.closest && e.target.closest('button,[role=button],a,[data-a],[data-id],[data-sw],.sw,.ab,.app,.medal');
    if (!el || el.disabled || el.dataset.nosound != null) return;
    const a = (el.dataset.a || '') + ' ' + (el.className && el.className.baseVal == null ? el.className : '');
    if (el.classList.contains('sw') || el.dataset.sw != null) return PupSons.play('pouic');
    if (/\b(del|delete|trash|suppr|remove|corbeille|poubelle)\b/i.test(a) || el.classList.contains('red')) return PupSons.play('croc');
    PupSons.play('tap');
  }, true);
})();
