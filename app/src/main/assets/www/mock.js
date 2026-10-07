/* Simulation du téléphone pour prévisualiser PuppyPhone dans un navigateur (inutilisée dans l'APK). */
(function () {
  if (window.Pup) return;
  const mem = {};
  const ls = {
    get(k) { try { return localStorage.getItem('pp_' + k); } catch (e) { return mem[k] ?? null; } },
    set(k, v) { try { localStorage.setItem('pp_' + k, v); } catch (e) { mem[k] = v; } },
  };
  const now = Date.now();
  const A = (pkg, label, cls, extra) => Object.assign({ id: pkg + '/' + (cls || '.Main'), pkg, label, sysCat: -1, system: false, installed: now - 9e8 }, extra || {});
  const apps = [
    A('com.google.android.dialer', 'Téléphone', '', { system: true }),
    A('com.google.android.apps.messaging', 'Messages', '', { system: true }),
    A('com.android.chrome', 'Chrome', '', { system: true }),
    A('com.sec.android.app.camera', 'Appareil photo', '', { system: true }),
    A('com.google.android.apps.photos', 'Photos', '', { sysCat: 3 }),
    A('com.android.settings', 'Paramètres', '', { system: true }),
    A('com.google.android.deskclock', 'Horloge', '', { system: true }),
    A('com.google.android.calculator', 'Calculatrice', '', { system: true }),
    A('com.google.android.calendar', 'Agenda', '', { system: true }),
    A('com.google.android.apps.maps', 'Maps', '', { sysCat: 6 }),
    A('com.google.android.gm', 'Gmail', '', { system: true }),
    A('com.android.vending', 'Play Store', '', { system: true }),
    A('com.whatsapp', 'WhatsApp'), A('org.telegram.messenger', 'Telegram'), A('com.discord', 'Discord'),
    A('com.instagram.android', 'Instagram', '', { sysCat: 4 }), A('com.zhiliaoapp.musically', 'TikTok'), A('com.snapchat.android', 'Snapchat'), A('com.twitter.android', 'X'),
    A('com.grindrapp.android', 'Grindr'), A('com.appspot.scruffapp', 'SCRUFF'), A('com.planetromeo.android.app', 'ROMEO'),
    A('com.spotify.music', 'Spotify', '', { sysCat: 1 }), A('com.soundcloud.android', 'SoundCloud'), A('com.shazam.android', 'Shazam'),
    A('com.google.android.youtube', 'YouTube', '', { sysCat: 2 }), A('com.netflix.mediaclient', 'Netflix'), A('tv.twitch.android.app', 'Twitch'),
    A('com.supercell.brawlstars', 'Brawl Stars', '', { sysCat: 0 }), A('com.king.candycrushsaga', 'Candy Crush'), A('com.mojang.minecraftpe', 'Minecraft'),
    A('fr.leboncoin', 'leboncoin'), A('com.vinted', 'Vinted'),
    A('com.boursorama.android.clients', 'Boursorama'), A('com.paypal.android.p2pmobile', 'PayPal'),
    A('com.sncf.fusion', 'SNCF Connect'), A('com.ubercab', 'Uber'),
    A('com.deliveroo.orderapp', 'Deliveroo'),
    A('com.openai.chatgpt', 'ChatGPT'), A('com.anthropic.claude', 'Claude'),
    A('fr.piika.pupdown', 'PupDown'), A('fr.piika.puppyphone', 'PupSon', '.PupSonActivity'),
    A('com.gaybarfinder.ultra', 'Pup Night Radar', '', { installed: now - 6e4 }),
    A('net.zxkq.helper', 'Zx Helper', '', { installed: now - 3e4 }),
  ];
  const cols = ['#ff3fa4', '#29e6ff', '#9b5cff', '#ffb627', '#3dffb0', '#ff4d5e', '#3d82ff', '#ff7a29'];
  let h = 0;
  const icon = (id) => {
    const a = apps.find((x) => x.id === id) || { label: '?' };
    let s = 0; for (const ch of id) s = (s * 31 + ch.charCodeAt(0)) >>> 0;
    const c = cols[s % cols.length], c2 = cols[(s >> 3) % cols.length];
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs><rect width="96" height="96" fill="url(#g)"/><text x="48" y="64" font-family="Arial Black,Arial" font-weight="900" font-size="46" text-anchor="middle" fill="#fff">${a.label[0]}</text></svg>`;
    return 'data:image/svg+xml,' + encodeURIComponent(svg);
  };
  let st = { wifi: true, wifiOn: true, bt: false, data: true, cell: false, airplane: false, battery: 78, charging: true, torch: false, hasTorch: true, isDefault: false, shortcutHost: false, operator: 'Free', ringer: 2,
    wall: { type: 'neon', video: '', image: '', hasVideo: false, hasImage: false, muted: false, volume: 0.8, ver: 1, playing: false } };
  window.PupMock = {
    iconUrl: icon,
    get: ls.get, set: ls.set,
    apps: () => JSON.stringify(apps),
    defaults: () => JSON.stringify({ dial: 'com.google.android.dialer', sms: 'com.google.android.apps.messaging', browser: 'com.android.chrome', camera: 'com.sec.android.app.camera', self: 'fr.piika.puppyphone' }),
    status: () => JSON.stringify(st),
    wallInfo: () => JSON.stringify(st.wall),
    toggle: (w) => { if (w === 'wallsound') st.wall.muted = !st.wall.muted; else if (w in st) st[w] = !st[w]; setTimeout(() => window.PupNative && PupNative.on('status', ''), 50); },
    open() {}, launch() {}, appInfo() {}, uninstall() {}, openUrl() {}, startShortcut() {}, haptic() {}, expand() {}, toast() {},
    shortcuts: () => JSON.stringify([{ pkg: 'x', sid: 'a', label: 'Nouveau message' }, { pkg: 'x', sid: 'b', label: 'Appareil photo' }]),
    takePins: () => '[]',
    pickWall() {}, setWallType(t) { st.wall.type = t; PupNative.on('wall', JSON.stringify(st.wall)); },
    setWallMuted(m) { st.wall.muted = m; }, setWallVolume(v) { st.wall.volume = v; },
    isDefault: () => st.isDefault, askDefault() { st.isDefault = true; },
  };
})();
