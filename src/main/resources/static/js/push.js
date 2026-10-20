/* InmobilIA Pro · Sistema de notificaciones (Push + polling fallback) */

(function() {
    'use strict';

    const POLL_INTERVAL = 60_000; // 1 minuto
    let lastCheckedAt = Date.now();
    let knownIds = new Set();

    async function requestPermissionAndSubscribe() {
        if (!('Notification' in window)) return false;
        if (Notification.permission === 'denied') return false;

        if (Notification.permission === 'default') {
            const result = await Notification.requestPermission();
            if (result !== 'granted') return false;
        }
        return true;
    }

    async function poll() {
        if (document.hidden === false) return; // si la pestaña está activa, no spamear
        try {
            const res = await fetch('/api/notifications/recent', { credentials: 'same-origin' });
            if (!res.ok) return;
            const data = await res.json();
            if (!data.ok || !data.items) return;

            data.items.forEach(n => {
                if (knownIds.has(n.id)) return;
                knownIds.add(n.id);
                const isNew = !n.read_at || n.read_at === 'null';
                if (isNew && Notification.permission === 'granted') {
                    new Notification(n.title || 'InmobilIA Pro', {
                        body: n.message || '',
                        icon: '/icons/icon-192.png',
                        badge: '/icons/icon-192.png',
                        tag: 'notif-' + n.id,
                        data: { url: n.link || '/dashboard' }
                    }).onclick = function(e) {
                        e.preventDefault();
                        window.focus();
                        if (n.link) window.location.href = n.link;
                    };
                }
            });
        } catch (e) {}
    }

    // Botón global para activar
    window.enablePushNotifications = async function() {
        const ok = await requestPermissionAndSubscribe();
        if (ok && window.toast) window.toast('🔔 Notificaciones activadas', 'ok');
        else if (window.toast) window.toast('No se pudieron activar', 'warn');
        return ok;
    };

    // Iniciar polling si tenemos permiso
    document.addEventListener('DOMContentLoaded', () => {
        if (Notification.permission === 'granted') {
            setInterval(poll, POLL_INTERVAL);
            // Cargar IDs actuales para no notificar las viejas
            fetch('/api/notifications/recent', { credentials: 'same-origin' })
                .then(r => r.json()).then(d => {
                    if (d.ok && d.items) d.items.forEach(n => knownIds.add(n.id));
                }).catch(() => {});
        }
    });
})();
