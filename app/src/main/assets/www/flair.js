/* Flair 🐾 — le tri intelligent hors ligne de PuppyPhone.
   Aucun serveur, aucun jeton : un petit classifieur local qui combine
   1) une base de plus de 300 applis connues, 2) la catégorie déclarée par l'appli à Android,
   3) des mots-clés (nom + identifiant), 4) ce qu'il apprend de tes corrections (éditeur → dossier).
   S'il hésite, l'appli part dans « À trier ». */
(function () {
  'use strict';

  const CATS = [
    { id: 'puppy',  n: 'PuppyPlay',        c: 'pink',   g: 'paw' },
    { id: 'tel',    n: 'Appels & SMS',     c: 'green',  g: 'phone' },
    { id: 'msg',    n: 'Messageries',      c: 'cyan',   g: 'chat' },
    { id: 'social', n: 'Réseaux sociaux',  c: 'blue',   g: 'people' },
    { id: 'date',   n: 'Rencontres',       c: 'red',    g: 'heart' },
    { id: 'web',    n: 'Internet & Mails', c: 'blue',   g: 'globe' },
    { id: 'photo',  n: 'Photo & Caméra',   c: 'amber',  g: 'camera' },
    { id: 'video',  n: 'Vidéo & TV',       c: 'red',    g: 'video' },
    { id: 'music',  n: 'Musique & Audio',  c: 'violet', g: 'music' },
    { id: 'games',  n: 'Jeux',             c: 'green',  g: 'gamepad' },
    { id: 'shop',   n: 'Shopping',         c: 'orange', g: 'cart' },
    { id: 'money',  n: 'Banque & Argent',  c: 'gold',   g: 'coin' },
    { id: 'travel', n: 'Voyage & Cartes',  c: 'cyan',   g: 'pin' },
    { id: 'food',   n: 'Miam',             c: 'orange', g: 'bowl' },
    { id: 'health', n: 'Santé & Sport',    c: 'red',    g: 'pulse' },
    { id: 'news',   n: 'Actus & Lecture',  c: 'chrome', g: 'news' },
    { id: 'ai',     n: 'IA & Création',    c: 'violet', g: 'sparkle' },
    { id: 'work',   n: 'Bureau & Cloud',   c: 'blue',   g: 'briefcase' },
    { id: 'tools',  n: 'Outils',           c: 'amber',  g: 'wrench' },
    { id: 'system', n: 'Système',          c: 'night',  g: 'gear' },
    { id: 'sort',   n: 'À trier',          c: 'orange', g: 'box' },
  ];

  // Préfixes de paquets connus → dossier (le plus long gagne).
  const KNOWN = {
    'fr.piika': 'puppy',
    // appels & sms
    'com.google.android.dialer': 'tel', 'com.android.dialer': 'tel', 'com.samsung.android.dialer': 'tel', 'com.android.incallui': 'tel',
    'com.google.android.contacts': 'tel', 'com.android.contacts': 'tel', 'com.samsung.android.app.contacts': 'tel',
    'com.google.android.apps.messaging': 'tel', 'com.samsung.android.messaging': 'tel', 'com.android.mms': 'tel', 'com.truecaller': 'tel', 'com.android.phone': 'tel',
    'com.miui.contacts': 'tel', 'com.oneplus.dialer': 'tel', 'com.android.messaging': 'tel', 'com.textra': 'tel', 'xyz.klinker.messenger': 'tel',
    // messageries
    'com.whatsapp': 'msg', 'com.whatsapp.w4b': 'msg', 'org.telegram': 'msg', 'org.thunderdog.challegram': 'msg', 'org.thoughtcrime.securesms': 'msg',
    'com.facebook.orca': 'msg', 'com.facebook.mlite': 'msg', 'com.discord': 'msg', 'com.viber.voip': 'msg', 'jp.naver.line': 'msg', 'com.skype': 'msg',
    'com.kakao.talk': 'msg', 'com.tencent.mm': 'msg', 'im.vector.app': 'msg', 'ch.threema': 'msg', 'com.wire': 'msg', 'org.telegram.messenger.web': 'msg', 'com.google.android.apps.tachyon': 'msg',
    // réseaux sociaux
    'com.instagram': 'social', 'com.facebook.katana': 'social', 'com.facebook.lite': 'social', 'com.twitter.android': 'social', 'com.zhiliaoapp.musically': 'social',
    'com.ss.android.ugc': 'social', 'com.snapchat.android': 'social', 'com.reddit.frontpage': 'social', 'com.pinterest': 'social', 'com.bereal': 'social',
    'com.linkedin.android': 'social', 'com.tumblr': 'social', 'org.joinmastodon': 'social', 'xyz.blueskyweb': 'social', 'com.fetlife': 'social', 'com.yubo': 'social',
    'com.vkontakte': 'social', 'com.quora': 'social', 'com.lemon8': 'social', 'com.clubhouse': 'social',
    // rencontres
    'com.grindrapp.android': 'date', 'com.scruff': 'date', 'com.appspot.scruffapp': 'date', 'com.planetromeo': 'date', 'com.hornet.android': 'date', 'com.tinder': 'date',
    'com.bumble': 'date', 'co.hinge': 'date', 'com.taimi': 'date', 'com.jackd': 'date', 'co.feeld': 'date', 'com.okcupid': 'date', 'com.badoo': 'date',
    'com.adopteunmec': 'date', 'fr.meetic': 'date', 'com.match': 'date', 'com.happn': 'date', 'com.blued': 'date', 'net.lovoo': 'date', 'com.recon': 'date', 'com.gaydar': 'date',
    // internet & mails
    'com.android.chrome': 'web', 'com.chrome.beta': 'web', 'org.mozilla': 'web', 'com.brave.browser': 'web', 'com.opera': 'web', 'com.microsoft.emmx': 'web',
    'com.sec.android.app.sbrowser': 'web', 'com.duckduckgo': 'web', 'com.vivaldi': 'web', 'com.kiwibrowser': 'web', 'org.torproject': 'web', 'com.ecosia': 'web', 'com.qwant': 'web',
    'com.google.android.gm': 'web', 'com.microsoft.office.outlook': 'web', 'com.yahoo.mobile.client.android.mail': 'web', 'ch.protonmail': 'web', 'me.proton.android.mail': 'web',
    'com.google.android.googlequicksearchbox': 'web', 'com.samsung.android.email': 'web', 'com.android.email': 'web', 'com.fsck.k9': 'web', 'net.thunderbird': 'web', 'com.mi.globalbrowser': 'web',
    // photo
    'com.google.android.apps.photos': 'photo', 'com.sec.android.gallery3d': 'photo', 'com.android.camera': 'photo', 'com.android.camera2': 'photo', 'com.sec.android.app.camera': 'photo',
    'com.google.android.GoogleCamera': 'photo', 'com.miui.gallery': 'photo', 'com.adobe.lrmobile': 'photo', 'com.vsco': 'photo', 'com.niksoftware.snapseed': 'photo',
    'com.google.android.apps.photosgo': 'photo', 'com.oneplus.camera': 'photo', 'com.oneplus.gallery': 'photo', 'com.android.gallery3d': 'photo', 'org.lineageos.aperture': 'photo', 'com.huawei.camera': 'photo',
    // vidéo
    'com.google.android.youtube': 'video', 'com.netflix': 'video', 'com.disney': 'video', 'com.amazon.avod': 'video', 'com.canal': 'video', 'fr.canalplus': 'video',
    'fr.francetv': 'video', 'tv.molotov': 'video', 'com.crunchyroll': 'video', 'org.videolan.vlc': 'video', 'com.mxtech': 'video', 'fr.m6': 'video', 'fr.tf1': 'video',
    'com.plexapp': 'video', 'com.dazn': 'video', 'com.google.android.videos': 'video', 'tv.twitch': 'video', 'com.hbo': 'video', 'com.wbd': 'video', 'com.apple.atve': 'video',
    'com.samsung.android.video': 'video', 'com.google.android.youtube.tv': 'video', 'com.arte': 'video', 'tv.arte': 'video', 'com.salto': 'video', 'com.mycanal': 'video', 'com.vimeo': 'video', 'com.kick': 'video',
    // musique
    'com.spotify': 'music', 'deezer.android': 'music', 'com.deezer': 'music', 'com.soundcloud': 'music', 'com.shazam': 'music', 'com.google.android.apps.youtube.music': 'music',
    'com.apple.android.music': 'music', 'com.amazon.mp3': 'music', 'com.audible': 'music', 'com.qobuz': 'music', 'com.aspiro.tidal': 'music', 'au.com.shiftyjelly.pocketcasts': 'music',
    'com.bandcamp': 'music', 'com.sec.android.app.music': 'music', 'com.miui.player': 'music', 'fr.radiofrance': 'music', 'com.radio': 'music', 'tunein': 'music', 'com.mixcloud': 'music',
    'com.beatport': 'music', 'com.resident.advisor': 'music', 'com.ra.android': 'music', 'com.djay': 'music', 'com.algoriddim': 'music', 'com.smule': 'music', 'com.google.android.music': 'music',
    // jeux
    'com.supercell': 'games', 'com.king': 'games', 'com.rovio': 'games', 'com.ea.': 'games', 'com.gameloft': 'games', 'com.mojang': 'games', 'com.roblox': 'games',
    'com.miHoYo': 'games', 'com.HoYoverse': 'games', 'com.activision': 'games', 'com.ubisoft': 'games', 'com.innersloth': 'games', 'com.kiloo': 'games', 'com.outfit7': 'games',
    'com.playrix': 'games', 'com.zynga': 'games', 'com.nianticlabs': 'games', 'com.pokemon': 'games', 'com.tencent.ig': 'games', 'com.dts.freefire': 'games', 'com.epicgames': 'games',
    'com.chess': 'games', 'com.nintendo': 'games', 'com.square_enix': 'games', 'com.bandainamco': 'games', 'com.netease': 'games', 'com.voodoo': 'games', 'com.ketchapp': 'games',
    'com.miniclip': 'games', 'com.halfbrick': 'games', 'com.imangi': 'games', 'com.sega': 'games', 'com.konami': 'games', 'com.garena': 'games', 'com.lilithgames': 'games',
    'com.moonactive': 'games', 'com.playtika': 'games', 'com.scopely': 'games', 'com.bethsoft': 'games', 'com.valvesoftware': 'games', 'com.xbox': 'games', 'com.playstation': 'games', 'com.scee': 'games',
    // shopping
    'com.amazon.mShop': 'shop', 'com.ebay': 'shop', 'fr.leboncoin': 'shop', 'com.vinted': 'shop', 'fr.vinted': 'shop', 'com.alibaba.aliexpresshd': 'shop', 'com.zzkko': 'shop',
    'com.einnovation.temu': 'shop', 'com.cdiscount': 'shop', 'com.fnac': 'shop', 'com.contextlogic.wish': 'shop', 'com.ikea': 'shop', 'com.decathlon': 'shop', 'com.zalando': 'shop',
    'com.lidl': 'shop', 'fr.carrefour': 'shop', 'com.carrefour': 'shop', 'com.leclerc': 'shop', 'com.action': 'shop', 'com.shein': 'shop', 'com.etsy': 'shop', 'com.wallapop': 'shop', 'com.backmarket': 'shop',
    // argent
    'com.paypal': 'money', 'com.revolut': 'money', 'com.lydia': 'money', 'fr.creditagricole': 'money', 'com.boursorama': 'money', 'fr.lcl': 'money', 'net.bnpparibas': 'money',
    'com.bnpp': 'money', 'fr.caisseepargne': 'money', 'com.caisseepargne': 'money', 'fr.banquepopulaire': 'money', 'mobi.societegenerale': 'money', 'com.socgen': 'money',
    'com.fortuneo': 'money', 'de.number26': 'money', 'com.binance': 'money', 'com.coinbase': 'money', 'com.transferwise': 'money', 'fr.laposte': 'money', 'com.cic': 'money',
    'fr.creditmutuel': 'money', 'com.google.android.apps.walletnfcrel': 'money', 'com.samsung.android.spay': 'money', 'com.sumeria': 'money', 'com.qonto': 'money', 'com.hellobank': 'money', 'com.ing': 'money', 'com.trading212': 'money',
    // voyage
    'com.google.android.apps.maps': 'travel', 'com.waze': 'travel', 'com.ubercab': 'travel', 'com.sncf': 'travel', 'fr.sncf': 'travel', 'com.airbnb': 'travel', 'com.booking': 'travel',
    'com.comuto': 'travel', 'com.citymapper': 'travel', 'com.ryanair': 'travel', 'com.mttnow.android.easyjet': 'travel', 'com.airfrance': 'travel', 'com.tripadvisor': 'travel',
    'com.thetrainline': 'travel', 'ee.mtakso': 'travel', 'com.heetch': 'travel', 'com.lime': 'travel', 'com.here': 'travel', 'net.osmand': 'travel', 'com.mapy': 'travel', 'com.vinted.go': 'travel',
    'com.flixbus': 'travel', 'com.blablacar': 'travel', 'com.skyscanner': 'travel', 'com.expedia': 'travel', 'com.hotels': 'travel', 'com.ratp': 'travel', 'com.transdev': 'travel', 'fr.lia': 'travel',
    // miam
    'com.ubercab.eats': 'food', 'com.deliveroo': 'food', 'com.justeat': 'food', 'com.mcdonalds': 'food', 'com.app.tgtg': 'food', 'com.dominos': 'food', 'com.burgerking': 'food',
    'fr.kfc': 'food', 'com.marmiton': 'food', 'com.starbucks': 'food', 'com.yuka': 'food', 'io.yuka': 'food', 'com.hellofresh': 'food', 'com.getir': 'food', 'com.flink': 'food', 'com.subway': 'food',
    // santé & sport
    'com.google.android.apps.fitness': 'health', 'com.strava': 'health', 'com.samsung.android.app.shealth': 'health', 'com.fitbit': 'health', 'com.myfitnesspal': 'health',
    'com.doctolib': 'health', 'fr.cnamts': 'health', 'com.calm': 'health', 'com.getsomeheadspace': 'health', 'com.nike': 'health', 'com.basicfit': 'health', 'com.adidas': 'health',
    'com.sec.android.app.shealth': 'health', 'com.huawei.health': 'health', 'com.garmin': 'health', 'com.withings': 'health', 'com.flo': 'health', 'com.mi.health': 'health', 'com.xiaomi.wearable': 'health', 'com.zepp': 'health',
    // actus & lecture
    'fr.lemonde': 'news', 'com.google.android.apps.magazines': 'news', 'fr.lefigaro': 'news', 'com.nextradiotv': 'news', 'fr.franceinfo': 'news', 'fr.francetv.info': 'news', 'com.twentyminutes': 'news',
    'com.amazon.kindle': 'news', 'com.medium': 'news', 'wp.wattpad': 'news', 'com.naver.linewebtoon': 'news', 'flipboard': 'news', 'fr.liberation': 'news', 'com.ouestfrance': 'news',
    'com.google.android.apps.books': 'news', 'com.kobo': 'news', 'com.feedly': 'news', 'com.mangaplus': 'news', 'jp.co.shueisha': 'news', 'com.wikipedia': 'news', 'org.wikipedia': 'news',
    // ia & création
    'com.openai.chatgpt': 'ai', 'com.anthropic.claude': 'ai', 'com.google.android.apps.bard': 'ai', 'com.microsoft.copilot': 'ai', 'ai.perplexity': 'ai', 'ai.mistral': 'ai',
    'com.canva': 'ai', 'com.adobe': 'ai', 'com.lemon.lvoverseas': 'ai', 'com.picsart': 'ai', 'com.prisma': 'ai', 'com.lensa': 'ai', 'com.character': 'ai', 'ai.character': 'ai',
    'com.ibis': 'ai', 'com.medibang': 'ai', 'com.sketchbook': 'ai', 'com.procreate': 'ai', 'com.alightcreative': 'ai', 'com.inshot': 'ai', 'com.camerasideas': 'ai', 'com.google.android.apps.labs': 'ai', 'com.x.grok': 'ai', 'ai.x.grok': 'ai',
    // bureau & cloud
    'com.google.android.apps.docs': 'work', 'com.microsoft.office': 'work', 'com.microsoft.teams': 'work', 'com.Slack': 'work', 'com.slack': 'work', 'notion.id': 'work', 'com.notion': 'work',
    'com.dropbox': 'work', 'com.microsoft.skydrive': 'work', 'com.evernote': 'work', 'com.trello': 'work', 'us.zoom': 'work', 'com.google.android.apps.meetings': 'work', 'com.adobe.reader': 'work',
    'com.google.android.keep': 'work', 'com.samsung.android.app.notes': 'work', 'com.microsoft.todos': 'work', 'com.todoist': 'work', 'com.google.android.apps.tasks': 'work', 'com.infomaniak': 'work', 'com.mega': 'work', 'mega.privacy': 'work',
    // outils
    'com.google.android.calculator': 'tools', 'com.sec.android.app.popupcalculator': 'tools', 'com.google.android.deskclock': 'tools', 'com.sec.android.app.clockpackage': 'tools',
    'com.google.android.apps.nbu.files': 'tools', 'com.sec.android.app.myfiles': 'tools', 'com.google.android.calendar': 'tools', 'com.samsung.android.calendar': 'tools',
    'com.google.android.apps.translate': 'tools', 'com.weather': 'tools', 'com.accuweather': 'tools', 'com.sec.android.daemonapp': 'tools', 'com.google.android.apps.authenticator2': 'tools',
    'com.sec.android.app.voicenote': 'tools', 'com.google.android.apps.recorder': 'tools', 'com.google.ar.lens': 'tools', 'com.miui.calculator': 'tools', 'com.android.calculator2': 'tools',
    'com.android.deskclock': 'tools', 'com.android.calendar': 'tools', 'com.android.documentsui': 'tools', 'com.mi.android.globalFileexplorer': 'tools', 'com.meteofrance': 'tools', 'fr.meteo': 'tools',
    'com.lastpass': 'tools', 'com.x8bit.bitwarden': 'tools', 'com.agilebits': 'tools', 'com.google.android.apps.authenticator': 'tools', 'org.fdroid': 'tools', 'com.termux': 'tools', 'com.anydesk': 'tools',
    // système
    'com.android.settings': 'system', 'com.android.vending': 'system', 'com.google.android.apps.wellbeing': 'system', 'com.samsung.android.lool': 'system',
    'com.google.android.apps.restore': 'system', 'com.samsung.android.app.spage': 'system', 'com.sec.android.app.samsungapps': 'system', 'com.google.android.gms': 'system',
    'com.android.stk': 'system', 'com.samsung.android.app.tips': 'system', 'com.google.android.apps.safetyhub': 'system', 'com.miui.securitycenter': 'system', 'com.android.settings.intelligence': 'system',
    'com.google.android.apps.googleassistant': 'system', 'com.samsung.android.bixby': 'system', 'com.google.android.feedback': 'system', 'com.huawei.appmarket': 'system', 'com.xiaomi.market': 'system',
    'com.samsung.android.smartswitchassistant': 'system', 'com.sec.android.easyMover': 'system', 'com.samsung.android.voc': 'system', 'com.google.android.apps.subscriptions.red': 'system',
  };

  // Mots-clés → dossier (nom affiché + morceaux de l'identifiant).
  const KW = {
    tel: 'phone telephone dialer dial call calls appel appels contacts contact sms mms messages messaging repondeur',
    msg: 'chat messenger messagerie whatsapp telegram signal discord talk im',
    social: 'social instagram facebook tiktok snapchat twitter reddit pinterest community communaute feed threads mastodon',
    date: 'dating rencontre rencontres date dates match love meet gay grindr tinder romeo scruff hornet flirt kink',
    web: 'browser navigateur internet web chrome firefox mail email gmail outlook inbox courriel search',
    photo: 'photo photos camera gallery galerie gallery3d picture pictures pic image images selfie lens album',
    video: 'video videos tv television movie movies film films stream streaming replay vlc anime series player',
    music: 'music musique audio song songs radio podcast podcasts mp3 sound beats spotify deezer dj',
    games: 'game games jeu jeux puzzle racing casino quiz battle clash craft rpg arcade saga fight shooter chess solitaire sudoku poker slots',
    shop: 'shop shopping store boutique market marketplace buy achat deals promo commerce soldes',
    money: 'bank banque banking pay payment wallet money argent finance crypto bourse budget cash compte',
    travel: 'maps map carte cartes gps navigation travel voyage train taxi uber flight vol hotel trip transport metro bus tram velo',
    food: 'food eat eats delivery livraison restaurant pizza burger recette recipes cuisine cook',
    health: 'health sante fitness fit sport sports run running workout gym yoga sleep sommeil meditation doctor medecin pharmacie',
    news: 'news actu actus actualites info infos journal magazine read reader book books livre livres kindle press manga comics webtoon',
    ai: 'ai ia gpt chatgpt claude gemini copilot assistant bot editor create design art draw dessin capcut montage',
    work: 'office docs doc word excel sheets slides drive cloud notes note teams meet zoom pdf scan scanner work travail todo tasks',
    tools: 'calc calculator calculatrice clock horloge alarm alarme timer chrono files fichiers manager weather meteo torch flashlight lampe compass boussole recorder dictaphone translate traduction qr calendar calendrier agenda password vpn',
    system: 'settings parametres reglages system systeme service services launcher keyboard clavier update updater vending security securite backup',
  };
  const GENERIC = new Set('com org fr net io co www android app apps mobile client google lite pro free official the de la le les du des et and for by my mon ma'.split(' '));
  // Catégories déclarées par les applis (ApplicationInfo.category)
  const SYS = { 0: ['games', 3], 1: ['music', 2], 2: ['video', 2], 3: ['photo', 2], 4: ['social', 1.6], 5: ['news', 2], 6: ['travel', 2], 7: ['work', 1.6], 8: ['system', 1] };

  const norm = (s) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const KWMAP = {};
  for (const [cat, words] of Object.entries(KW)) for (const w of words.split(' ')) (KWMAP[w] = KWMAP[w] || []).push(cat);
  const KNOWN_KEYS = Object.keys(KNOWN).sort((a, b) => b.length - a.length);

  function tokens(app) {
    const pk = norm(app.pkg).split(/[._]/).filter((t) => t && !GENERIC.has(t));
    const lb = norm(app.label).split(/[^a-z0-9]+/).filter((t) => t && !GENERIC.has(t));
    return { pk, lb };
  }

  function vendorOf(pkg) {
    const parts = String(pkg || '').split('.');
    for (let i = 1; i < parts.length; i++) if (!GENERIC.has(parts[i].toLowerCase())) return parts[i].toLowerCase();
    return '';
  }

  /** Classe une appli. learn = { vendeur: { cat: poids } } */
  function classify(app, learn) {
    const pkg = app.pkg || '';
    for (const k of KNOWN_KEYS) {
      if (pkg === k || pkg.startsWith(k.endsWith('.') ? k : k + '.') || pkg.startsWith(k) && k.length > 12) {
        return { cat: KNOWN[k], conf: 0.99, why: 'appli connue' };
      }
    }
    const score = {};
    const add = (c, w) => { score[c] = (score[c] || 0) + w; };
    const v = vendorOf(pkg);
    if (learn && v && learn[v]) for (const [c, w] of Object.entries(learn[v])) add(c, w);
    const sc = SYS[app.sysCat];
    if (sc) add(sc[0], sc[1]);
    const { pk, lb } = tokens(app);
    const hit = (t, w) => {
      if (KWMAP[t]) for (const c of KWMAP[t]) add(c, w);
      else if (t.length >= 5) {
        for (const k in KWMAP) if (k.length >= 4 && t.includes(k)) for (const c of KWMAP[k]) add(c, w * 0.5);
      }
    };
    lb.forEach((t) => hit(t, 1.1));
    pk.forEach((t) => hit(t, 0.8));
    if (app.system && !Object.keys(score).length) add('system', 0.7);
    const ranked = Object.entries(score).sort((a, b) => b[1] - a[1]);
    if (!ranked.length) return { cat: 'sort', conf: 0, why: 'aucun indice' };
    const [top, s1] = ranked[0];
    const s2 = ranked[1] ? ranked[1][1] : 0;
    const conf = s1 / (s1 + s2 + 0.6);
    if (s1 < 1 || s1 - s2 < 0.35) return { cat: 'sort', conf, why: 'hésitation', guess: top };
    return { cat: top, conf, why: 'indices' };
  }

  /** Apprend d'une correction : l'éditeur de l'appli penche vers ce dossier. */
  function learnFrom(app, cat, learn) {
    const v = vendorOf(app.pkg);
    if (!v || cat === 'sort') return learn;
    learn[v] = learn[v] || {};
    learn[v][cat] = (learn[v][cat] || 0) + 1.6;
    return learn;
  }

  window.Flair = { CATS, classify, learnFrom, vendorOf, cat: (id) => CATS.find((c) => c.id === id) || CATS[CATS.length - 1] };
})();
