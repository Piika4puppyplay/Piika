/* PupScan — scanner de documents + lecteur de QR codes, 100 % hors ligne */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const P = window.Pup || {
    beginDoc() {}, addPage() { return true; }, saveDoc(n) { return JSON.stringify({ ok: true, uri: 'content://demo/' + n + '.pdf', name: n + '.pdf' }); },
    saveJpg(d, n) { return JSON.stringify({ ok: true, uri: 'content://demo/' + n + '.jpg', name: n + '.jpg' }); },
    openFile() {}, shareFile() {}, share() {}, copy() {}, openUrl(u) { window.open(u); }, haptic() {}, wifiSettings() {},
  };
  const p2 = (n) => String(n).padStart(2, '0');
  const KEY = 'pupscan';
  let H = { scans: [], qr: [] };
  try { H = Object.assign(H, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch (e) { }
  const saveH = () => { try { localStorage.setItem(KEY, JSON.stringify(H)); } catch (e) { H.scans = H.scans.slice(0, 20); try { localStorage.setItem(KEY, JSON.stringify(H)); } catch (e2) { } } };
  function toast(msg) { let t = $('.toast'); if (!t) { t = document.createElement('div'); t.className = 'toast'; document.body.appendChild(t); } t.textContent = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 2800); }
  const show = (id) => { for (const s of ['cam', 'crop', 'doc']) $('#' + s).classList.toggle('hidden', s !== id); if (id === 'cam') startCam(); else stopCam(); };
  const mkc = (w, h) => { const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(w)); c.height = Math.max(1, Math.round(h)); return c; };

  // =============================================================== caméra
  const V = $('#v'), OV = $('#ov');
  let stream = null, track = null, mode = 'doc', torchOn = false, loopT = 0, quad = null, sq = null, frozen = false;
  async function startCam() {
    if (stream || document.hidden) return;
    $('#nocam').classList.add('hidden');
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } } });
      track = stream.getVideoTracks()[0];
      V.srcObject = stream; await V.play().catch(() => { });
      try { track.applyConstraints({ advanced: [{ focusMode: 'continuous' }] }).catch(() => { }); } catch (e) { }
      const caps = track.getCapabilities ? track.getCapabilities() : {};
      $('#torch').style.visibility = caps.torch ? '' : 'hidden';
      torchOn = false; $('#torch').classList.remove('on');
      clearInterval(loopT); loopT = setInterval(tick, 160);
    } catch (e) {
      stream = null; $('#nocam').classList.remove('hidden');
      $('#nocamtxt').textContent = /NotAllowed|Permission/i.test(e && e.name) ? 'Autorise la caméra pour que le chiot puisse renifler tes documents.' : 'Pas de caméra disponible pour le moment. Tu peux quand même choisir une photo dans la galerie.';
    }
  }
  function stopCam() { clearInterval(loopT); if (stream) { stream.getTracks().forEach((t) => t.stop()); stream = null; track = null; } quad = sq = null; drawOv(); }
  document.addEventListener('visibilitychange', () => { if (document.hidden) stopCam(); else if (!$('#cam').classList.contains('hidden')) startCam(); });

  function bubble(txt, found) { $('#bub').textContent = txt; $('#pup').classList.toggle('found', !!found); $('#led').classList.toggle('on', !!found); }
  const small = mkc(10, 10), sx = small.getContext('2d', { willReadFrequently: true });
  let qrBusy = false, lastQR = '';
  function tick() {
    if (!V.videoWidth || frozen) return;
    if (mode === 'qr') {
      if (qrBusy) return; qrBusy = true;
      const w = Math.min(720, V.videoWidth), h = Math.round(V.videoHeight * w / V.videoWidth);
      small.width = w; small.height = h; sx.drawImage(V, 0, 0, w, h);
      const r = window.jsQR ? jsQR(sx.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: 'attemptBoth' }) : null;
      qrBusy = false;
      if (r && r.data) { const s = V.videoWidth / w; quad = [r.location.topLeftCorner, r.location.topRightCorner, r.location.bottomRightCorner, r.location.bottomLeftCorner].map((p) => [p.x * s, p.y * s]); sq = quad; drawOv(); found(r.data); }
      else { quad = null; sq = null; drawOv(); }
      return;
    }
    const w = 220, h = Math.round(V.videoHeight * w / V.videoWidth);
    small.width = w; small.height = h; sx.drawImage(V, 0, 0, w, h);
    const q = detect(sx.getImageData(0, 0, w, h), w, h);
    if (q) {
      const s = V.videoWidth / w, nq = q.map(([x, y]) => [x * s, y * s]);
      sq = sq ? sq.map((p, i) => [p[0] + (nq[i][0] - p[0]) * .45, p[1] + (nq[i][1] - p[1]) * .45]) : nq; quad = nq;
      bubble('Flair ! Document trouvé 🐾', true);
    } else { quad = null; sq = null; bubble('Je renifle… 🐽', false); }
    drawOv();
  }
  function drawOv() {
    const r = OV.getBoundingClientRect(), dpr = devicePixelRatio || 1;
    if (OV.width !== Math.round(r.width * dpr)) { OV.width = Math.round(r.width * dpr); OV.height = Math.round(r.height * dpr); }
    const c = OV.getContext('2d'); c.clearRect(0, 0, OV.width, OV.height);
    if (!sq || !V.videoWidth) return;
    const sc = Math.max(OV.width / V.videoWidth, OV.height / V.videoHeight), ox = (OV.width - V.videoWidth * sc) / 2, oy = (OV.height - V.videoHeight * sc) / 2;
    const pts = sq.map(([x, y]) => [ox + x * sc, oy + y * sc]);
    const acc = getComputedStyle(document.body).getPropertyValue('--acc').trim() || '#ff3fa4';
    c.save(); c.beginPath(); pts.forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y))); c.closePath();
    c.fillStyle = 'rgba(61,255,176,.16)'; c.fill();
    c.lineWidth = 4 * dpr; c.strokeStyle = mode === 'qr' ? '#3dffb0' : acc; c.shadowColor = c.strokeStyle; c.shadowBlur = 16 * dpr; c.stroke(); c.restore();
    for (const [x, y] of pts) pawDot(c, x, y, 13 * dpr, acc);
  }
  function pawDot(c, x, y, r, col) {
    c.save(); c.translate(x, y);
    c.fillStyle = 'rgba(0,0,0,.45)'; c.beginPath(); c.arc(0, 2, r * 1.15, 0, 7); c.fill();
    const g = c.createRadialGradient(-r * .35, -r * .4, 1, 0, 0, r); g.addColorStop(0, '#fff'); g.addColorStop(.35, col); g.addColorStop(1, '#3a0820');
    c.fillStyle = g; c.beginPath(); c.arc(0, 0, r, 0, 7); c.fill(); c.lineWidth = r * .14; c.strokeStyle = '#000'; c.stroke();
    c.fillStyle = '#fff'; c.beginPath(); c.ellipse(0, r * .22, r * .34, r * .27, 0, 0, 7); c.fill();
    for (const [a, d] of [[-2.35, .5], [-1.85, .58], [-1.3, .58], [-.8, .5]]) { c.beginPath(); c.ellipse(Math.cos(a) * r * d, Math.sin(a) * r * d + r * .12, r * .12, r * .15, 0, 0, 7); c.fill(); }
    c.restore();
  }

  // ------------------------------------------- flair : détection de la feuille
  /** Seuil d'Otsu → plus grande tache claire → 4 coins extrêmes. Renvoie [[x,y]×4] TL,TR,BR,BL ou null. */
  function detect(id, w, h) {
    const d = id.data, n = w * h, g = new Uint8Array(n), hist = new Uint32Array(256);
    for (let i = 0, j = 0; i < n; i++, j += 4) { const v = (d[j] * 77 + d[j + 1] * 150 + d[j + 2] * 29) >> 8; g[i] = v; hist[v]++; }
    let sum = 0; for (let i = 0; i < 256; i++) sum += i * hist[i];
    let sB = 0, wB = 0, best = 0, th = 128;
    for (let i = 0; i < 256; i++) { wB += hist[i]; if (!wB) continue; const wF = n - wB; if (!wF) break; sB += i * hist[i]; const mB = sB / wB, mF = (sum - sB) / wF, v = wB * wF * (mB - mF) * (mB - mF); if (v > best) { best = v; th = i; } }
    if (best / n / n < 120) return null; // pas assez de contraste
    const lab = new Int32Array(n), st = new Int32Array(n);
    let bestL = 0, bestA = 0, L = 0;
    for (let i = 0; i < n; i++) {
      if (lab[i] || g[i] <= th) continue;
      L++; let sp = 0, a = 0; st[sp++] = i; lab[i] = L;
      while (sp) {
        const k = st[--sp]; a++; const x = k % w, y = (k / w) | 0;
        if (x > 0 && !lab[k - 1] && g[k - 1] > th) { lab[k - 1] = L; st[sp++] = k - 1; }
        if (x < w - 1 && !lab[k + 1] && g[k + 1] > th) { lab[k + 1] = L; st[sp++] = k + 1; }
        if (y > 0 && !lab[k - w] && g[k - w] > th) { lab[k - w] = L; st[sp++] = k - w; }
        if (y < h - 1 && !lab[k + w] && g[k + w] > th) { lab[k + w] = L; st[sp++] = k + w; }
      }
      if (a > bestA) { bestA = a; bestL = L; }
    }
    if (bestA < n * .12 || bestA > n * .94) return null;
    let tl = [0, 0, 1e9], tr = [0, 0, -1e9], br = [0, 0, -1e9], bl = [0, 0, -1e9], touch = 0;
    for (let i = 0; i < n; i++) {
      if (lab[i] !== bestL) continue; const x = i % w, y = (i / w) | 0;
      if (x === 0 || y === 0 || x === w - 1 || y === h - 1) touch++;
      if (x + y < tl[2]) tl = [x, y, x + y]; if (x - y > tr[2]) tr = [x, y, x - y]; if (x + y > br[2]) br = [x, y, x + y]; if (y - x > bl[2]) bl = [x, y, y - x];
    }
    if (touch > (w + h) * .9) return null; // la tache déborde de partout : c'est la table, pas la feuille
    const q = [tl, tr, br, bl].map((p) => [p[0] + .5, p[1] + .5]);
    return polyArea(q) > n * .1 ? q : null;
  }
  const polyArea = (q) => Math.abs(q.reduce((s, p, i) => { const r = q[(i + 1) % 4]; return s + p[0] * r[1] - r[0] * p[1]; }, 0)) / 2;

  // ---------------------------------------------------------- capture
  async function capture() {
    if (!stream) return;
    P.haptic(); const f = document.createElement('div'); f.className = 'flash'; document.body.appendChild(f); setTimeout(() => f.remove(), 400);
    let src = null;
    try { if (window.ImageCapture) { const blob = await new ImageCapture(track).takePhoto(); src = await createImageBitmap(blob); } } catch (e) { src = null; }
    if (!src) { const c = mkc(V.videoWidth, V.videoHeight); c.getContext('2d').drawImage(V, 0, 0); src = c; }
    const hint = sq && src.width ? sq.map(([x, y]) => [x * src.width / V.videoWidth, y * src.height / V.videoHeight]) : null;
    openCrop(src, hint);
  }

  // ---------------------------------------------------------- recadrage
  const CC = $('#cc'), LC = $('#lc');
  let img = null, cq = null, drag = -1, view = { s: 1, x: 0, y: 0 }, editIdx = -1;
  function openCrop(src, hint, idx) {
    const m = Math.max(src.width, src.height), k = Math.min(1, 3000 / m);
    img = mkc(src.width * k, src.height * k); img.getContext('2d').drawImage(src, 0, 0, img.width, img.height);
    editIdx = idx == null ? -1 : idx;
    cq = hint ? hint.map(([x, y]) => [x * k, y * k]) : (autoQuad() || fullQuad());
    show('crop'); requestAnimationFrame(drawCrop);
  }
  const fullQuad = () => { const w = img.width, h = img.height, m = Math.min(w, h) * .04; return [[m, m], [w - m, m], [w - m, h - m], [m, h - m]]; };
  function autoQuad() {
    const w = 260, h = Math.round(img.height * w / img.width), c = mkc(w, h), x = c.getContext('2d'); x.drawImage(img, 0, 0, w, h);
    const q = detect(x.getImageData(0, 0, w, h), w, h); if (!q) return null;
    const s = img.width / w; return q.map(([a, b]) => [a * s, b * s]);
  }
  function drawCrop() {
    const r = CC.getBoundingClientRect(), dpr = devicePixelRatio || 1;
    CC.width = Math.round(r.width * dpr); CC.height = Math.round(r.height * dpr);
    const c = CC.getContext('2d'), pad = 26 * dpr, s = Math.min((CC.width - pad * 2) / img.width, (CC.height - pad * 2) / img.height);
    view = { s, x: (CC.width - img.width * s) / 2, y: (CC.height - img.height * s) / 2, dpr };
    c.clearRect(0, 0, CC.width, CC.height);
    c.save(); c.shadowColor = 'rgba(0,0,0,.8)'; c.shadowBlur = 20 * dpr; c.drawImage(img, view.x, view.y, img.width * s, img.height * s); c.restore();
    const pts = cq.map(([x, y]) => [view.x + x * s, view.y + y * s]);
    c.save(); c.fillStyle = 'rgba(8,2,16,.55)'; c.beginPath(); c.rect(0, 0, CC.width, CC.height); pts.slice().reverse().forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y))); c.closePath(); c.fill('evenodd'); c.restore();
    const acc = getComputedStyle(document.body).getPropertyValue('--acc').trim() || '#ff3fa4';
    c.save(); c.beginPath(); pts.forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y))); c.closePath(); c.lineWidth = 3 * dpr; c.strokeStyle = acc; c.shadowColor = acc; c.shadowBlur = 12 * dpr; c.stroke(); c.restore();
    c.save(); c.setLineDash([6 * dpr, 6 * dpr]); c.strokeStyle = 'rgba(255,255,255,.5)'; c.lineWidth = 1 * dpr;
    for (const t of [1 / 3, 2 / 3]) { const a = lerp(pts[0], pts[3], t), b = lerp(pts[1], pts[2], t), e = lerp(pts[0], pts[1], t), f = lerp(pts[3], pts[2], t); c.beginPath(); c.moveTo(a[0], a[1]); c.lineTo(b[0], b[1]); c.moveTo(e[0], e[1]); c.lineTo(f[0], f[1]); c.stroke(); }
    c.restore();
    pts.forEach(([x, y], i) => pawDot(c, x, y, (drag === i ? 19 : 16) * dpr, acc));
  }
  const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
  function cropPt(e) { const r = CC.getBoundingClientRect(), d = view.dpr; return [((e.clientX - r.left) * d - view.x) / view.s, ((e.clientY - r.top) * d - view.y) / view.s]; }
  CC.addEventListener('pointerdown', (e) => {
    const p = cropPt(e); let bd = 1e9;
    cq.forEach((q, i) => { const dd = Math.hypot(q[0] - p[0], q[1] - p[1]); if (dd < bd) { bd = dd; drag = i; } });
    if (bd * view.s / view.dpr > 70) { drag = -1; return; }
    CC.setPointerCapture(e.pointerId); move(e);
  });
  CC.addEventListener('pointermove', (e) => { if (drag >= 0) move(e); });
  const end = () => { drag = -1; $('#loupe').classList.add('hidden'); drawCrop(); };
  CC.addEventListener('pointerup', end); CC.addEventListener('pointercancel', end);
  function move(e) {
    const p = cropPt(e); cq[drag] = [Math.max(0, Math.min(img.width, p[0])), Math.max(0, Math.min(img.height, p[1]))];
    drawCrop();
    const L = $('#loupe'), r = CC.getBoundingClientRect(), q = cq[drag];
    L.classList.remove('hidden');
    const lx = (view.x + q[0] * view.s) / view.dpr, ly = (view.y + q[1] * view.s) / view.dpr;
    L.style.left = Math.max(4, Math.min(r.width - 136, lx - 66)) + 'px'; L.style.top = (ly > 170 ? ly - 160 : ly + 30) + 'px';
    const c = LC.getContext('2d'), reg = 132 / (2.5 * view.s / view.dpr); c.fillStyle = '#000'; c.fillRect(0, 0, 132, 132);
    c.drawImage(img, q[0] - reg / 2, q[1] - reg / 2, reg, reg, 0, 0, 132, 132);
    c.strokeStyle = '#ff3fa4'; c.lineWidth = 2; c.beginPath(); c.moveTo(66, 50); c.lineTo(66, 82); c.moveTo(50, 66); c.lineTo(82, 66); c.stroke();
  }
  addEventListener('resize', () => { if (!$('#crop').classList.contains('hidden')) drawCrop(); drawOv(); });

  // ------------------------------------------------------- redressement
  /** Homographie carré unité → quadrilatère (Heckbert). */
  function homo(q) {
    const [[x0, y0], [x1, y1], [x2, y2], [x3, y3]] = q, sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3;
    if (Math.abs(sx) < 1e-9 && Math.abs(sy) < 1e-9) return [x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0, 0];
    const dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2, den = dx1 * dy2 - dx2 * dy1, g = (sx * dy2 - dx2 * sy) / den, h = (dx1 * sy - sx * dy1) / den;
    return [x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h];
  }
  function warp(src, q) {
    const dst = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
    let W = Math.max(dst(q[0], q[1]), dst(q[3], q[2])), Hh = Math.max(dst(q[0], q[3]), dst(q[1], q[2]));
    const k = Math.min(1, 2300 / Math.max(W, Hh)); W = Math.round(W * k); Hh = Math.round(Hh * k);
    const S = src.getContext('2d').getImageData(0, 0, src.width, src.height), sd = S.data, sw = src.width, sh = src.height;
    const out = mkc(W, Hh), oc = out.getContext('2d'), O = oc.createImageData(W, Hh), od = O.data;
    const [a, b, c, d, e, f, g, h] = homo(q);
    for (let y = 0; y < Hh; y++) {
      const v = (y + .5) / Hh;
      for (let x = 0; x < W; x++) {
        const u = (x + .5) / W, z = g * u + h * v + 1;
        let fx = (a * u + b * v + c) / z - .5, fy = (d * u + e * v + f) / z - .5;
        if (fx < 0) fx = 0; if (fy < 0) fy = 0; if (fx > sw - 1.001) fx = sw - 1.001; if (fy > sh - 1.001) fy = sh - 1.001;
        const ix = fx | 0, iy = fy | 0, tx = fx - ix, ty = fy - iy, i0 = (iy * sw + ix) * 4, i1 = i0 + sw * 4, o = (y * W + x) * 4;
        for (let ch = 0; ch < 3; ch++) {
          const top = sd[i0 + ch] + (sd[i0 + 4 + ch] - sd[i0 + ch]) * tx, bot = sd[i1 + ch] + (sd[i1 + 4 + ch] - sd[i1 + ch]) * tx;
          od[o + ch] = top + (bot - top) * ty;
        }
        od[o + 3] = 255;
      }
    }
    oc.putImageData(O, 0, 0); return out;
  }

  // ---------------------------------------------------------- filtres
  const FILTERS = [['magie', '✨ Magie'], ['couleur', '🎨 Original'], ['gris', '🌫️ Gris'], ['nb', '🖨️ N&B'], ['encre', '🐾 Encre']];
  function applyF(src, f) {
    const w = src.width, h = src.height, out = mkc(w, h), c = out.getContext('2d'); c.drawImage(src, 0, 0);
    if (f === 'couleur') return out;
    const D = c.getImageData(0, 0, w, h), d = D.data, n = w * h;
    if (f === 'magie' || f === 'gris') {
      const hs = [new Uint32Array(256), new Uint32Array(256), new Uint32Array(256)];
      for (let i = 0; i < n * 4; i += 4) { hs[0][d[i]]++; hs[1][d[i + 1]]++; hs[2][d[i + 2]]++; }
      const pct = (hh, p) => { let s = 0; const t = n * p; for (let i = 0; i < 256; i++) { s += hh[i]; if (s >= t) return i; } return 255; };
      const lo = hs.map((hh) => pct(hh, .02)), hi = hs.map((hh) => Math.max(pct(hh, .9), 40));
      const lut = [0, 1, 2].map((k) => { const L = new Uint8ClampedArray(256); for (let i = 0; i < 256; i++) { let v = (i - lo[k]) / Math.max(1, hi[k] - lo[k]); v = Math.max(0, Math.min(1, v)); L[i] = 255 * Math.pow(v, .9); } return L; });
      for (let i = 0; i < n * 4; i += 4) {
        let r = lut[0][d[i]], g = lut[1][d[i + 1]], b = lut[2][d[i + 2]];
        if (f === 'gris') { const y = r * .3 + g * .59 + b * .11; d[i] = d[i + 1] = d[i + 2] = y; continue; }
        const y = r * .3 + g * .59 + b * .11;
        if (y > 225) { r = g = b = 255; } else { r = y + (r - y) * 1.25; g = y + (g - y) * 1.25; b = y + (b - y) * 1.25; }
        d[i] = r; d[i + 1] = g; d[i + 2] = b;
      }
    } else {
      // seuil adaptatif (image intégrale) : texte bien net même avec une ombre
      const gr = new Uint8Array(n); for (let i = 0, j = 0; i < n; i++, j += 4) gr[i] = (d[j] * 77 + d[j + 1] * 150 + d[j + 2] * 29) >> 8;
      const I = new Uint32Array((w + 1) * (h + 1));
      for (let y = 1; y <= h; y++) { let row = 0; for (let x = 1; x <= w; x++) { row += gr[(y - 1) * w + x - 1]; I[y * (w + 1) + x] = I[(y - 1) * (w + 1) + x] + row; } }
      const s = Math.max(8, Math.round(Math.max(w, h) / 28)), T = f === 'encre' ? .9 : .86;
      const ink = f === 'encre' ? [28, 22, 70] : [0, 0, 0];
      for (let y = 0; y < h; y++) {
        const y0 = Math.max(0, y - s), y1 = Math.min(h, y + s + 1);
        for (let x = 0; x < w; x++) {
          const x0 = Math.max(0, x - s), x1 = Math.min(w, x + s + 1), cnt = (x1 - x0) * (y1 - y0);
          const sum = I[y1 * (w + 1) + x1] - I[y0 * (w + 1) + x1] - I[y1 * (w + 1) + x0] + I[y0 * (w + 1) + x0];
          const k = (y * w + x), o = k * 4, dark = gr[k] * cnt < sum * T;
          if (dark) { d[o] = ink[0]; d[o + 1] = ink[1]; d[o + 2] = ink[2]; } else { d[o] = d[o + 1] = d[o + 2] = 255; }
        }
      }
    }
    c.putImageData(D, 0, 0); return out;
  }
  function rotC(src, r) {
    if (!r) return src; const sw = r % 2, out = mkc(sw ? src.height : src.width, sw ? src.width : src.height), c = out.getContext('2d');
    c.translate(out.width / 2, out.height / 2); c.rotate(r * Math.PI / 2); c.drawImage(src, -src.width / 2, -src.height / 2); return out;
  }

  // ---------------------------------------------------------- document
  let pages = [], cur = 0;
  const outC = (p) => { const k = p.f + p.r; if (p.cache !== k) { p.out = rotC(applyF(p.src, p.f), p.r); p.cache = k; } return p.out; };
  function addFromCrop() {
    const busy = $('#busy');
    show('doc'); busy.classList.remove('hidden');
    setTimeout(() => {
      const w = warp(img, cq);
      if (editIdx >= 0 && pages[editIdx]) { Object.assign(pages[editIdx], { src: w, raw: img, q: cq.map((p) => p.slice()), cache: '' }); cur = editIdx; }
      else { pages.push({ src: w, raw: img, q: cq.map((p) => p.slice()), f: 'magie', r: 0, cache: '' }); cur = pages.length - 1; }
      if (!$('#dname').value) { const d = new Date(); $('#dname').value = `Scan ${p2(d.getDate())}-${p2(d.getMonth() + 1)} ${p2(d.getHours())}h${p2(d.getMinutes())}`; }
      renderDoc(); busy.classList.add('hidden');
    }, 60);
  }
  function renderDoc() {
    if (!pages.length) { show('cam'); updStack(); return; }
    cur = Math.max(0, Math.min(cur, pages.length - 1));
    const p = pages[cur];
    $('#pg').src = outC(p).toDataURL('image/jpeg', .85);
    $('#pgi').textContent = `${cur + 1} / ${pages.length}`;
    const th = mkc(140, 140 * p.src.height / p.src.width), tc = th.getContext('2d'); tc.drawImage(p.src, 0, 0, th.width, th.height);
    $('#filters').innerHTML = FILTERS.map(([k, l], i) => `<button class="fl ${p.f === k ? 'on' : ''}" data-f="${k}" type="button" style="--r:${[-3, 2, -1.5, 3, -2][i]}deg"><img src="${applyF(th, k).toDataURL('image/jpeg', .8)}" alt=""><span>${l}</span></button>`).join('');
    updStack();
  }
  function updStack() { $('#np').textContent = pages.length; $('#stack').classList.toggle('hidden', !pages.length); }
  function exportDoc(kind) {
    if (!pages.length) return;
    const name = ($('#dname').value || 'Scan').trim();
    $('#busy').classList.remove('hidden');
    setTimeout(() => {
      let res, first = outC(pages[0]);
      const thumb = mkc(110, 110 * first.height / first.width); thumb.getContext('2d').drawImage(first, 0, 0, thumb.width, thumb.height);
      if (kind === 'pdf') {
        P.beginDoc(); for (const p of pages) P.addPage(outC(p).toDataURL('image/jpeg', .88));
        res = JSON.parse(P.saveDoc(name));
        if (res.ok) H.scans.unshift({ id: Date.now(), name: res.name, uri: res.uri, mime: 'application/pdf', n: pages.length, at: Date.now(), th: thumb.toDataURL('image/jpeg', .7) });
      } else {
        for (let i = 0; i < pages.length; i++) {
          res = JSON.parse(P.saveJpg(outC(pages[i]).toDataURL('image/jpeg', .92), pages.length > 1 ? `${name} (${i + 1})` : name));
          if (res.ok) H.scans.unshift({ id: Date.now() + i, name: res.name, uri: res.uri, mime: 'image/jpeg', n: 1, at: Date.now(), th: i ? '' : thumb.toDataURL('image/jpeg', .7) });
        }
      }
      $('#busy').classList.add('hidden');
      if (!res || !res.ok) { toast('Oups, le chiot n’a pas pu ranger le fichier 😿 ' + ((res && res.msg) || '')); return; }
      H.scans = H.scans.slice(0, 60); saveH();
      const it = H.scans[0];
      veil(`<div class="sheet"><h3>Rangé dans la niche ! 🦴</h3>
        <div class="hi"><img src="${it.th || ''}" alt=""><div class="tx"><b>${esc(it.name)}</b><span>${kind === 'pdf' ? 'Documents › PupScan · ' + it.n + ' page' + (it.n > 1 ? 's' : '') : 'Images › PupScan'}</span></div></div>
        <div class="nrow"><button class="ab" data-h="open" data-i="${it.id}" type="button">👀 Ouvrir</button><button class="ab c2" data-h="share" data-i="${it.id}" type="button">📤 Partager</button></div>
        <button class="ab wide glass" data-v="new" type="button">📄 Nouveau scan</button></div>`);
    }, 60);
  }

  // ---------------------------------------------------------- QR codes
  function parseQR(t) {
    let m;
    if (/^https?:\/\//i.test(t)) return { k: '🔗', ty: 'Lien', main: t, act: [['url', '🌐 Ouvrir le lien', t]] };
    if ((m = /^WIFI:(.*);?;?$/i.exec(t))) {
      const g = (k) => { const r = new RegExp('(?:^|;)' + k + ':((?:\\\\.|[^;])*)', 'i').exec(m[1]); return r ? r[1].replace(/\\(.)/g, '$1') : ''; };
      const s = g('S'), p = g('P'), ty = g('T') || 'ouvert';
      return { k: '📶', ty: 'Wi-Fi', dl: [['Réseau', s], ['Mot de passe', p || '(aucun)'], ['Sécurité', ty]], act: [['wifi', '🔑 Copier le mot de passe et ouvrir le Wi-Fi', p]] };
    }
    if (/^tel:/i.test(t)) return { k: '📞', ty: 'Téléphone', main: t.slice(4), act: [['url', '📞 Appeler', t]] };
    if (/^mailto:/i.test(t)) return { k: '✉️', ty: 'E-mail', main: t.slice(7), act: [['url', '✉️ Écrire', t]] };
    if ((m = /^MATMSG:TO:([^;]*);SUB:([^;]*);BODY:([^;]*)/i.exec(t))) return { k: '✉️', ty: 'E-mail', dl: [['À', m[1]], ['Sujet', m[2]], ['Message', m[3]]], act: [['url', '✉️ Écrire', `mailto:${m[1]}?subject=${encodeURIComponent(m[2])}&body=${encodeURIComponent(m[3])}`]] };
    if ((m = /^SMSTO:([^:]*):?(.*)$/i.exec(t))) return { k: '💬', ty: 'SMS', dl: [['À', m[1]], ['Message', m[2]]], act: [['url', '💬 Écrire le SMS', `sms:${m[1]}?body=${encodeURIComponent(m[2])}`]] };
    if (/^geo:/i.test(t)) return { k: '📍', ty: 'Lieu', main: t.slice(4), act: [['url', '🗺️ Ouvrir la carte', t]] };
    if (/^BEGIN:VCARD/i.test(t)) {
      const f = (k) => { const r = new RegExp('^' + k + '[^:]*:(.*)$', 'im').exec(t); return r ? r[1].trim() : ''; };
      const dl = [['Nom', f('FN') || f('N').replace(/;/g, ' ')], ['Tél', f('TEL')], ['E-mail', f('EMAIL')], ['Société', f('ORG')]].filter((x) => x[1]);
      return { k: '👤', ty: 'Contact', dl, act: f('TEL') ? [['url', '📞 Appeler', 'tel:' + f('TEL')]] : [] };
    }
    if (/^[\w.-]+\.[a-z]{2,}(\/\S*)?$/i.test(t)) return { k: '🔗', ty: 'Lien', main: t, act: [['url', '🌐 Ouvrir le lien', 'https://' + t]] };
    return { k: '📝', ty: 'Texte', main: t, act: [] };
  }
  let curQR = '';
  function found(t) {
    if (frozen) return;
    frozen = true; P.haptic(); bubble('Wouf ! QR code trouvé 🐾', true);
    if (t !== lastQR || !H.qr.length || H.qr[0].t !== t) { H.qr = [{ t, at: Date.now() }].concat(H.qr.filter((x) => x.t !== t)).slice(0, 40); saveH(); }
    lastQR = t; qrSheet(t);
  }
  function qrSheet(t) {
    curQR = t; const q = parseQR(t);
    veil(`<div class="sheet"><div class="tagw"><i class="ring"></i><div class="medal"><small>${esc(q.ty.toUpperCase())}</small><div class="k">${q.k}</div>
      ${q.main ? `<p>${esc(q.main)}</p>` : ''}${q.dl ? `<dl>${q.dl.map(([a, b]) => `<dt>${esc(a)}</dt><dd>${esc(b)}</dd>`).join('')}</dl>` : ''}</div></div>
      ${q.act.map(([k, l, v], i) => `<button class="ab wide ${i ? 'c2' : 'green'}" data-q="${k}" data-v="${esc(v)}" type="button">${l}</button>`).join('')}
      <div class="nrow"><button class="ab glass" data-q="copy" type="button">📋 Copier</button><button class="ab glass" data-q="share" type="button">📤 Partager</button></div>
      <button class="ab wide violet" data-q="again" type="button">▦ Scanner un autre code</button></div>`);
  }

  // ---------------------------------------------------------- mes scans
  let htab = 'scans';
  function histSheet() {
    const ago = (t) => { const d = new Date(t); return `${p2(d.getDate())}/${p2(d.getMonth() + 1)} · ${p2(d.getHours())}h${p2(d.getMinutes())}`; };
    const body = htab === 'scans'
      ? (H.scans.length ? H.scans.map((s) => `<div class="hi">${s.th ? `<img src="${s.th}" alt="">` : '<span class="qi">🖼️</span>'}<div class="tx"><b>${esc(s.name)}</b><span>${ago(s.at)} · ${s.mime === 'application/pdf' ? 'PDF · ' + s.n + ' p.' : 'JPG'}</span></div><div class="acts"><button data-h="open" data-i="${s.id}" type="button">👀</button><button class="c2" data-h="share" data-i="${s.id}" type="button">📤</button><button class="gr" data-h="forget" data-i="${s.id}" type="button">✕</button></div></div>`).join('') : '<div class="emp">Aucun scan pour l’instant 🐶<br>Pose une feuille sur la table et appuie sur la patte.</div>')
      : (H.qr.length ? H.qr.map((q, i) => { const p = parseQR(q.t); return `<div class="hi" data-hq="${i}"><span class="qi">${p.k}</span><div class="tx"><b>${esc(p.main || (p.dl && p.dl[0] && p.dl[0][1]) || q.t)}</b><span>${p.ty} · ${ago(q.at)}</span></div></div>`; }).join('') : '<div class="emp">Aucun QR code reniflé pour l’instant 🐽</div>');
    veil(`<div class="sheet"><h3>🗂️ Mes scans</h3><div class="seg tabs2"><button class="${htab === 'scans' ? 'on' : ''}" data-ht="scans" type="button">📄 Documents</button><button class="${htab === 'qr' ? 'on' : ''}" data-ht="qr" type="button">▦ QR codes</button></div>
      <div class="hl">${body}</div><p class="emp" style="padding:0;font-size:12px">${htab === 'scans' ? 'Les fichiers sont rangés dans Documents › PupScan et Images › PupScan. « ✕ » retire juste la fiche de la liste.' : ''}</p>
      <button class="ab wide glass" data-v="close" type="button">Fermer</button></div>`);
  }

  // ---------------------------------------------------------- voile
  function veil(html) { const v = $('#veil'); v.innerHTML = html; v.classList.remove('hidden'); }
  function unveil() { $('#veil').classList.add('hidden'); $('#veil').innerHTML = ''; if (frozen) { frozen = false; quad = sq = null; drawOv(); bubble(mode === 'qr' ? 'Montre-moi un QR code 🐽' : 'Je renifle… 🐽', false); } }

  // ---------------------------------------------------------- clics
  function setMode(m) { mode = m; $('#modes').classList.toggle('qr', m === 'qr'); document.querySelectorAll('[data-mode]').forEach((b) => b.classList.toggle('on', b.dataset.mode === m)); document.body.classList.toggle('qrmode', m === 'qr'); quad = sq = null; drawOv(); bubble(m === 'qr' ? 'Montre-moi un QR code 🐽' : 'Je renifle… 🐽', false); }
  document.addEventListener('click', (ev) => {
    const t = ev.target, b = (s) => t.closest(s);
    let e;
    if ((e = b('[data-mode]'))) { setMode(e.dataset.mode); return; }
    if (b('#shut')) { capture(); return; }
    if (b('#retry')) { startCam(); return; }
    if (b('#gal')) { $('#file').click(); return; }
    if (b('#torch')) { torchOn = !torchOn; try { track.applyConstraints({ advanced: [{ torch: torchOn }] }); } catch (x) { } $('#torch').classList.toggle('on', torchOn); return; }
    if (b('#stack')) { show('doc'); renderDoc(); return; }
    if (b('#histb')) { htab = 'scans'; histSheet(); return; }
    if ((e = b('[data-c]'))) {
      const k = e.dataset.c;
      if (k === 'cancel') { show(pages.length && editIdx >= 0 ? 'doc' : 'cam'); if (editIdx >= 0) renderDoc(); }
      if (k === 'full') { cq = fullQuad(); drawCrop(); }
      if (k === 'auto') { const q = autoQuad(); if (q) { cq = q; drawCrop(); toast('Flair ! 🐾'); } else toast('Le chiot ne trouve pas les bords… place les pattes à la main 🐶'); }
      if (k === 'ok') addFromCrop();
      return;
    }
    if ((e = b('[data-f]'))) { pages[cur].f = e.dataset.f; renderDoc(); return; }
    if ((e = b('[data-d]'))) {
      const k = e.dataset.d, p = pages[cur];
      if (k === 'back' || k === 'add') { show('cam'); updStack(); }
      if (k === 'prev') { cur = (cur - 1 + pages.length) % pages.length; renderDoc(); }
      if (k === 'next') { cur = (cur + 1) % pages.length; renderDoc(); }
      if (k === 'rot') { p.r = (p.r + 1) % 4; renderDoc(); }
      if (k === 'recrop') { img = p.raw; editIdx = cur; cq = p.q.map((x) => x.slice()); show('crop'); requestAnimationFrame(drawCrop); }
      if (k === 'delp') { pages.splice(cur, 1); toast('Page retirée 🦴'); renderDoc(); }
      if (k === 'pdf' || k === 'jpg') exportDoc(k);
      return;
    }
    if ((e = b('[data-q]'))) {
      const k = e.dataset.q, v = e.dataset.v;
      if (k === 'url') P.openUrl(v);
      if (k === 'wifi') { P.copy(v); toast('Mot de passe copié 🔑 colle-le dans le Wi-Fi'); setTimeout(() => P.wifiSettings(), 700); }
      if (k === 'copy') { P.copy(curQR); toast('Copié 📋'); }
      if (k === 'share') P.share('QR code', curQR);
      if (k === 'again') unveil();
      return;
    }
    if ((e = b('[data-ht]'))) { htab = e.dataset.ht; histSheet(); return; }
    if ((e = b('[data-hq]'))) { qrSheet(H.qr[+e.dataset.hq].t); return; }
    if ((e = b('[data-h]'))) {
      const s = H.scans.find((x) => x.id === +e.dataset.i); if (!s) return;
      if (e.dataset.h === 'open') P.openFile(s.uri, s.mime);
      if (e.dataset.h === 'share') P.shareFile(s.uri, s.mime, s.name);
      if (e.dataset.h === 'forget') { H.scans = H.scans.filter((x) => x !== s); saveH(); histSheet(); }
      return;
    }
    if ((e = b('[data-v]'))) {
      if (e.dataset.v === 'new') { pages = []; $('#dname').value = ''; unveil(); show('cam'); updStack(); }
      else unveil();
      return;
    }
    if (t === $('#veil')) unveil();
  });
  $('#file').addEventListener('change', async (ev) => {
    const f = ev.target.files && ev.target.files[0]; ev.target.value = ''; if (!f) return;
    let bm; try { bm = await createImageBitmap(f); } catch (e) { toast('Image illisible 😿'); return; }
    if (mode === 'qr') {
      const k = Math.min(1, 1600 / Math.max(bm.width, bm.height)), c = mkc(bm.width * k, bm.height * k), x = c.getContext('2d'); x.drawImage(bm, 0, 0, c.width, c.height);
      const r = window.jsQR && jsQR(x.getImageData(0, 0, c.width, c.height).data, c.width, c.height, { inversionAttempts: 'attemptBoth' });
      if (r && r.data) found(r.data); else toast('Pas de QR code sur cette image 🐽');
      return;
    }
    openCrop(bm, null);
  });

  window.ScanUI = {
    on(ev) { if (ev === 'resume' && !$('#cam').classList.contains('hidden')) startCam(); },
    back() {
      if (!$('#veil').classList.contains('hidden')) { unveil(); return true; }
      if (!$('#crop').classList.contains('hidden')) { show(editIdx >= 0 || pages.length ? 'doc' : 'cam'); if (pages.length) renderDoc(); return true; }
      if (!$('#doc').classList.contains('hidden')) { show('cam'); updStack(); return true; }
      if (mode === 'qr') { setMode('doc'); return true; }
      return false;
    },
  };
  setMode('doc'); show('cam');
})();
