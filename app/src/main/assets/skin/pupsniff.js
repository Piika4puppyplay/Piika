/* Renifleur de CGU (PuppyInternet) : surligne localement les passages sur les modifications, extensions, robots, IA, scraping.
   Aucune IA, aucun envoi : tout se passe dans la page affichée. Renvoie un résumé JSON (empreinte du texte + passages). */
(function () {
  const KW = [
    ['IA', /(intelligence artificielle|artificial intelligence|\bIA\b|\bAI\b|machine learning|apprentissage automatique|LLM|modèles? de langage|language models?|entra[iî]n(er|ement)|train(ing)? (data|models?))/i],
    ['Fouille / scraping', /(scrap|aspir|crawl|fouille de (textes|données)|text and data mining|data mining|extraction (automatisée|de données)|harvest)/i],
    ['Robots / automatisation', /(\brobots?\b|\bbots?\b|automatis|automated|script|spider|araignée)/i],
    ['Modification / extensions', /(modifi(er|cation)s? (le|du|de la|des)? ?(site|service|contenu|interface|affichage)|altér|extensions?|plug-?ins?|add-?ons?|navigateur|browser|ad-?block|bloqueur)/i],
    ['Contournement', /(contourn|circumvent|reverse engineer|ingénierie inverse|décompil|bypass)/i],
  ];
  const blocks = Array.from(document.querySelectorAll('p, li, td, h1, h2, h3, h4, section > div, article > div')).filter((e) => e.children.length < 12 && (e.innerText || '').trim().length > 30);
  const hits = [];
  const ranges = [];
  let id = 0;
  for (const b of blocks) {
    const t = (b.innerText || '').replace(/\s+/g, ' ').trim();
    const kinds = KW.filter((k) => k[1].test(t)).map((k) => k[0]);
    if (!kinds.length) continue;
    if (hits.some((h) => h.el.contains(b) || b.contains(h.el))) continue;
    b.setAttribute('data-pupsniff', String(++id));
    // surlignage des mots-clés (API Highlight, sans modifier la page)
    const tw = document.createTreeWalker(b, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = tw.nextNode())) {
      for (const k of KW) {
        const re = new RegExp(k[1].source, 'gi');
        let m;
        while ((m = re.exec(n.nodeValue)) && ranges.length < 2000) { const r = new Range(); r.setStart(n, m.index); r.setEnd(n, m.index + m[0].length); ranges.push(r); if (!m[0].length) re.lastIndex++; }
      }
    }
    hits.push({ el: b, id, kinds, snippet: t.slice(0, 220) });
    if (hits.length >= 60) break;
  }
  if (window.CSS && CSS.highlights && typeof Highlight !== 'undefined') CSS.highlights.set('pupsniff', new Highlight(...ranges));
  let st = document.getElementById('pupsniff-style');
  if (!st) {
    st = document.createElement('style'); st.id = 'pupsniff-style';
    st.textContent = '::highlight(pupsniff){background:rgba(255,63,164,.45);color:#fff}[data-pupsniff]{outline:2px dashed rgba(41,230,255,.6);outline-offset:4px;border-radius:6px}[data-pupsniff].pupflash{animation:pupflash 1.2s 2}@keyframes pupflash{50%{outline-color:#ff3fa4;box-shadow:0 0 24px #ff3fa4}}'
      + '#pupsniff-panel{position:fixed;left:8px;right:8px;bottom:8px;max-height:46vh;overflow:auto;z-index:2147483647;border-radius:16px;padding:10px;font:14px system-ui,sans-serif;color:#fff;background:linear-gradient(180deg,rgba(255,255,255,.18),rgba(255,255,255,.03) 40px),#1b0f2e;border:1px solid rgba(255,255,255,.6);box-shadow:0 0 30px rgba(255,63,164,.6),0 12px 30px rgba(0,0,0,.7)}'
      + '#pupsniff-panel b.h{display:block;font-size:15px;margin:2px 4px 8px}#pupsniff-panel button{all:unset;display:block;cursor:pointer;margin:0 0 6px;padding:8px 10px;border-radius:10px;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.15)}#pupsniff-panel small{display:block;color:#29e6ff;font-size:11px;letter-spacing:.06em;text-transform:uppercase;margin-bottom:2px}#pupsniff-panel .x{position:sticky;top:0;float:right;background:#e8243a;border:0;padding:4px 10px;margin:0}';
    document.documentElement.appendChild(st);
  }
  let p = document.getElementById('pupsniff-panel');
  if (p) p.remove();
  p = document.createElement('div'); p.id = 'pupsniff-panel';
  p.innerHTML = '<button class="x" type="button">✕</button><b class="h">🐾 Renifleur de CGU : ' + hits.length + ' passage' + (hits.length > 1 ? 's' : '') + ' à lire</b>';
  if (!hits.length) p.insertAdjacentHTML('beforeend', '<div style="padding:4px 6px;color:#eadcf7">Rien trouvé sur l\'IA, les robots, le scraping ou les modifications dans cette page. Vérifie que c\'est bien la page des conditions.</div>');
  for (const h of hits) {
    const btn = document.createElement('button'); btn.type = 'button';
    const sm = document.createElement('small'); sm.textContent = h.kinds.join(' · ');
    btn.appendChild(sm); btn.appendChild(document.createTextNode(h.snippet + (h.snippet.length >= 220 ? '…' : '')));
    btn.onclick = () => { h.el.scrollIntoView({ behavior: 'smooth', block: 'center' }); h.el.classList.remove('pupflash'); void h.el.offsetWidth; h.el.classList.add('pupflash'); };
    p.appendChild(btn);
  }
  p.querySelector('.x').onclick = () => p.remove();
  document.documentElement.appendChild(p);
  // empreinte du texte complet (pour savoir si les CGU changent)
  const txt = (document.body.innerText || '').replace(/\s+/g, ' ').trim();
  let hsh = 5381; for (let i = 0; i < txt.length; i++) hsh = ((hsh << 5) + hsh + txt.charCodeAt(i)) | 0;
  return JSON.stringify({ hash: (hsh >>> 0).toString(16) + ':' + txt.length, count: hits.length, kinds: Array.from(new Set(hits.flatMap((h) => h.kinds))), title: document.title, url: location.href });
})();
