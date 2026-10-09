/* Carte de notification puppyplay (volet + pop-up) */
(function () {
  'use strict';
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const ago = (t) => {
    const s = Math.max(0, (Date.now() - t) / 1000);
    if (s < 60) return 'à l’instant'; if (s < 3600) return Math.floor(s / 60) + ' min'; if (s < 86400) return Math.floor(s / 3600) + ' h';
    const d = new Date(t); return d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
  };
  /** Couleur de bordure : celle de l'appli si elle est lisible, sinon la couleur du pelage. */
  const col = (n) => { if (!n.hasColor) return ''; const c = n.color || ''; const m = /^#?([0-9a-f]{6})$/i.exec(c); if (!m) return ''; const v = parseInt(m[1], 16), r = v >> 16, g = (v >> 8) & 255, b = v & 255; return (r + g + b) < 60 ? '' : '#' + m[1]; };
  function card(n, o) {
    o = o || {};
    const c = col(n), lines = (n.lines || []).filter((l) => l.t);
    const body = lines.length > 1 ? lines.slice(-3).map((l) => `<div class="ln">${l.who ? `<b>${esc(l.who)} :</b> ` : ''}${esc(l.t)}</div>`).join('') : `<div class="tx">${esc(n.text || (lines[0] && lines[0].t) || '')}</div>`;
    const acts = (n.acts || []).filter((a) => a.t).map((a) => `<button data-act="${a.i}" data-reply="${a.reply ? 1 : 0}" type="button">${a.reply ? '💬 ' : ''}${esc(a.t)}</button>`).join('');
    return `<div class="nc${n.ongoing ? ' ongo' : ''}" data-key="${esc(n.key)}" data-clear="${n.clear ? 1 : 0}" style="${c ? '--c:' + c : ''}">
      <span class="ai"><img src="/nicon?pkg=${encodeURIComponent(n.pkg)}" alt="" onerror="this.style.visibility='hidden'"></span>
      <div class="bd"><div class="top"><b>${esc(n.app)}</b><span>· ${ago(n.when)}</span>${n.sub ? `<span>· ${esc(n.sub)}</span>` : ''}</div>
        ${n.title ? `<span class="tt">${esc(n.title)}</span>` : ''}${body}
        ${n.prog != null ? `<div class="prog"><i style="width:${n.prog < 0 ? 100 : n.prog}%;${n.prog < 0 ? 'opacity:.5' : ''}"></i></div>` : ''}
        ${n.pic && !o.compact ? `<img class="pic" src="/nimg?k=pic&key=${encodeURIComponent(n.key)}" alt="">` : ''}
        ${acts ? `<div class="acts">${acts}</div>` : ''}
      </div>
      ${n.large ? `<img class="lg" src="/nimg?k=large&key=${encodeURIComponent(n.key)}" alt="" onerror="this.remove()">` : ''}
      ${o.timer ? `<div class="tm"><i style="animation-duration:${o.timer}ms"></i></div><span class="paw-peek">🐾</span>` : ''}
    </div>`;
  }
  /** Glisser une carte : à gauche/droite pour la balayer, petit appui pour l'ouvrir. */
  function swipe(el, h) {
    let x0 = 0, y0 = 0, dx = 0, dy = 0, on = false, axis = '';
    el.addEventListener('pointerdown', (e) => { if (e.target.closest('button,input')) return; x0 = e.clientX; y0 = e.clientY; dx = dy = 0; on = true; axis = ''; });
    el.addEventListener('pointermove', (e) => {
      if (!on) return; dx = e.clientX - x0; dy = e.clientY - y0;
      if (!axis && Math.hypot(dx, dy) > 10) { axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y'; if (axis === 'x') { el.setPointerCapture(e.pointerId); el.classList.add('sw'); } }
      if (axis === 'x') { el.style.transform = `translateX(${dx}px) rotate(${dx / 40}deg)`; el.style.opacity = String(1 - Math.min(.8, Math.abs(dx) / 400)); }
      if (axis === 'y' && h.y) h.y(dy, false);
    });
    const end = (e) => {
      if (!on) return; on = false; el.classList.remove('sw');
      if (axis === 'x') {
        if (Math.abs(dx) > 110 && el.dataset.clear === '1') { el.style.transform = `translateX(${dx > 0 ? 120 : -120}%)`; el.style.opacity = '0'; setTimeout(() => h.dismiss(el.dataset.key, el), 220); }
        else { el.style.transform = ''; el.style.opacity = ''; if (Math.abs(dx) > 110 && h.cant) h.cant(); }
      } else if (axis === 'y' && h.y) h.y(dy, true);
      else if (!axis && e.type === 'pointerup') h.open(el.dataset.key, el);
    };
    el.addEventListener('pointerup', end); el.addEventListener('pointercancel', end);
  }
  window.PupCard = { card, swipe, esc, ago };
})();
