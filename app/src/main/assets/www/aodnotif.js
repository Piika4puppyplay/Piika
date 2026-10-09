/* Médailles de notifications pour la Veille puppy et la Sieste (OLED : noir pur, pas de halo). */
(function () {
  'use strict';
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const css = `#aodn{display:flex;flex-direction:column;align-items:center;gap:8px;margin-top:10px;min-height:0}
#aodn .tags{display:flex;flex-wrap:wrap;justify-content:center;gap:12px}
#aodn .tag{position:relative;display:flex;flex-direction:column;align-items:center}
#aodn .tag::before{content:"";width:10px;height:10px;margin-bottom:-4px;border-radius:50%;border:2px solid #8e879c;z-index:1}
#aodn .disc{width:40px;height:40px;border-radius:50%;padding:4px;background:linear-gradient(160deg,#d6d0e2,#6e687c 55%,#b9b3c6);box-shadow:0 0 0 1.5px #000}
#aodn .disc img{display:block;width:100%;height:100%;border-radius:50%;background:#000}
#aodn .n{position:absolute;right:-7px;bottom:-4px;min-width:19px;height:19px;padding:0 5px;border-radius:10px;font:400 10.5px var(--f-head);line-height:19px;text-align:center;color:#fff;background:var(--acc);box-shadow:0 0 0 1.5px #000}
#aodn .lst{display:flex;flex-direction:column;gap:3px;max-width:88vw}
#aodn .it{font:13px var(--f-body);color:#cdbfe0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;text-align:center}
#aodn .it b{font-family:var(--f-head);font-weight:400;font-size:11px;color:var(--acc2);margin-right:6px}`;
  const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
  function render(js, el) {
    let a = []; try { a = JSON.parse(js || '[]') || []; } catch (e) { a = []; }
    el = el || document.getElementById('aodn'); if (!el) return;
    if (!a.length) { el.innerHTML = ''; return; }
    const tags = a.slice(0, 7).map((g) => `<span class="tag"><span class="disc"><img src="/nicon?pkg=${encodeURIComponent(g.pkg)}" alt="" onerror="this.style.visibility='hidden'"></span>${g.n > 1 ? `<span class="n">${g.n > 99 ? '99+' : g.n}</span>` : ''}</span>`).join('');
    const withTxt = a.filter((g) => g.title || g.text).slice(0, 3);
    el.innerHTML = `<div class="tags">${tags}</div>${withTxt.length ? `<div class="lst">${withTxt.map((g) => `<div class="it"><b>${esc(g.app)}</b>${esc(g.title || g.text)}</div>`).join('')}</div>` : ''}`;
  }
  window.AodNotif = { render };
})();
