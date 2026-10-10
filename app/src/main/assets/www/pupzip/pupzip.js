/* PupZip Éco++ — logique du presse-compresseur. Scanne une source, réencode en WebP, garde les originaux. */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const J = (s, d) => { try { return s == null || s === '' ? d : JSON.parse(s); } catch (e) { return d; } };
  const I = (window.PupIcons && PupIcons.icon) || (() => '');

  // --- pont natif (mock en preview navigateur) ---
  const MOCK = !window.Pup;
  const P = window.Pup || mock();
  const call = (fn, ...a) => { try { return P[fn] ? P[fn](...a) : undefined; } catch (e) { console.warn(fn, e); return undefined; } };
  const haptic = () => call('haptic');

  const fmt = (b) => { b = +b || 0; if (b < 1024) return b + ' o'; const u = ['Ko', 'Mo', 'Go', 'To']; let i = -1; do { b /= 1024; i++; } while (b >= 1024 && i < 3); return (b < 10 ? b.toFixed(1) : Math.round(b)).toString().replace('.', ',') + ' ' + u[i]; };

  let cat = 'shots';
  let quality = 70;
  let running = false;
  let scanTotal = 0, scanCount = 0;

  // ====================================================== manomètre (gain de place)
  const ticks = $('#gticks'), arc = $('#garc'), needle = $('#gneedle'), gpct = $('#gpct');
  (function buildGauge() {
    const cx = 100, cy = 110, r = 72, a0 = 200, a1 = 340; let g = '';
    for (let i = 0; i <= 10; i++) {
      const a = (a0 + (a1 - a0) * i / 10) * Math.PI / 180;
      const r2 = i % 5 === 0 ? r - 12 : r - 7;
      g += `<line x1="${cx + Math.cos(a) * r}" y1="${cy + Math.sin(a) * r}" x2="${cx + Math.cos(a) * r2}" y2="${cy + Math.sin(a) * r2}"/>`;
    }
    ticks.innerHTML = g;
  })();
  function setGauge(pct) {
    pct = Math.max(0, Math.min(100, pct || 0));
    const a0 = 200, a1 = 340, a = (a0 + (a1 - a0) * pct / 100) * Math.PI / 180, r = 72, cx = 100, cy = 110;
    needle.setAttribute('x2', cx + Math.cos(a) * (r - 4));
    needle.setAttribute('y2', cy + Math.sin(a) * (r - 4));
    // arc de 200° au point courant
    const aa = (a0 + (a1 - a0) * pct / 100) * Math.PI / 180, rr = 60;
    const x0 = cx + Math.cos(a0 * Math.PI / 180) * rr, y0 = cy + Math.sin(a0 * Math.PI / 180) * rr;
    const x1 = cx + Math.cos(aa) * rr, y1 = cy + Math.sin(aa) * rr;
    const large = (a1 - a0) * pct / 100 > 180 ? 1 : 0;
    arc.setAttribute('d', pct <= 0 ? '' : `M${x0} ${y0} A${rr} ${rr} 0 ${large} 1 ${x1} ${y1}`);
    const col = pct >= 55 ? '#3dffb0' : pct >= 25 ? '#ffb627' : '#29e6ff';
    arc.setAttribute('stroke', col); gpct.setAttribute('fill', col);
    gpct.textContent = Math.round(pct) + '%';
  }
  setGauge(0);

  // ====================================================== LED
  function led(state, txt) {
    const el = $('#led'); el.className = 'led' + (state ? ' ' + state : '');
    $('#ledtx').textContent = txt;
  }

  // ====================================================== catégories
  $('#cats').addEventListener('click', (e) => {
    const b = e.target.closest('.sw'); if (!b || running) return;
    cat = b.dataset.cat; haptic();
    $('#cats').querySelectorAll('.sw').forEach((x) => x.classList.toggle('on', x === b));
    resetScan();
  });

  // ====================================================== qualité
  $('#qual').addEventListener('click', (e) => {
    const b = e.target.closest('button'); if (!b || running) return;
    quality = +b.dataset.q; haptic();
    $('#qual').setAttribute('data-q', b.dataset.q);
    $('#qual').querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
  });
  $('#qual').setAttribute('data-q', '70');

  // ====================================================== actions
  function resetScan() {
    scanTotal = 0; scanCount = 0;
    $('#cBefore').textContent = '—'; $('#cAfter').textContent = '—'; setGauge(0);
    $('#go').hidden = true; $('#result').hidden = true; $('#del').hidden = true;
    led('', 'PRÊT');
  }

  $('#scan').onclick = () => {
    if (running) return;
    haptic(); led('busy', 'ANALYSE…');
    $('#result').hidden = true; $('#del').hidden = true; $('#go').hidden = true;
    $('#scan').disabled = true;
    call('scan', cat);
  };

  $('#go').onclick = () => {
    if (running || scanCount <= 0) return;
    haptic(); running = true; led('busy', 'COMPRESSION…');
    $('#go').hidden = true; $('#scan').hidden = true; $('#stop').hidden = false;
    $('#result').hidden = true; $('#del').hidden = true;
    $('#prog').hidden = false; $('#barfill').style.width = '0%'; $('#progtx').textContent = 'Démarrage…';
    call('compress', cat, quality);
  };

  $('#stop').onclick = () => { haptic(); call('cancelJob'); $('#progtx').textContent = 'Arrêt en cours…'; };

  $('#del').onclick = () => {
    haptic();
    $('#del').disabled = true; led('busy', 'SUPPRESSION…');
    call('deleteOriginals');
  };

  // ====================================================== écran d'autorisation
  $('#accic').innerHTML = I('gallery', 'cyan');
  $('#accbtn').onclick = () => { haptic(); call('requestAccess'); };
  function gate() {
    const ok = !!call('hasAccess');
    $('#access').hidden = ok;
    if (ok) call('scan', cat); // pré-analyse auto de la catégorie par défaut
    else led('', 'ACCÈS REQUIS');
  }

  // ====================================================== évènements natifs
  function finished(o) {
    running = false;
    $('#stop').hidden = true; $('#scan').hidden = false; $('#scan').disabled = false;
    $('#prog').hidden = true;
    const gain = o.orig > 0 ? (1 - o.new / o.orig) * 100 : 0;
    setGauge(gain);
    $('#cBefore').textContent = fmt(o.orig); $('#cAfter').textContent = fmt(o.new);
    led('done', 'TERMINÉ');
    const saved = Math.max(0, o.orig - o.new);
    let html = `<div class="big">💾 ${fmt(saved)} récupérés !</div>`;
    html += `<div>✅ <b>${o.ok}</b> image(s) compressée(s)`;
    if (o.fail) html += ` · ⚠️ ${o.fail} ignorée(s)`;
    html += `</div>`;
    html += `<div>${fmt(o.orig)} → <b>${fmt(o.new)}</b> (−${Math.round(gain)}%)</div>`;
    if (o.candidates > 0) html += `<div style="margin-top:6px;color:var(--muted);font-size:12.5px">Les copies sont dans <b>Images/PupZip</b>. Tu peux maintenant supprimer les ${o.candidates} original(aux) compressé(s) pour libérer la place.</div>`;
    const r = $('#result'); r.innerHTML = html; r.hidden = false;
    $('#del').hidden = !(o.candidates > 0); $('#del').disabled = false;
  }

  window.ZipUI = {
    on(ev, data) {
      if (ev === 'access') { const ok = data === '1'; $('#access').hidden = ok; if (ok) { led('', 'PRÊT'); call('scan', cat); } return; }
      if (ev === 'scanned') {
        const o = J(data, {}) || {}; scanCount = o.count || 0; scanTotal = o.total || 0;
        $('#scan').disabled = false; $('#scan').hidden = false;
        $('#cBefore').textContent = fmt(scanTotal); $('#cAfter').textContent = '—'; setGauge(0);
        if (scanCount > 0) { $('#go').hidden = false; led('', scanCount + ' À COMPRESSER'); }
        else { $('#go').hidden = true; led('done', 'RIEN À FAIRE'); const r = $('#result'); r.innerHTML = 'Aucune image à compresser ici 🐾 (déjà optimisées ou dossier vide).'; r.hidden = false; }
        return;
      }
      if (ev === 'progress') {
        const o = J(data, {}) || {}; const p = o.total ? Math.round(o.done / o.total * 100) : 0;
        $('#barfill').style.width = p + '%';
        $('#progtx').textContent = `${o.done}/${o.total} · ${fmt(o.orig)} → ${fmt(o.new)}`;
        if (o.orig > 0) setGauge((1 - o.new / o.orig) * 100);
        return;
      }
      if (ev === 'finished') { finished(J(data, {}) || {}); return; }
      if (ev === 'deleted') {
        const n = +data || 0; led('done', 'TERMINÉ');
        $('#del').hidden = true;
        const r = $('#result'); r.innerHTML = `<div class="big">🗑️ ${n} original(aux) supprimé(s)</div><div>La place est libérée. Les copies compressées restent dans <b>Images/PupZip</b>.</div>`; r.hidden = false;
        return;
      }
      if (ev === 'delcancel') { led('done', 'TERMINÉ'); $('#del').disabled = false; return; }
      if (ev === 'err') { led('', 'ERREUR'); const r = $('#result'); r.innerHTML = '⚠️ ' + (data || 'erreur inconnue'); r.hidden = false; $('#del').disabled = false; return; }
      if (ev === 'resume') { gate(); return; }
    },
    back() { return false; },
  };

  // ====================================================== démarrage
  gate();

  // ------------------------------------------------------ mock navigateur (preview)
  function mock() {
    let busy = false;
    return {
      hasAccess: () => true,
      requestAccess() { setTimeout(() => ZipUI.on('access', '1'), 300); },
      haptic() {},
      scan(c) { const n = { shots: 48, dl: 12, all: 73 }[c] || 0; const t = n * 2.4e6; setTimeout(() => ZipUI.on('scanned', JSON.stringify({ count: n, total: t })), 350); },
      compress(c, q) {
        busy = true; const n = { shots: 48, dl: 12, all: 73 }[c] || 0; const factor = q >= 85 ? 0.45 : q >= 70 ? 0.28 : 0.16;
        let done = 0, orig = 0, nw = 0; const each = 2.4e6;
        const tick = () => {
          if (!busy || done >= n) { ZipUI.on('finished', JSON.stringify({ ok: done, fail: 0, orig, new: nw, candidates: done })); busy = false; return; }
          done++; orig += each; nw += each * factor;
          ZipUI.on('progress', JSON.stringify({ done, total: n, ok: done, orig, new: nw }));
          setTimeout(tick, 60);
        };
        tick();
      },
      cancelJob() { busy = false; },
      deleteOriginals() { setTimeout(() => ZipUI.on('deleted', '48'), 400); },
    };
  }
})();
