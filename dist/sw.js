const CORE = 'luckystar-core-v3';
const RUNTIME = 'luckystar-runtime-v3';
const DOWNLOADS = 'luckystar-downloads-v3';
const APP_SHELL = [
  '/luckystar/',
  '/luckystar/manifest.webmanifest',
  '/luckystar/data/chapters.json',
  '/luckystar/icons/icon-192.png',
  '/luckystar/icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CORE).then((cache) => cache.addAll(APP_SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.map((key) => {
    if (![CORE, RUNTIME, DOWNLOADS].includes(key)) return caches.delete(key);
    return null;
  }))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);

  if (url.pathname.includes('/raki_suta/')) {
    event.respondWith(cacheFirst(request, DOWNLOADS));
    return;
  }

  if (url.pathname.startsWith('/luckystar/')) {
    event.respondWith(staleWhileRevalidate(request));
  }
});

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok) await cache.put(request, response.clone());
  return response;
}

async function staleWhileRevalidate(request) {
  const cache = await caches.open(RUNTIME);
  const cached = await cache.match(request);
  const networkPromise = fetch(request).then((response) => {
    if (response.ok) cache.put(request, response.clone());
    return response;
  }).catch(() => cached);
  return cached || networkPromise;
}
