package com.inmobilia.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter en memoria · ventana fija por clave.
 * Limita intentos de login/2FA/recuperación por IP y por cuenta para frenar fuerza bruta y spam.
 */
@Service
public class RateLimiter {

    private static final long CADUCIDAD_MAX_MS = Duration.ofHours(2).toMillis();

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Permite N intentos cada D minutos por clave (cuenta todos los intentos). */
    public boolean tryAcquire(String key, int maxAttempts, Duration window) {
        return registrarFallo(key, window) <= maxAttempts;
    }

    /** Suma un intento fallido a la clave y devuelve cuántos lleva en la ventana. */
    public int registrarFallo(String key, Duration window) {
        long now = Instant.now().toEpochMilli();
        Bucket b = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > window.toMillis()) {
                return new Bucket(now, 1);
            }
            existing.count++;
            return existing;
        });
        return b.count;
    }

    /** ¿La clave ya ha llegado al máximo de fallos dentro de la ventana? (no suma intento) */
    public boolean bloqueado(String key, int maxFallos, Duration window) {
        Bucket b = buckets.get(key);
        if (b == null) return false;
        if (Instant.now().toEpochMilli() - b.windowStart > window.toMillis()) {
            buckets.remove(key, b);
            return false;
        }
        return b.count >= maxFallos;
    }

    public long resetIn(String key, Duration window) {
        Bucket b = buckets.get(key);
        if (b == null) return 0;
        long elapsed = Instant.now().toEpochMilli() - b.windowStart;
        long left = window.toMillis() - elapsed;
        return Math.max(0, left / 1000);
    }

    public void reset(String key) { buckets.remove(key); }

    /** Limpia contadores viejos para que el mapa no crezca sin fin. */
    @Scheduled(fixedDelay = 15 * 60 * 1000L)
    public void limpiar() {
        long now = Instant.now().toEpochMilli();
        buckets.entrySet().removeIf(e -> now - e.getValue().windowStart > CADUCIDAD_MAX_MS);
    }

    private static class Bucket {
        final long windowStart;
        int count;
        Bucket(long start, int c) { this.windowStart = start; this.count = c; }
    }
}
