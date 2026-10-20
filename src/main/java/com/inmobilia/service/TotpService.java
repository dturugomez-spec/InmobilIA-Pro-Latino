package com.inmobilia.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Implementación de TOTP (RFC 6238) compatible con Google Authenticator, Authy, 1Password.
 * Sin dependencias externas — solo java.crypto.
 */
@Service
public class TotpService {

    private static final int TIME_STEP = 30;
    private static final int CODE_DIGITS = 6;

    /** Genera un secreto base32 nuevo de 160 bits (32 caracteres, lo recomendado por el RFC 4226). */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** URL otpauth:// para QR (compatible con Google Authenticator). */
    public String otpAuthUrl(String issuer, String account, String secret) {
        String label = issuer + ":" + account;
        return "otpauth://totp/" + urlEncode(label)
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + TIME_STEP;
    }

    /** Verifica el código (acepta ±1 ventana de 30s para tolerar drift). */
    public boolean verify(String secret, String code) {
        return verifyStep(secret, code) >= 0;
    }

    /**
     * Verifica el código y devuelve el paso de tiempo (ventana de 30 s) en el que encaja,
     * o -1 si no vale. Con el paso se impide reutilizar un mismo código (anti-replay).
     * Comparación en tiempo constante.
     */
    public long verifyStep(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null) return -1;
        code = code.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) return -1;
        byte[] dado = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        long now = Instant.now().getEpochSecond() / TIME_STEP;
        long encontrado = -1;
        for (long t = now - 1; t <= now + 1; t++) {
            byte[] esperado = generate(secret, t).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (java.security.MessageDigest.isEqual(esperado, dado)) encontrado = t;
        }
        return encontrado;
    }

    private String generate(String secret, long time) {
        try {
            byte[] key = base32Decode(secret);
            byte[] data = ByteBuffer.allocate(8).putLong(time).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (Exception e) {
            return "";
        }
    }

    // ─── Base32 encode/decode (RFC 4648) ───
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        s = s.replaceAll("\\s", "").replaceAll("=+$", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (char c : s.toCharArray()) {
            int idx = ALPHABET.indexOf(c);
            if (idx < 0) continue;
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }
}
