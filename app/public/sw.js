const CORE_CACHE = 'luckystar-core-v6';
const RUNTIME_CACHE = 'luckystar-runtime-v6';
const LIBRARY_CACHE = 'luckystar-library-v6';

const APP_SHELL = [
  '/luckystar/',
  '/luckystar/manifest.webmanifest',
  '/luckystar/data/library.json',
  '/luckystar/icons/icon-192.png',
  '/luckystar/icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CORE_CACHE)
      .then((cache) => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keep = new Set([CORE_CACHE, RUNTIME_CACHE, LIBRARY_CACHE]);
    const keys = await caches.keys();
    await Promise.all(keys.map((key) => (keep.has(key) ? null : caches.delete(key))));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (!url.pathname.startsWith('/luckystar/')) return;

  if (url.pathname.startsWith('/luckystar/library/')) {
    event.respondWith(cacheFirst(request, LIBRARY_CACHE));
    return;
  }

  if (url.pathname.endsWith('/data/library.json') || url.pathname.includes('/data/')) {
    event.respondWith(staleWhileRevalidate(request, RUNTIME_CACHE));
    return;
  }

  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request, CORE_CACHE, '/luckystar/'));
    return;
  }

  if (url.pathname.includes('/assets/')) {
    event.respondWith(staleWhileRevalidate(request, RUNTIME_CACHE));
    return;
  }

  event.respondWith(cacheFirst(request, RUNTIME_CACHE));
});

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  if (cached) return cached;

  const response = await fetch(request);
  if (response.ok) cache.put(request, response.clone());
  return response;
}

async function staleWhileRevalidate(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);

  const network = fetch(request)
    .then((response) => {
      if (response.ok) cache.put(request, response.clone());
      return response;
    })
    .catch(() => cached);

  return cached || network;
}

async function networkFirst(request, cacheName, fallbackUrl) {
  const cache = await caches.open(cacheName);
  try {
    const response = await fetch(request);
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch {
    return (await cache.match(request)) || (await cache.match(fallbackUrl));
  }
}
