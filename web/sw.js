// VIX AI service worker: network first, so updates always show.
// The saved copy is only used when the phone is offline.
const CACHE = "vix-web-v1";

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
    .keys()
    .then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    )
    .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  
  // Only handle this site's own files. Supabase, Google and the CDN go straight through.
  if (request.method !== "GET" || url.origin !== self.location.origin) return;
  
  event.respondWith(
    fetch(request)
    .then((response) => {
      if (response.ok) {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(request, copy));
      }
      return response;
    })
    .catch(() =>
      caches
      .match(request)
      .then((hit) => hit || (request.mode === "navigate" ? caches.match("index.html") : undefined))
    )
  );
});