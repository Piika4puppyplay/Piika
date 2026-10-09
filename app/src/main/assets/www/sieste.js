/* La sieste du chiot — horloge dans une batterie qui passe du rouge (vide) au vert (pleine). */
(function () {
  'use strict';
  const $ = (s) => document.querySelector(s);
  const J = (s, d) => { try { return JSON.parse(s); } catch (e) { return d; } };
  const B = window.Sieste || { battery: () => JSON.stringify({ pct: +(new URLSearchParams(location.search).get('pct') || 63), charging: true, plug: 'secteur', remain: 2520000 }), prefs: () => '{}' };
  let bat = J(B.battery(), {}) || {};
  const pr = J(B.prefs(), {}) || {};
  if (pr.dim) document.body.classList.add('dim');
  if (pr.acc) { document.body.style.setProperty('--acc', pr.acc); document.body.style.setProperty('--acc2', pr.acc2); }

  // étoiles
  const st = $('#stars');
  for (let i = 0; i < 70; i++) { const s = document.createElement('i'); s.style.cssText = `left:${Math.random() * 100}%;top:${Math.random() * 70}%;--d:${2 + Math.random() * 4}s;animation-delay:${-Math.random() * 4}s`; st.appendChild(s); }
  // vague (bord du liquide)
  let d = 'M0 0'; for (let y = 0; y <= 200; y += 5) d += ` L${(10 + Math.sin(y / 200 * Math.PI * 8) * 7).toFixed(1)} ${y}`; d += ' L0 200 Z';
  $('#wpath').setAttribute('d', d);
  // bulles
  const bb = $('#bubbles');
  for (let i = 0; i < 14; i++) { const b = document.createElement('i'); const s = 4 + Math.random() * 10; b.style.cssText = `left:${Math.random() * 92}%;width:${s}px;height:${s}px;--d:${3 + Math.random() * 4}s;--x:${-10 + Math.random() * 20}px;animation-delay:${-Math.random() * 6}s`; bb.appendChild(b); }

  // chiot (dessin original) : endormi sur son coussin, ou réveillé avec son os quand c'est plein
  function pup(awake) {
    const eyes = awake
      ? '<ellipse cx="88" cy="62" rx="7" ry="8.5" fill="#1e0e36"/><ellipse cx="114" cy="62" rx="7" ry="8.5" fill="#1e0e36"/><circle cx="90.5" cy="58.5" r="3" fill="#fff"/><circle cx="116.5" cy="58.5" r="3" fill="#fff"/>'
      : '<path d="M80 63q8 6 16 0M106 63q8 6 16 0" stroke="#3a1a0c" stroke-width="3.2" fill="none" stroke-linecap="round"/>';
    const mouth = awake ? '<path d="M101 76v4M101 80q-6 7-12 2M101 80q6 7 12 2" stroke="#3a1a0c" stroke-width="2.6" fill="none" stroke-linecap="round"/><path d="M97 84q4 12 8 0z" fill="#ff5e9e"/>'
      : '<path d="M95 80q6 4 12 0" stroke="#3a1a0c" stroke-width="2.4" fill="none" stroke-linecap="round"/>';
    const bone = awake ? '<g transform="translate(140 96) rotate(-20)"><rect x="-16" y="-5" width="32" height="10" rx="5" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.6"/><circle cx="-16" cy="-5" r="6" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.6"/><circle cx="-16" cy="5" r="6" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.6"/><circle cx="16" cy="-5" r="6" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.6"/><circle cx="16" cy="5" r="6" fill="#fff3fa" stroke="#ff3fa4" stroke-width="1.6"/></g>' : '';
    const zz = awake ? '' : '<text class="zzz" x="140" y="40" font-size="20">Z</text><text class="zzz" x="150" y="30" font-size="16">z</text><text class="zzz" x="158" y="22" font-size="13">z</text>';
    return `<svg viewBox="0 0 200 140"><defs>
      <radialGradient id="sf" cx=".38" cy=".3" r=".85"><stop offset="0" stop-color="#fff6e8"/><stop offset=".45" stop-color="#f6cf9a"/><stop offset=".8" stop-color="#d9934f"/><stop offset="1" stop-color="#9c5a24"/></radialGradient>
      <radialGradient id="sd" cx=".4" cy=".25" r=".9"><stop offset="0" stop-color="#d98c4a"/><stop offset="1" stop-color="#5a2c0c"/></radialGradient>
      <radialGradient id="cu" cx=".5" cy=".3" r=".8"><stop offset="0" stop-color="#ffb3d9"/><stop offset=".55" stop-color="#ff3fa4"/><stop offset="1" stop-color="#6e0a40"/></radialGradient>
      <linearGradient id="gl" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".7"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient></defs>
      <ellipse cx="100" cy="122" rx="92" ry="18" fill="url(#cu)" stroke="#fff" stroke-width="1.5"/><ellipse cx="100" cy="114" rx="70" ry="6" fill="url(#gl)"/>
      ${[30, 60, 140, 170].map((x) => `<circle cx="${x}" cy="124" r="2.4" fill="#fff" opacity=".85"/>`).join('')}
      <g class="breath"><path d="M60 112c-10-30 20-50 60-48 34 2 48 26 36 48z" fill="url(#sf)" stroke="#7a3d12" stroke-width="1"/>
      <path d="M150 104c18 2 26-10 20-20" stroke="url(#sd)" stroke-width="9" fill="none" stroke-linecap="round"/>
      <path d="M66 46C48 44 40 66 46 84c3 10 16 9 18 0l6-28z" fill="url(#sd)"/><path d="M136 46c18-2 26 20 20 38-3 10-16 9-18 0l-6-28z" fill="url(#sd)"/>
      <ellipse cx="101" cy="66" rx="34" ry="30" fill="url(#sf)" stroke="#7a3d12" stroke-width="1"/>
      <ellipse cx="101" cy="80" rx="19" ry="13" fill="#fff6ea"/>${eyes}
      <ellipse cx="101" cy="75" rx="7" ry="5.2" fill="#1a0b2e"/><ellipse cx="99" cy="73" rx="2.6" ry="1.3" fill="#fff" opacity=".75"/>${mouth}
      <ellipse cx="80" cy="74" rx="6" ry="3.6" fill="#ff7ab8" opacity=".55"/><ellipse cx="122" cy="74" rx="6" ry="3.6" fill="#ff7ab8" opacity=".55"/>
      <ellipse cx="80" cy="108" rx="13" ry="9" fill="url(#sf)" stroke="#7a3d12"/><ellipse cx="120" cy="108" rx="13" ry="9" fill="url(#sf)" stroke="#7a3d12"/>
      <ellipse cx="92" cy="46" rx="18" ry="7" fill="url(#gl)" opacity=".55"/></g>${bone}${zz}</svg>`;
  }

  const fmtRemain = (ms) => { const m = Math.round(ms / 60000); return m >= 60 ? `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}` : `${m} min`; };
  let lastAwake = null;
  function paint() {
    const pct = Math.max(0, Math.min(100, bat.pct == null || bat.pct < 0 ? 0 : bat.pct));
    const hue = Math.round(pct * 1.2); // 0 = rouge, 120 = vert
    document.body.style.setProperty('--fc', `hsl(${hue} 100% ${pct >= 100 ? 50 : 52}%)`);
    $('#fill').style.width = Math.max(4, pct) + '%';
    $('#pct').textContent = pct + ' %';
    const full = pct >= 100 || bat.full;
    document.body.classList.toggle('charging', !!bat.charging && !full);
    document.body.classList.toggle('low', !bat.charging && pct <= 15);
    if (lastAwake !== full) { lastAwake = full; $('#pup').innerHTML = pup(full); }
    const say = $('#say');
    if (full && bat.charging) say.innerHTML = 'Gamelle pleine ! Tu peux débrancher 🐾<small>100 % · le chiot est réveillé</small>';
    else if (bat.charging) say.innerHTML = `Le chiot recharge ses croquettes ⚡<small>${bat.remain > 0 ? 'plein dans ' + fmtRemain(bat.remain) : 'charge en cours'}${bat.plug ? ' · ' + bat.plug : ''}</small>`;
    else if (pct <= 15) say.innerHTML = 'La gamelle est presque vide 🥺<small>branche-moi vite</small>';
    else say.innerHTML = `Sieste sans câble 🐶<small>${pct} % de croquettes</small>`;
  }
  function clock() {
    const n = new Date();
    $('#hh').textContent = String(n.getHours()).padStart(2, '0');
    $('#mm').textContent = String(n.getMinutes()).padStart(2, '0');
    $('#date').textContent = n.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
  }
  // anti-marquage : la scène se décale doucement toutes les 50 s
  function drift() {
    const x = (Math.random() - .5) * 10, y = (Math.random() - .5) * 10;
    $('#wrap').style.transform = `translate(calc(-50% + ${x}vw), calc(-50% + ${y}vh)) scale(${.94 + Math.random() * .08})`;
  }
  window.SiesteUI = { on(ev, data) { if (ev === 'battery') { bat = J(data, bat) || bat; paint(); } } };
  // aperçu hors service Android (ex. dans « Ta niche ») : vraie batterie via le navigateur
  if (!window.Sieste && navigator.getBattery && !/[?&]pct=/.test(location.search)) {
    navigator.getBattery().then((b) => {
      const up = () => { bat = { pct: Math.round(b.level * 100), charging: b.charging, full: b.level >= 1, plug: b.charging ? 'câble' : '', remain: b.charging && isFinite(b.chargingTime) ? b.chargingTime * 1000 : 0 }; paint(); };
      up(); b.addEventListener('levelchange', up); b.addEventListener('chargingchange', up); b.addEventListener('chargingtimechange', up);
    }).catch(() => {});
  }
  paint(); clock(); drift();
  setInterval(clock, 1000);
  setInterval(drift, 50000);
})();
