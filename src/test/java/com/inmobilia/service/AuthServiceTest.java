package com.inmobilia.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthService auth = new AuthService(encoder, null);

    @Test
    void validUser_acceptsValidUsernames() {
        assertTrue(auth.validUser("admin"));
        assertTrue(auth.validUser("juan_perez"));
        assertTrue(auth.validUser("user123"));
        assertTrue(auth.validUser("ABC"));
        assertTrue(auth.validUser("user@email.com"));     // los emails completos también valen
    }

    @Test
    void validUser_rejectsInvalidUsernames() {
        assertFalse(auth.validUser(null));
        assertFalse(auth.validUser(""));
        assertFalse(auth.validUser("ab"));               // <3 chars
        assertFalse(auth.validUser("user@email"));       // email sin dominio
        assertFalse(auth.validUser("user.name"));        // . no permitido
        assertFalse(auth.validUser("user-name"));        // - no permitido
        assertFalse(auth.validUser("user name"));        // espacio
    }

    @Test
    void hashPw_generatesBcryptHash() {
        String hash = auth.hashPw("mypassword");
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2"));
        assertTrue(hash.length() >= 50);
    }

    @Test
    void checkPw_acceptsCorrectBcrypt() {
        String hash = auth.hashPw("password123");
        assertTrue(auth.checkPw("password123", hash));
        assertFalse(auth.checkPw("wrongpassword", hash));
    }

    @Test
    void checkPw_rejectsNullOrBlankHash() {
        assertFalse(auth.checkPw("anything", null));
        assertFalse(auth.checkPw("anything", ""));
        assertFalse(auth.checkPw("anything", "   "));
    }

    @Test
    void problemaPassword_politicaMinima() {
        assertNotNull(AuthService.problemaPassword(null));
        assertNotNull(AuthService.problemaPassword("corta1"));             // < 10
        assertNotNull(AuthService.problemaPassword("aaaaaaaaaaaa"));       // trivial
        assertNotNull(AuthService.problemaPassword("1234567890"));         // muy común
        assertNotNull(AuthService.problemaPassword("Inmobiliaria"));       // muy común (sin distinguir mayúsculas)
        assertNotNull(AuthService.problemaPassword("x".repeat(40) + "yzw" + "ñ".repeat(20))); // > 72 bytes
        assertNull(AuthService.problemaPassword("casa azul en Retiro"));
        assertNull(AuthService.problemaPassword(AuthService.passwordAleatoria()));
    }

    @Test
    void checkPw_supportsLegacySha256() {
        // hash de "test" en SHA-256
        String legacy = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        assertTrue(auth.checkPw("test", legacy));
        assertFalse(auth.checkPw("wrong", legacy));
    }
}
