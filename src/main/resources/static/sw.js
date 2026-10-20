/* ═══════════════════════════════════════════════════════════════════
   InmobilIA Pro · Service Worker
   Estrategia:
     - Static assets (CSS/JS/iconos): Cache-First (rapidisimo)
     - Navegacion HTML: Network-First con fallback a cache + offline.html
     - APIs y POSTs: pasan directos a red, sin cachear
   ═══════════════════════════════════════════════════════════════════ */

const VERSION = 'inmobilia-v1.0.1';
const STATIC_CACHE  = `${VERSION}-static`;
const RUNTIME_CACHE = `${VERSION}-runtime`;

// Recursos esenciales precacheados al instalar
const PRECACHE_URLS = [
  '/',
  '/manifest.json',
  '/css/main.css',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/offline'
];

// ─── INSTALL ───
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(STATIC_CACHE)
      .then(cache => cache.addAll(PRECACHE_URLS).catch(err => {
        console.warn('[SW] precache parcial:', err);
      }))
      .then(() => self.skipWaiting())
  );
});

// ─── ACTIVATE: limpia versiones antiguas ───
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(k => !k.startsWith(VERSION))
          .map(k => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

// ─── FETCH: estrategias por tipo ───
self.addEventListener('fetch', event => {
  const req = event.request;
  const url = new URL(req.url);

  // Solo same-origin GET
  if (req.method !== 'GET' || url.origin !== location.origin) {
    return;
  }

  // No interceptar autenticacion ni endpoints sensibles
  if (url.pathname.startsWith('/login') ||
      url.pathname.startsWith('/logout') ||
      url.pathname.startsWith('/register') ||
      url.pathname.startsWith('/api/')) {
    return;
  }

  // Static assets: Cache-First
  if (url.pathname.startsWith('/css/') ||
      url.pathname.startsWith('/js/') ||
      url.pathname.startsWith('/icons/') ||
      url.pathname.endsWith('.css') ||
      url.pathname.endsWith('.js') ||
      url.pathname.endsWith('.png') ||
      url.pathname.endsWith('.jpg') ||
      url.pathname.endsWith('.svg') ||
      url.pathname.endsWith('.woff2') ||
      url.pathname.endsWith('.woff')) {
    event.respondWith(cacheFirst(req));
    return;
  }

  // Navegacion HTML: Network-First con fallback
  if (req.mode === 'navigate' || (req.headers.get('accept') || '').includes('text/html')) {
    event.respondWith(networkFirst(req));
    return;
  }

  // Resto: red con cache de respaldo
  event.respondWith(networkFirst(req));
});

// Cache-First: si esta en cache lo sirve, si no va a red y cachea.
async function cacheFirst(req) {
  const cached = await caches.match(req);
  if (cached) return cached;
  try {
    const fresh = await fetch(req);
    if (fresh && fresh.status === 200) {
      const cache = await caches.open(STATIC_CACHE);
      cache.put(req, fresh.clone());
    }
    return fresh;
  } catch (err) {
    return new Response('Offline', { status: 503, statusText: 'Offline' });
  }
}

// Network-First: red prioritaria, si falla tira de cache, si no hay nada -> offline.
async function networkFirst(req) {
  try {
    const fresh = await fetch(req);
    if (fresh && fresh.status === 200) {
      const cache = await caches.open(RUNTIME_CACHE);
      cache.put(req, fresh.clone());
    }
    return fresh;
  } catch (err) {
    const cached = await caches.match(req);
    if (cached) return cached;
    if (req.mode === 'navigate') {
      const offline = await caches.match('/offline');
      if (offline) return offline;
    }
    return new Response(
      '<!doctype html><html lang="es"><head><meta charset="utf-8"><title>Sin conexion</title></head>' +
      '<body style="font-family:sans-serif;text-align:center;padding:3rem"><h1>📡 Sin conexion</h1>' +
      '<p>No has podido cargar esta pagina. Comprueba tu red e intentalo otra vez.</p></body></html>',
      { headers: { 'Content-Type': 'text/html; charset=utf-8' }, status: 503 }
    );
  }
}

// ─── PUSH NOTIFICATIONS (preparado para activar despues) ───
self.addEventListener('push', event => {
  if (!event.data) return;
  let data = {};
  try { data = event.data.json(); } catch (e) { data = { title: 'InmobilIA Pro', body: event.data.text() }; }
  const opts = {
    body: data.body || '',
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    data: { url: data.url || '/dashboard' }
  };
  event.waitUntil(self.registration.showNotification(data.title || 'InmobilIA Pro', opts));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const url = event.notification.data?.url || '/dashboard';
  event.waitUntil(
    clients.matchAll({ type: 'window' }).then(list => {
      for (const c of list) { if (c.url.includes(url) && 'focus' in c) return c.focus(); }
      if (clients.openWindow) return clients.openWindow(url);
    })
  );
});

// ─── MESSAGES desde la pagina (forzar actualizacion) ───
self.addEventListener('message', event => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});
