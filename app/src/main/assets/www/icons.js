/* PuppyPhone — icônes 3D dessinées à la main (style Android 4.x / Windows Vista, version puppyplay néon).
   Chaque icône = un corps brillant en dégradé + reflet "verre" + lueur basse + pictogramme en relief. */
(function () {
  'use strict';
  let uid = 0;
  const PAL = {
    pink:   ['#ffc2e6', '#ff3fa4', '#6e0a40'],
    cyan:   ['#c4fbff', '#29e6ff', '#03506a'],
    violet: ['#e0ccff', '#9b5cff', '#2e1170'],
    amber:  ['#fff0bd', '#ffb627', '#6e3c00'],
    green:  ['#cbffe9', '#3dffb0', '#055c41'],
    red:    ['#ffc9ce', '#ff4d5e', '#6e0815'],
    blue:   ['#c6dcff', '#3d82ff', '#0b2470'],
    orange: ['#ffd7b8', '#ff7a29', '#6e2800'],
    chrome: ['#ffffff', '#b9b2cc', '#3e3754'],
    night:  ['#a18ccc', '#3a2560', '#0b0517'],
    gold:   ['#fff6c9', '#e8b23a', '#5e3a00'],
  };

  const mat = (d, s, tx, ty) => `<path d="${d}" transform="translate(${tx} ${ty}) scale(${s})" fill="F"/>`;
  const star4 = (cx, cy, R) => `M${cx} ${cy - R}Q${cx} ${cy} ${cx + R} ${cy}Q${cx} ${cy} ${cx} ${cy + R}Q${cx} ${cy} ${cx - R} ${cy}Q${cx} ${cy} ${cx} ${cy - R}Z`;
  function gearPath(cx, cy, R, r, n) {
    let d = '';
    for (let i = 0; i < n * 2; i++) {
      const a0 = (i / (n * 2)) * Math.PI * 2, a1 = ((i + 1) / (n * 2)) * Math.PI * 2;
      const rr = i % 2 ? r : R;
      const p0 = [cx + Math.cos(a0 + 0.06) * rr, cy + Math.sin(a0 + 0.06) * rr];
      const p1 = [cx + Math.cos(a1 - 0.06) * rr, cy + Math.sin(a1 - 0.06) * rr];
      d += (i ? 'L' : 'M') + p0[0].toFixed(1) + ' ' + p0[1].toFixed(1) + 'L' + p1[0].toFixed(1) + ' ' + p1[1].toFixed(1);
    }
    return d + 'Z';
  }

  // Pictogrammes. F = remplissage clair (verre), A = couleur d'accent, D = trait sombre.
  const G = {
    paw: () => `<ellipse cx="50" cy="63" rx="16" ry="13" fill="F"/>
      <ellipse cx="30.5" cy="47" rx="6.5" ry="8.4" fill="F" transform="rotate(-22 30.5 47)"/>
      <ellipse cx="42" cy="34.5" rx="6.8" ry="8.8" fill="F" transform="rotate(-8 42 34.5)"/>
      <ellipse cx="58" cy="34.5" rx="6.8" ry="8.8" fill="F" transform="rotate(8 58 34.5)"/>
      <ellipse cx="69.5" cy="47" rx="6.5" ry="8.4" fill="F" transform="rotate(22 69.5 47)"/>`,
    phone: () => mat('M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z', 2.6, 18.8, 19),
    sms: () => mat('M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z', 2.6, 18.8, 18) +
      `<ellipse cx="50" cy="45" rx="7" ry="5.6" fill="A"/><circle cx="41" cy="37" r="2.8" fill="A"/><circle cx="47" cy="33.5" r="2.8" fill="A"/><circle cx="53" cy="33.5" r="2.8" fill="A"/><circle cx="59" cy="37" r="2.8" fill="A"/>`,
    chat: () => `<rect x="38" y="18" width="46" height="32" rx="10" fill="A"/><path d="M70 48L78 60L62 49Z" fill="A"/>
      <rect x="16" y="36" width="50" height="34" rx="11" fill="F"/><path d="M27 68L22 82L40 69Z" fill="F"/>
      <circle cx="30" cy="53" r="3.6" fill="D"/><circle cx="41" cy="53" r="3.6" fill="D"/><circle cx="52" cy="53" r="3.6" fill="D"/>`,
    people: () => mat('M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z', 2.7, 17.6, 18),
    heart: () => mat('M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z', 2.6, 18.8, 18) +
      `<ellipse cx="38" cy="36" rx="6" ry="4" fill="#fff" opacity=".55" transform="rotate(-30 38 36)"/>`,
    globe: () => `<circle cx="50" cy="50" r="26" fill="F"/>
      <g fill="none" stroke="A" stroke-width="2.8"><ellipse cx="50" cy="50" rx="11" ry="26"/><path d="M24 50H76M28 37H72M28 63H72"/><circle cx="50" cy="50" r="26"/></g>`,
    camera: () => `<rect x="16" y="32" width="68" height="46" rx="10" fill="F"/><rect x="37" y="23" width="26" height="13" rx="4" fill="F"/>
      <circle cx="50" cy="55" r="16" fill="D"/><circle cx="50" cy="55" r="11.5" fill="A"/><circle cx="45.5" cy="50.5" r="4" fill="#fff" opacity=".85"/>
      <rect x="68" y="38" width="9" height="6" rx="2" fill="A"/>`,
    gallery: () => `<rect x="19" y="20" width="62" height="62" rx="7" fill="F"/><rect x="25" y="26" width="50" height="38" rx="4" fill="A"/>
      <path d="M25 64L40 45L50 56L59 47L75 64Z" fill="D"/><circle cx="64" cy="37" r="5.5" fill="#fff"/>`,
    music: () => `<path d="M42 27L75 20V61a9.5 8 0 1 1-6.5-7.6V34L48.5 38.5V68a9.5 8 0 1 1-6.5-7.6Z" fill="F"/>`,
    video: () => `<rect x="16" y="25" width="68" height="47" rx="9" fill="F"/><path d="M43 36L63 48.5L43 61Z" fill="A"/><rect x="38" y="74" width="24" height="6" rx="3" fill="F"/>`,
    gear: () => `<path d="${gearPath(50, 50, 29, 22, 9)}" fill="F"/><circle cx="50" cy="50" r="10" fill="A"/><circle cx="50" cy="50" r="5" fill="D"/>`,
    clock: () => {
      let t = '';
      for (let i = 0; i < 12; i++) {
        const a = i / 12 * Math.PI * 2, r1 = i % 3 ? 21 : 18;
        t += `<line x1="${(50 + Math.sin(a) * r1).toFixed(1)}" y1="${(50 - Math.cos(a) * r1).toFixed(1)}" x2="${(50 + Math.sin(a) * 24).toFixed(1)}" y2="${(50 - Math.cos(a) * 24).toFixed(1)}"/>`;
      }
      return `<circle cx="50" cy="50" r="28" fill="F"/><g stroke="D" stroke-width="2.4" stroke-linecap="round">${t}</g>
        <g stroke="A" stroke-width="4.6" stroke-linecap="round"><path d="M50 50V31M50 50L63 57"/></g><circle cx="50" cy="50" r="4" fill="D"/>`;
    },
    calc: () => {
      let b = '';
      for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) b += `<rect x="${33 + c * 12.5}" y="${45 + r * 11}" width="9" height="8" rx="2" fill="A"/>`;
      return `<rect x="26" y="17" width="48" height="67" rx="9" fill="F"/><rect x="32" y="24" width="36" height="14" rx="3" fill="D"/>${b}`;
    },
    pin: () => `<path d="M50 16C35 16 26 27 26 40C26 57 50 82 50 82S74 57 74 40C74 27 65 16 50 16Z" fill="F"/><circle cx="50" cy="40" r="9" fill="A"/>`,
    mail: () => `<rect x="16" y="27" width="68" height="47" rx="7" fill="F"/><path d="M19 31L50 54L81 31" fill="none" stroke="A" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"/>`,
    contacts: () => `<circle cx="50" cy="37" r="13" fill="F"/><path d="M24 78C24 61 37 54 50 54S76 61 76 78Z" fill="F"/>`,
    calendar: (o) => `<rect x="20" y="24" width="60" height="56" rx="8" fill="F"/><path d="M20 32a8 8 0 0 1 8-8H72a8 8 0 0 1 8 8V40H20Z" fill="A"/>
      <rect x="32" y="16" width="6" height="14" rx="3" fill="D"/><rect x="62" y="16" width="6" height="14" rx="3" fill="D"/>
      <text x="50" y="73" text-anchor="middle" font-family="Bungee,Impact,Arial Black,sans-serif" font-weight="900" font-size="27" fill="D">${o.day || new Date().getDate()}</text>`,
    store: () => `<path d="M26 38H74L69 81H31Z" fill="F"/><path d="M39 40V33a11 11 0 0 1 22 0V40" fill="none" stroke="F" stroke-width="5.5" stroke-linecap="round"/><path d="M44 52L60 61L44 70Z" fill="A"/>`,
    folder: () => `<path d="M16 30a6 6 0 0 1 6-6H41L48 31H78a6 6 0 0 1 6 6V72a6 6 0 0 1-6 6H22a6 6 0 0 1-6-6Z" fill="F"/><path d="M16 42H84V72a6 6 0 0 1-6 6H22a6 6 0 0 1-6-6Z" fill="A" opacity=".45"/>`,
    weather: () => `<circle cx="40" cy="40" r="13" fill="#ffd34d"/><g stroke="#ffd34d" stroke-width="3.5" stroke-linecap="round"><path d="M40 18V22M22 40H26M27 27L30 30M53 27L50 30"/></g>
      <path d="M33 76a12 12 0 0 1 1-24a17 17 0 0 1 32-3a13 13 0 0 1 3 27Z" fill="F"/>`,
    notes: () => `<rect x="24" y="17" width="48" height="66" rx="6" fill="F"/><g stroke="A" stroke-width="3.5" stroke-linecap="round"><path d="M32 34H64M32 45H64M32 56H64M32 67H52"/></g>
      <path d="M62 78L80 52L86 56L68 82L60 84Z" fill="D"/>`,
    speaker: (o) => `<path d="M20 39H34L52 24V76L34 61H20Z" fill="F"/>` + (o.muted
      ? `<g stroke="#ff4d5e" stroke-width="6.5" stroke-linecap="round"><path d="M62 38L80 62M80 38L62 62"/></g>`
      : `<g fill="none" stroke="F" stroke-width="5.5" stroke-linecap="round"><path d="M61 39a14 14 0 0 1 0 22"/><path d="M69 30a26 26 0 0 1 0 40"/></g>`),
    wifi: () => `<g fill="none" stroke="F" stroke-width="7.5" stroke-linecap="round"><path d="M20 43a42 42 0 0 1 60 0"/><path d="M30 54a28 28 0 0 1 40 0"/><path d="M40 65a14 14 0 0 1 20 0"/></g><circle cx="50" cy="75" r="5.5" fill="F"/>`,
    bt: () => `<path d="M34 35L66 63L50 78V22L66 37L34 65" fill="none" stroke="F" stroke-width="7" stroke-linejoin="round" stroke-linecap="round"/>`,
    data: () => `<text x="50" y="69" text-anchor="middle" font-family="Bungee,Impact,Arial Black,sans-serif" font-weight="900" font-size="31" fill="F">4G</text>
      <path d="M33 33L41 22L49 33Z" fill="F"/><path d="M53 22L61 33L69 22Z" fill="A"/>`,
    torch: (o) => (o.on ? `<g stroke="#ffe27a" stroke-width="3.5" stroke-linecap="round"><path d="M50 8V16M30 12L35 19M70 12L65 19"/></g>` : '') +
      `<path d="M30 24H70L61 44H39Z" fill="F"/><path d="M39 46H61V80a5 5 0 0 1-5 5H44a5 5 0 0 1-5-5Z" fill="F"/><rect x="46" y="55" width="8" height="12" rx="3" fill="A"/>`,
    battery: (o) => {
      const lv = Math.max(0, Math.min(100, o.level == null ? 70 : o.level));
      const h = 48 * lv / 100;
      return `<rect x="44" y="15" width="12" height="7" rx="2" fill="F"/><rect x="31" y="21" width="38" height="62" rx="8" fill="F"/>
        <rect x="36" y="${(78 - h).toFixed(1)}" width="28" height="${h.toFixed(1)}" rx="4" fill="A"/>` +
        (o.charging ? `<path d="M53 30L41 54H50L46 72L60 46H51Z" fill="#fff" stroke="D" stroke-width="1.5"/>` : '');
    },
    plus: () => `<path d="M50 24V76M24 50H76" stroke="F" stroke-width="11" stroke-linecap="round"/>`,
    gamepad: () => `<path d="M30 34H70C81 34 87 45 87 58C87 70 81 77 74 77C67 77 64 68 60 66H40C36 68 33 77 26 77C19 77 13 70 13 58C13 45 19 34 30 34Z" fill="F"/>
      <rect x="23" y="52" width="16" height="6" rx="2" fill="A"/><rect x="28" y="47" width="6" height="16" rx="2" fill="A"/>
      <circle cx="66" cy="49" r="4" fill="A"/><circle cx="74" cy="56" r="4" fill="A"/><circle cx="58" cy="56" r="4" fill="A"/><circle cx="66" cy="63" r="4" fill="A"/>`,
    cart: () => `<path d="M16 24H28L36 62H72L80 36H31" fill="none" stroke="F" stroke-width="7" stroke-linejoin="round" stroke-linecap="round"/><circle cx="40" cy="74" r="6" fill="F"/><circle cx="68" cy="74" r="6" fill="F"/>`,
    coin: () => `<circle cx="50" cy="50" r="28" fill="F"/><circle cx="50" cy="50" r="21" fill="none" stroke="A" stroke-width="3"/>
      <text x="50" y="62" text-anchor="middle" font-family="Bungee,Impact,Arial Black,sans-serif" font-weight="900" font-size="32" fill="A">€</text>`,
    plane: () => mat('M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z', 2.7, 17.6, 18),
    bone: () => `<g fill="F"><rect x="32" y="45" width="36" height="10" rx="5"/><circle cx="31" cy="44" r="7"/><circle cx="31" cy="56" r="7"/><circle cx="69" cy="44" r="7"/><circle cx="69" cy="56" r="7"/></g>`,
    bowl: () => `<g fill="F"><rect x="36" y="30" width="28" height="8" rx="4"/><circle cx="35" cy="30" r="5.5"/><circle cx="35" cy="38" r="5.5"/><circle cx="65" cy="30" r="5.5"/><circle cx="65" cy="38" r="5.5"/></g>
      <path d="M18 52H82L75 74a7 7 0 0 1-7 5H32a7 7 0 0 1-7-5Z" fill="F"/><ellipse cx="50" cy="52" rx="32" ry="6" fill="A"/>`,
    pulse: () => mat('M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z', 2.6, 18.8, 18) +
      `<path d="M22 48H37L43 38L51 60L57 46H78" fill="none" stroke="A" stroke-width="4.5" stroke-linejoin="round" stroke-linecap="round"/>`,
    news: () => `<rect x="18" y="22" width="56" height="58" rx="6" fill="F"/><path d="M74 36H82V74a6 6 0 0 1-6 6H70" fill="F"/>
      <rect x="25" y="30" width="42" height="11" rx="2" fill="A"/><g fill="A" opacity=".75"><rect x="25" y="47" width="20" height="16" rx="2"/><rect x="49" y="48" width="18" height="4" rx="2"/><rect x="49" y="56" width="18" height="4" rx="2"/><rect x="25" y="68" width="42" height="4" rx="2"/></g>`,
    sparkle: () => `<path d="${star4(44, 54, 28)}" fill="F"/><path d="${star4(71, 28, 13)}" fill="A"/><path d="${star4(74, 72, 8)}" fill="F"/>`,
    briefcase: () => `<path d="M40 36V30a5 5 0 0 1 5-5H55a5 5 0 0 1 5 5V36" fill="none" stroke="F" stroke-width="5.5"/><rect x="16" y="35" width="68" height="44" rx="8" fill="F"/>
      <rect x="16" y="52" width="68" height="5" fill="A"/><rect x="44" y="48" width="12" height="13" rx="3" fill="A"/>`,
    wrench: () => mat('M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z', 2.6, 19, 19),
    box: () => `<path d="M20 40L50 51L80 40V70L50 83L20 70Z" fill="F"/><path d="M20 40L50 28L80 40L50 51Z" fill="A"/>
      <path d="M50 51V83" stroke="D" stroke-width="2" opacity=".5"/><text x="36" y="74" text-anchor="middle" font-family="Bungee,Impact,Arial Black,sans-serif" font-weight="900" font-size="20" fill="D">?</text>`,
    search: () => `<circle cx="44" cy="44" r="17" fill="none" stroke="F" stroke-width="8"/><path d="M57 57L75 75" stroke="F" stroke-width="10" stroke-linecap="round"/>`,
    piggy: () => `<ellipse cx="48" cy="56" rx="27" ry="20" fill="F"/><path d="M30 42L27 28L41 37Z" fill="F"/><ellipse cx="75" cy="56" rx="7" ry="6" fill="A"/>
      <rect x="32" y="70" width="8" height="12" rx="3" fill="F"/><rect x="54" y="70" width="8" height="12" rx="3" fill="F"/><rect x="41" y="38" width="15" height="4" rx="2" fill="D"/><circle cx="62" cy="49" r="3" fill="D"/>
      <circle cx="49" cy="25" r="8" fill="#ffd34d"/><text x="49" y="29.5" text-anchor="middle" font-family="Bungee,Impact,Arial Black,sans-serif" font-weight="900" font-size="11" fill="#7a4a00">€</text>`,
    pupnet: () => `<circle cx="46" cy="46" r="27" fill="F"/>
      <g fill="none" stroke="A" stroke-width="2.6"><ellipse cx="46" cy="46" rx="11" ry="27"/><path d="M19 46H73M23 33H69M23 59H69"/></g>
      <circle cx="70" cy="72" r="15" fill="D"/><g fill="F"><ellipse cx="70" cy="76" rx="6.5" ry="5.2"/><circle cx="62.5" cy="69" r="2.7"/><circle cx="67" cy="65" r="2.7"/><circle cx="73" cy="65" r="2.7"/><circle cx="77.5" cy="69" r="2.7"/></g>`,
    shield: () => `<path d="M50 14L80 25V48C80 66 67 79 50 86C33 79 20 66 20 48V25Z" fill="F"/><path d="M50 22L72 30V48C72 61 63 71 50 77Z" fill="A" opacity=".55"/>
      <path d="M37 50L46 59L64 40" fill="none" stroke="D" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>`,
    star: () => `<path d="M50 14L60 38L86 40L66 57L72 83L50 69L28 83L34 57L14 40L40 38Z" fill="F"/>`,
    share: () => `<g fill="F"><circle cx="70" cy="26" r="11"/><circle cx="30" cy="50" r="11"/><circle cx="70" cy="74" r="11"/></g><path d="M30 50L70 26M30 50L70 74" stroke="F" stroke-width="6"/>`,
    tabs: () => `<rect x="30" y="18" width="50" height="50" rx="9" fill="A"/><rect x="20" y="30" width="50" height="52" rx="9" fill="F"/>`,
    refresh: () => `<path d="M74 50A24 24 0 1 1 64 30" fill="none" stroke="F" stroke-width="9" stroke-linecap="round"/><path d="M56 14L78 26L58 40Z" fill="F"/>`,
    left: () => `<path d="M62 18L30 50L62 82" fill="none" stroke="F" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>`,
    right: () => `<path d="M38 18L70 50L38 82" fill="none" stroke="F" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>`,
    download: () => `<path d="M50 16V58" stroke="F" stroke-width="10" stroke-linecap="round"/><path d="M30 42L50 64L70 42" fill="none" stroke="F" stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/><rect x="20" y="72" width="60" height="10" rx="5" fill="F"/>`,
    desktop: () => `<rect x="14" y="20" width="72" height="48" rx="6" fill="F"/><rect x="20" y="26" width="60" height="36" rx="3" fill="A"/><path d="M40 68L36 80H64L60 68Z" fill="F"/><rect x="30" y="79" width="40" height="6" rx="3" fill="F"/>`,
    external: () => `<rect x="16" y="28" width="52" height="54" rx="9" fill="none" stroke="F" stroke-width="8"/><path d="M48 52L80 20M58 18H82V42" fill="none" stroke="A" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/>`,
    trash: () => `<rect x="22" y="26" width="56" height="9" rx="4" fill="F"/><rect x="40" y="16" width="20" height="10" rx="3" fill="F"/><path d="M28 38H72L67 84H33Z" fill="F"/><g stroke="A" stroke-width="4" stroke-linecap="round"><path d="M42 48V74M58 48V74M50 48V74"/></g>`,
    lock: () => `<rect x="24" y="44" width="52" height="40" rx="8" fill="F"/><path d="M34 44V34a16 16 0 0 1 32 0V44" fill="none" stroke="F" stroke-width="8"/><circle cx="50" cy="62" r="6" fill="A"/>`,
    upload: () => `<path d="M50 64V22" stroke="F" stroke-width="10" stroke-linecap="round"/><path d="M30 38L50 16L70 38" fill="none" stroke="F" stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/><rect x="20" y="72" width="60" height="10" rx="5" fill="F"/>`,
    menu: () => `<g fill="F"><circle cx="50" cy="24" r="9"/><circle cx="50" cy="50" r="9"/><circle cx="50" cy="76" r="9"/></g>`,
    apps: () => { let d = ''; for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) d += `<circle cx="${30 + c * 20}" cy="${30 + r * 20}" r="6.5" fill="F"/>`; return d; },
    home: () => `<path d="M50 20L82 48H73V80H57V62H43V80H27V48H18Z" fill="F"/>`,
    link: () => `<g fill="none" stroke="F" stroke-width="7" stroke-linecap="round"><path d="M44 56a12 12 0 0 0 17 0l10-10a12 12 0 0 0-17-17l-4 4"/><path d="M56 44a12 12 0 0 0-17 0L29 54a12 12 0 0 0 17 17l4-4"/></g>`,
    moon: () => `<path d="M62 18A34 34 0 1 0 82 62A27 27 0 1 1 62 18Z" fill="F"/>`,
    plane2: () => '',
    ringer: (o) => `<path d="M50 18a6 6 0 0 1 6 6v2c11 3 17 12 17 24v14l7 8H20l7-8V50c0-12 6-21 17-24v-2a6 6 0 0 1 6-6Z" fill="F"/><circle cx="50" cy="80" r="7" fill="F"/>` +
      (o.off ? `<path d="M22 22L78 78" stroke="#ff4d5e" stroke-width="7" stroke-linecap="round"/>` : ''),
  };

  function paint(src, F, A, D) {
    return src.replace(/="F"/g, `="${F}"`).replace(/="A"/g, `="${A}"`).replace(/="D"/g, `="${D}"`);
  }

  /** Icône complète avec corps 3D. shape: tile | orb | none */
  // Cache : chaque icône est dessinée une seule fois puis réutilisée comme image (rapide pour le GPU).
  const cache = new Map();
  function icon(glyph, color, opt) {
    opt = opt || {};
    const key = glyph + '|' + color + '|' + JSON.stringify(opt);
    let out = cache.get(key);
    if (!out) {
      const svg = draw(glyph, color, opt).replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" ');
      out = `<img class="pi" alt="" draggable="false" decoding="async" src="data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}">`;
      cache.set(key, out);
    }
    return out;
  }
  function draw(glyph, color, opt) {
    const shape = opt.shape || 'tile';
    const [L, M, D] = PAL[color] || PAL.pink;
    const u = 'pp' + (++uid);
    const g = (G[glyph] || G.paw)(opt);
    const body = shape === 'orb'
      ? (f, extra) => `<circle cx="50" cy="50" r="44" fill="${f}" ${extra || ''}/>`
      : (f, extra) => `<rect x="6" y="6" width="88" height="88" rx="23" fill="${f}" ${extra || ''}/>`;
    const rim = shape === 'orb'
      ? `<circle cx="50" cy="50" r="42.2" fill="none" stroke="#fff" stroke-opacity=".55" stroke-width="1.6"/>`
      : `<rect x="7.8" y="7.8" width="84.4" height="84.4" rx="21.4" fill="none" stroke="#fff" stroke-opacity=".5" stroke-width="1.6"/>`;
    const gloss = shape === 'orb'
      ? `<ellipse cx="50" cy="30" rx="33" ry="21" fill="url(#${u}g)"/>`
      : `<path d="M9 31Q9 9 31 9H69Q91 9 91 31V45Q50 57 9 45Z" fill="url(#${u}g)"/>`;
    if (shape === 'none') {
      return `<svg viewBox="0 0 100 100" class="pi" aria-hidden="true"><defs>
        <linearGradient id="${u}f" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="${L}"/></linearGradient></defs>
        <g transform="translate(0 2.4)" opacity=".55">${paint(g, D, D, D)}</g>${paint(g, `url(#${u}f)`, M, D)}</svg>`;
    }
    return `<svg viewBox="0 0 100 100" class="pi" aria-hidden="true">
      <defs>
        <linearGradient id="${u}b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${L}"/><stop offset=".48" stop-color="${M}"/><stop offset="1" stop-color="${D}"/></linearGradient>
        <radialGradient id="${u}r" cx=".5" cy="1" r=".62"><stop offset="0" stop-color="${L}" stop-opacity=".95"/><stop offset="1" stop-color="${L}" stop-opacity="0"/></radialGradient>
        <linearGradient id="${u}g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".92"/><stop offset="1" stop-color="#fff" stop-opacity=".07"/></linearGradient>
        <linearGradient id="${u}f" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff"/><stop offset=".6" stop-color="#fff6fc"/><stop offset="1" stop-color="${L}"/></linearGradient>
        <clipPath id="${u}c">${body('#000')}</clipPath>
      </defs>
      <g transform="translate(0 4)" opacity=".3">${body('#000')}</g><g transform="translate(0 2)" opacity=".3">${body('#000')}</g>
      ${body(`url(#${u}b)`, `stroke="${D}" stroke-width="2"`)}
      <g clip-path="url(#${u}c)"><ellipse cx="50" cy="98" rx="46" ry="30" fill="url(#${u}r)"/></g>
      <g transform="translate(0 2.8)" opacity=".55">${paint(g, D, D, D)}</g>
      ${paint(g, `url(#${u}f)`, M, D)}
      ${gloss}${rim}
      <path d="${star4(22, 20, 5)}" fill="#fff" opacity=".9"/>
    </svg>`;
  }

  window.PupIcons = { icon, PAL, glyphs: Object.keys(G) };
})();
