package com.inmobilia.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private final RateLimiter rl = new RateLimiter();

    @Test
    void allowsAttemptsWithinLimit() {
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryAcquire("test1", 5, Duration.ofMinutes(5)));
        }
    }

    @Test
    void blocksAfterMaxAttempts() {
        String key = "test-block";
        for (int i = 0; i < 5; i++) {
            rl.tryAcquire(key, 5, Duration.ofMinutes(5));
        }
        assertFalse(rl.tryAcquire(key, 5, Duration.ofMinutes(5)));
    }

    @Test
    void resetClearsBlock() {
        String key = "test-reset";
        for (int i = 0; i < 6; i++) rl.tryAcquire(key, 5, Duration.ofMinutes(5));
        rl.reset(key);
        assertTrue(rl.tryAcquire(key, 5, Duration.ofMinutes(5)));
    }

    @Test
    void soloCuentanLosFallos() {
        String key = "login-cuenta:ana@inmo.es";
        Duration v = Duration.ofMinutes(15);
        assertFalse(rl.bloqueado(key, 3, v), "consultar no suma intentos");
        assertFalse(rl.bloqueado(key, 3, v));
        rl.registrarFallo(key, v);
        rl.registrarFallo(key, v);
        assertFalse(rl.bloqueado(key, 3, v));
        rl.registrarFallo(key, v);
        assertTrue(rl.bloqueado(key, 3, v));
        rl.reset(key);
        assertFalse(rl.bloqueado(key, 3, v));
    }

    @Test
    void differentKeysAreIndependent() {
        for (int i = 0; i < 5; i++) rl.tryAcquire("key-a", 5, Duration.ofMinutes(5));
        // key-a bloqueada, key-b debería seguir disponible
        assertTrue(rl.tryAcquire("key-b", 5, Duration.ofMinutes(5)));
    }
}
