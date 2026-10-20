package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TwoFactorServiceTest {

    private final TotpService totp = new TotpService();
    private final TwoFactorService twoFactor = new TwoFactorService(totp, null, new CifradoService(""));

    @Test
    void cifrado_aes_ida_y_vuelta_y_compatibleConSecretosEnClaro() {
        String clave = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        CifradoService c = new CifradoService(clave);
        String cifrado = c.cifrar("JBSWY3DPEHPK3PXP");
        assertTrue(CifradoService.estaCifrado(cifrado));
        assertNotEquals(cifrado, c.cifrar("JBSWY3DPEHPK3PXP"), "IV aleatorio: nunca el mismo texto cifrado");
        assertEquals("JBSWY3DPEHPK3PXP", c.descifrar(cifrado));
        assertEquals("JBSWY3DPEHPK3PXP", c.descifrar("JBSWY3DPEHPK3PXP"), "lo antiguo en claro se sigue leyendo");

        byte[] otra = new byte[32];
        otra[0] = 1;
        assertNull(new CifradoService(java.util.Base64.getEncoder().encodeToString(otra)).descifrar(cifrado), "otra clave no descifra");
        assertNull(new CifradoService("").descifrar(cifrado), "sin clave no se puede leer");
        assertEquals("abc", new CifradoService("").cifrar("abc"), "sin clave se guarda tal cual");

        TwoFactorService tf = new TwoFactorService(totp, null, c);
        Map<String, Object> u = new HashMap<>();
        u.put("totp_secret", "JBSWY3DPEHPK3PXP");
        assertTrue(tf.necesitaCifrar(u));
        u.put("totp_secret", tf.secretoParaGuardar("JBSWY3DPEHPK3PXP"));
        assertFalse(tf.necesitaCifrar(u));
        assertEquals("JBSWY3DPEHPK3PXP", tf.secretoDe(u));
    }

    /** Código de 6 cifras para un paso de 30 s concreto (método privado de TotpService). */
    private String codigo(String secreto, long paso) throws Exception {
        Method m = TotpService.class.getDeclaredMethod("generate", String.class, long.class);
        m.setAccessible(true);
        return (String) m.invoke(totp, secreto, paso);
    }

    @Test
    void totp_coincideConLosVectoresDelRfc6238() throws Exception {
        // RFC 6238, apéndice B (SHA-1, secreto ASCII "12345678901234567890"), truncado a 6 cifras
        String secreto = TotpService.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertEquals("287082", codigo(secreto, 59L / 30));
        assertEquals("081804", codigo(secreto, 1111111109L / 30));
        assertEquals("005924", codigo(secreto, 1234567890L / 30));
        assertEquals("279037", codigo(secreto, 2000000000L / 30));
    }

    @Test
    void secretoNuevo_tiene160Bits() {
        String s = totp.generateSecret();
        assertEquals(32, s.length());
        assertEquals(20, TotpService.base32Decode(s).length);
    }

    @Test
    void codigoActual_valeUnaSolaVez() throws Exception {
        String secreto = totp.generateSecret();
        String ahora = codigo(secreto, Instant.now().getEpochSecond() / 30);
        assertTrue(twoFactor.verificarCodigo("u1", secreto, ahora));
        assertFalse(twoFactor.verificarCodigo("u1", secreto, ahora), "el mismo código no puede reutilizarse");
        assertFalse(twoFactor.verificarCodigo("u2", secreto, "000000".equals(ahora) ? "111111" : "000000"));
    }

    @Test
    void codigosDeRecuperacion_sirvenUnaVezYAdmitenMayusculasSinGuion() {
        List<String> codigos = twoFactor.generarCodigosRecuperacion();
        assertEquals(TwoFactorService.NUM_CODIGOS, codigos.size());
        assertTrue(codigos.get(0).matches("[a-z2-9]{4}-[a-z2-9]{4}"));

        Map<String, Object> user = new HashMap<>();
        user.put("totp_recovery_codes", TwoFactorService.hashesParaGuardar(codigos));
        assertEquals(10, TwoFactorService.codigosRestantes(user));

        String escrito = codigos.get(3).toUpperCase().replace("-", "");
        String quedan = TwoFactorService.consumirCodigoRecuperacion(user, escrito);
        assertNotNull(quedan);
        user.put("totp_recovery_codes", quedan);
        assertEquals(9, TwoFactorService.codigosRestantes(user));
        assertNull(TwoFactorService.consumirCodigoRecuperacion(user, codigos.get(3)), "ya usado");
        assertNull(TwoFactorService.consumirCodigoRecuperacion(user, "zzzz-zzzz"));
    }

    @Test
    void activo_exigeSecretoYBandera() {
        Map<String, Object> u = new HashMap<>();
        assertFalse(TwoFactorService.activo(u));
        u.put("totp_enabled", true);
        assertFalse(TwoFactorService.activo(u), "sin secreto no cuenta como activo");
        u.put("totp_secret", "ABCDEFGHIJKLMNOP");
        assertTrue(TwoFactorService.activo(u));
        u.put("totp_enabled", "false");
        assertFalse(TwoFactorService.activo(u));
    }

    @Test
    void qr_esUnSvgYSecretoLegible() {
        String svg = twoFactor.qrSvg(twoFactor.urlAlta("ana@inmo.es", "JBSWY3DPEHPK3PXP"));
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.contains("<path"));
        assertEquals("JBSW Y3DP EHPK 3PXP", TwoFactorService.formatearSecreto("JBSWY3DPEHPK3PXP"));
    }
}
