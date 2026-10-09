/* Batteries puppy pour l'écran éteint : « Gamelle de verre » et « Os lumineux » (dessin canvas skeuomorphique).
   AodBat.draw(ctx, style, w, h, pct, t, {charging, acc, acc2}) — fond noir OLED, utilisé par la Veille puppy et les GIF AOD. */
(function () {
  'use strict';
  const RG = (c, x, y, r, st, x0, y0, r0) => { const g = c.createRadialGradient(x0 ?? x, y0 ?? y, r0 ?? 0, x, y, r); st.forEach(([o, col]) => g.addColorStop(o, col)); return g; };
  const LG = (c, x0, y0, x1, y1, st) => { const g = c.createLinearGradient(x0, y0, x1, y1); st.forEach(([o, col]) => g.addColorStop(o, col)); return g; };
  const hsl = (h, s, l, a = 1) => `hsla(${h},${s}%,${l}%,${a})`;
  const chrome = (c, x0, y0, x1, y1) => LG(c, x0, y0, x1, y1, [[0, '#f4f2fa'], [.18, '#8a8399'], [.35, '#ffffff'], [.5, '#4e485e'], [.65, '#d8d3e4'], [.82, '#6b6480'], [1, '#e9e6f2']]);
  const hex = (h, a) => { const v = parseInt(h.slice(1), 16); return `rgba(${v >> 16 & 255},${v >> 8 & 255},${v & 255},${a})`; };
  // bulles déterministes
  const B = Array.from({ length: 16 }, (_, i) => [((i * 73) % 97) / 97, ((i * 41) % 89) / 89, 2 + (i * 7) % 5, .6 + ((i * 13) % 10) / 12]);

  function rivet(c, x, y, r) { c.fillStyle = RG(c, x, y, r, [[0, '#fff'], [.35, '#c9c2d8'], [.8, '#5b546a'], [1, '#2a2533']], x - r * .35, y - r * .4, 0); c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); }
  function liquidColor(p) { const h = Math.max(0, Math.min(120, p * 1.2)); return [hsl(h, 100, 62), hsl(h, 100, 45), hsl(h, 100, 30), h]; }
  function bolt(c, x, y, s, a) {
    c.save(); c.translate(x, y); c.scale(s / 36, s / 36); c.globalAlpha = a;
    c.shadowColor = '#ffb627'; c.shadowBlur = 4; c.fillStyle = '#fff8c9'; c.strokeStyle = '#ffb627'; c.lineWidth = 2;
    c.beginPath(); c.moveTo(14, 1); c.lineTo(2, 21); c.lineTo(10, 21); c.lineTo(7, 35); c.lineTo(22, 13); c.lineTo(13, 13); c.closePath(); c.fill(); c.stroke(); c.restore();
  }
  function pctText(c, x, y, size, pct, col) {
    c.save(); c.font = `${size}px Bungee, Impact, sans-serif`; c.textAlign = 'center'; c.textBaseline = 'middle';
    c.shadowColor = col; c.shadowBlur = size * .08; c.fillStyle = '#fff'; c.fillText(pct + '%', x, y);
    c.shadowBlur = 0; c.lineWidth = Math.max(1, size * .04); c.strokeStyle = 'rgba(0,0,0,.45)'; c.strokeText(pct + '%', x, y); c.restore();
  }
  // mini chiot endormi (posé sur la batterie)
  function sleepyPup(c, x, y, s, t) {
    c.save(); c.translate(x, y); c.scale(s, s * (1 + .02 * Math.sin(t * 1.6)));
    c.fillStyle = RG(c, 0, -10, 70, [[0, '#f6d0a0'], [.6, '#cf8f50'], [1, '#6b3812']]); c.beginPath(); c.ellipse(10, 0, 62, 30, 0, 0, 7); c.fill();
    for (const k of [-1, 1]) { c.fillStyle = '#7a3f17'; c.beginPath(); c.ellipse(-40 + k * 20, -10, 12, 24, k * .4, 0, 7); c.fill(); }
    c.fillStyle = RG(c, -42, -18, 40, [[0, '#fff4e2'], [.5, '#e9b77a'], [1, '#a8652e']]); c.beginPath(); c.ellipse(-40, -12, 32, 28, 0, 0, 7); c.fill();
    c.fillStyle = '#fff6ea'; c.beginPath(); c.ellipse(-44, -2, 18, 13, 0, 0, 7); c.fill();
    c.strokeStyle = '#2b1206'; c.lineWidth = 2.4; c.lineCap = 'round';
    for (const k of [-1, 1]) { c.beginPath(); c.moveTo(-40 + k * 11 - 6, -16); c.quadraticCurveTo(-40 + k * 11, -12, -40 + k * 11 + 6, -16); c.stroke(); }
    c.fillStyle = '#1a0b2e'; c.beginPath(); c.ellipse(-46, -6, 6, 4, 0, 0, 7); c.fill();
    c.fillStyle = 'rgba(255,110,170,.5)'; c.beginPath(); c.ellipse(-60, -4, 6, 4, 0, 0, 7); c.ellipse(-24, -4, 6, 4, 0, 0, 7); c.fill();
    c.restore();
  }

  /** Style 1 : gamelle de verre (batterie horizontale chromée) */
  function verre(c, w, h, pct, t, o) {
    const bw = w * .78, bh = bw * .44, x0 = (w - bw) / 2 - w * .03, y0 = h * .52 - bh / 2, r = bh * .22, pad = bh * .08;
    const [l1, l2, l3, hue] = liquidColor(pct);
    // halo
    // noir OLED : pas de halo autour
    // embout
    c.fillStyle = chrome(c, 0, y0 + bh * .3, 0, y0 + bh * .7); c.beginPath(); c.roundRect(x0 + bw - 2, y0 + bh * .3, bw * .07, bh * .4, [0, r * .5, r * .5, 0]); c.fill();
    // cadre chromé
    c.save();
    c.fillStyle = chrome(c, x0, y0, x0 + bw * .4, y0 + bh); c.beginPath(); c.roundRect(x0, y0, bw, bh, r); c.fill(); c.restore();
    for (const [rx, ry] of [[x0 + pad * 1.3, y0 + pad * 1.3], [x0 + bw - pad * 1.3, y0 + pad * 1.3], [x0 + pad * 1.3, y0 + bh - pad * 1.3], [x0 + bw - pad * 1.3, y0 + bh - pad * 1.3]]) rivet(c, rx, ry, bh * .035);
    // intérieur en verre
    const ix = x0 + pad * 2.2, iy = y0 + pad * 1.6, iw = bw - pad * 4.4, ih = bh - pad * 3.2, ir = r * .7;
    const inner = () => { c.beginPath(); c.roundRect(ix, iy, iw, ih, ir); };
    c.fillStyle = LG(c, 0, iy, 0, iy + ih, [[0, '#120a20'], [1, '#030106']]); inner(); c.fill();
    c.save(); inner(); c.clip();
    // liquide avec bord ondulé
    const fw = iw * Math.max(.02, pct / 100);
    c.fillStyle = LG(c, 0, iy, 0, iy + ih, [[0, l1], [.45, l2], [.55, l2], [1, l3]]);
    c.beginPath(); c.moveTo(ix, iy); c.lineTo(ix + fw, iy);
    for (let k = 0; k <= 20; k++) { const yy = iy + ih * k / 20; c.lineTo(ix + fw + Math.sin(k / 20 * Math.PI * 4 + t * 4) * ih * .04, yy); }
    c.lineTo(ix, iy + ih); c.closePath(); c.fill();
    c.fillStyle = LG(c, 0, iy, 0, iy + ih * .5, [[0, 'rgba(255,255,255,.55)'], [1, 'rgba(255,255,255,0)']]); c.fillRect(ix, iy, fw, ih * .5);
    for (const [bx, by, br, sp] of B) { const yy = iy + ih - ((by + t * sp * .25) % 1) * ih; const xx = ix + bx * fw * .95; if (xx > ix + fw - 4) continue; c.fillStyle = RG(c, xx - br * .3, yy - br * .3, br, [[0, 'rgba(255,255,255,.9)'], [1, 'rgba(255,255,255,.1)']]); c.beginPath(); c.arc(xx, yy, br * ih / 120, 0, 7); c.fill(); }
    // graduations
    c.strokeStyle = 'rgba(255,255,255,.18)'; c.lineWidth = 1.5; for (let k = 1; k < 10; k++) { const gx = ix + iw * k / 10; c.beginPath(); c.moveTo(gx, iy + ih * .82); c.lineTo(gx, iy + ih); c.stroke(); }
    c.restore();
    // reflet du verre
    c.save(); inner(); c.clip(); c.fillStyle = LG(c, ix, iy, ix + iw * .3, iy + ih, [[0, 'rgba(255,255,255,.28)'], [.38, 'rgba(255,255,255,.05)'], [.39, 'rgba(255,255,255,0)']]); c.fillRect(ix, iy, iw, ih); c.restore();
    c.lineWidth = 2; c.strokeStyle = 'rgba(0,0,0,.8)'; inner(); c.stroke();
    if (o.text !== false) pctText(c, ix + iw / 2, iy + ih / 2, ih * .5, pct, hsl(hue, 100, 55));
    if (o.charging) bolt(c, ix + iw - ih * .45, iy + ih * .18, ih * .62, .6 + .4 * Math.sin(t * 3));
    sleepyPup(c, x0 + bw * .26, y0 - bh * .06, bw / 520, t);
  }

  /** Style 2 : os lumineux (batterie en forme d'os, tube de verre) */
  function os(c, w, h, pct, t, o) {
    const L = w * .6, R = w * .078, cx = w / 2, cy = h * .52, x0 = cx - L / 2, x1 = cx + L / 2, tube = R * 1.75;
    const [l1, l2, l3, hue] = liquidColor(pct);
    const bone = (grow = 0) => {
      c.beginPath(); c.roundRect(x0, cy - tube / 2 - grow, L, tube + grow * 2, tube / 2);
      for (const [bx, by] of [[x0, cy - R * .72], [x0, cy + R * .72], [x1, cy - R * .72], [x1, cy + R * .72]]) { c.moveTo(bx + R + grow, by); c.arc(bx, by, R + grow, 0, 7); }
    };
    // noir OLED : pas de halo autour
    // coque chromée
    c.save(); c.fillStyle = chrome(c, x0 - R, cy - R * 2, x0 + L * .3, cy + R * 2); bone(R * .16); c.fill('nonzero'); c.restore();
    // verre sombre
    c.fillStyle = LG(c, 0, cy - R * 2, 0, cy + R * 2, [[0, '#120a20'], [1, '#030106']]); bone(); c.fill('nonzero');
    // liquide
    c.save(); bone(); c.clip('nonzero');
    const fx = x0 - R * 1.2 + (L + R * 2.4) * Math.max(.02, pct / 100);
    c.fillStyle = LG(c, 0, cy - R * 1.8, 0, cy + R * 1.8, [[0, l1], [.45, l2], [.55, l2], [1, l3]]);
    c.beginPath(); c.moveTo(x0 - R * 2, cy - R * 2); c.lineTo(fx, cy - R * 2);
    for (let k = 0; k <= 24; k++) { const yy = cy - R * 2 + R * 4 * k / 24; c.lineTo(fx + Math.sin(k / 24 * Math.PI * 5 + t * 4) * R * .12, yy); }
    c.lineTo(x0 - R * 2, cy + R * 2); c.closePath(); c.fill();
    c.fillStyle = LG(c, 0, cy - R * 1.8, 0, cy, [[0, 'rgba(255,255,255,.5)'], [1, 'rgba(255,255,255,0)']]); c.fillRect(x0 - R * 2, cy - R * 2, fx - x0 + R * 2, R * 2);
    for (const [bx, by, br, sp] of B) { const xx = x0 - R + bx * (fx - x0 + R), yy = cy + R * 1.6 - ((by + t * sp * .25) % 1) * R * 3.2; if (xx > fx - 4) continue; c.fillStyle = 'rgba(255,255,255,.55)'; c.beginPath(); c.arc(xx, yy, br * R / 40, 0, 7); c.fill(); }
    // reflets
    c.fillStyle = 'rgba(255,255,255,.22)'; c.beginPath(); c.roundRect(x0 + R * .4, cy - tube / 2 + tube * .12, L - R * .8, tube * .18, tube * .09); c.fill();
    for (const [bx, by] of [[x0, cy - R * .78], [x1, cy - R * .78]]) { c.fillStyle = 'rgba(255,255,255,.3)'; c.beginPath(); c.ellipse(bx - R * .3, by - R * .4, R * .35, R * .18, -.6, 0, 7); c.fill(); }
    c.restore();
    // pattes gravées sur les embouts
    for (const bx of [x0, x1]) { c.fillStyle = 'rgba(255,255,255,.18)'; const px = bx, py = cy, r = R * .3; c.beginPath(); c.ellipse(px, py + r * .3, r, r * .8, 0, 0, 7); for (const [a, b] of [[-1.05, -.62], [-.38, -1.2], [.38, -1.2], [1.05, -.62]]) { c.moveTo(px + a * r + r * .4, py + b * r); c.arc(px + a * r, py + b * r, r * .4, 0, 7); } c.fill(); }
    if (o.text !== false) pctText(c, cx, cy + 2, tube * .58, pct, hsl(hue, 100, 55));
    sleepyPup(c, cx + L * .1, cy - tube / 2 - R * .42, w / 640, t);
    if (o.charging) bolt(c, x1 - R * 2.2, cy - R * 2.3, R * 1.1, .6 + .4 * Math.sin(t * 3));
    // médaille qui pend
    const sw = Math.sin(t * 1.4) * .12;
    c.save(); c.translate(cx, cy + tube / 2 + R * .1); c.rotate(sw);
    c.strokeStyle = chrome(c, -4, 0, 4, 0); c.lineWidth = 3; c.beginPath(); c.moveTo(0, 0); c.lineTo(0, R * .9); c.stroke();
    c.fillStyle = RG(c, -R * .12, R * 1.18, R * .5, [[0, '#fff'], [.35, o.acc || '#ff3fa4'], [1, '#3a0620']]); c.beginPath(); c.arc(0, R * 1.3, R * .42, 0, 7); c.fill();
    c.lineWidth = 3; c.strokeStyle = chrome(c, -R * .4, 0, R * .4, 0); c.stroke();
    c.restore();
  }

  window.AodBat = {
    draw(c, style, w, h, pct, t, o = {}) {
      c.save(); if (o.transparent) c.clearRect(0, 0, w, h); else { c.fillStyle = '#000'; c.fillRect(0, 0, w, h); }
      (style === 'os' ? os : verre)(c, w, h, Math.round(pct), t, o);
      c.restore();
    },
  };
})();
