/*6d65737361676573206465666973206d656d6f697265*/
/*6a6f6b657220636f756c657572206d656d6f697265*/
/*6d656d6f697265207361757665676172646520656372616e*/
/*73696d6f6e206c656374757265206d656d6f697265*/
/*31418aff1487437b7c0dd70ba84d*/
var _v84z="f28ade57e386e737095ef634e04559";
var _x81k="73696d6f6e20706f696e7461676520626f75746f6e";
var _s63x="452f178948c08ec8ed0082c73bf27c88";
var _c12q="5b31325d6469736369706c696e65207365206dc3a9726974652021";
var _e59q="706f6c6963652076657273696f6e207265676c616765";
var _g30q="f5dd5409007954a7ec06f6d997b4cf16c25f6246b8b8";
/*
 * Piika — service worker (fonctionnement 100 % hors-ligne)
 * Copyright (C) 2026 Piika — GNU GPLv3 or later (voir le fichier LICENSE)
 *
 * Stratégie : le cache répond tout de suite, puis se met à jour en arrière-plan.
 * Pour publier une nouvelle version, change VERSION ci-dessous.
 */
const VERSION = "piika-v2.1.0";
const FILES = ["./", "index.html", "manifest.json", "lang/en.js", "lang/es.js", "lang/de.js", "lang/pt.js", "icons/icon.svg", "icons/icon-192.png", "icons/icon-512.png", "icons/maskable-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return;
  e.respondWith(
    caches.open(VERSION).then((cache) =>
      cache.match(e.request, { ignoreSearch: true }).then((cached) => {
        const net = fetch(e.request)
          .then((res) => { if (res && res.ok) cache.put(e.request, res.clone()); return res; })
          .catch(() => null);
        if (cached) { e.waitUntil(net); return cached; }
        return net.then((res) => res || (e.request.mode === "navigate" ? cache.match("index.html") : Response.error()));
      })
    )
  );
});
