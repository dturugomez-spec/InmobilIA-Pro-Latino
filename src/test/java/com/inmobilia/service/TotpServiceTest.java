package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TotpServiceTest {

    private final TotpService totp = new TotpService();

    @Test
    void generateSecret_returnsBase32String() {
        String secret = totp.generateSecret();
        assertNotNull(secret);
        assertTrue(secret.length() >= 16);
        assertTrue(secret.matches("[A-Z2-7]+"));
    }

    @Test
    void otpAuthUrl_followsRfcFormat() {
        String url = totp.otpAuthUrl("InmobilIA Pro", "user@email.com", "JBSWY3DPEHPK3PXP");
        assertTrue(url.startsWith("otpauth://totp/"));
        assertTrue(url.contains("secret=JBSWY3DPEHPK3PXP"));
        assertTrue(url.contains("issuer="));
        assertTrue(url.contains("algorithm=SHA1"));
        assertTrue(url.contains("digits=6"));
    }

    @Test
    void verify_rejectsInvalidFormat() {
        String secret = totp.generateSecret();
        assertFalse(totp.verify(secret, null));
        assertFalse(totp.verify(secret, ""));
        assertFalse(totp.verify(secret, "abcdef"));
        assertFalse(totp.verify(secret, "12345"));    // too short
        assertFalse(totp.verify(secret, "1234567"));  // too long
    }

    @Test
    void base32_encodeDecodeRoundtrip() {
        byte[] data = "hello world test".getBytes();
        String encoded = TotpService.base32Encode(data);
        byte[] decoded = TotpService.base32Decode(encoded);
        assertArrayEquals(data, decoded);
    }
}
