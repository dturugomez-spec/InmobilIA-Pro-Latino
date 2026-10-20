package com.inmobilia.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Firma (HMAC-SHA256) de enlaces que la app manda por email, para que nadie pueda fabricarlos:
 * p. ej. el seguimiento de clics de las campañas redirige solo a destinos firmados (antes
 * redirigía a cualquier web: enlaces de phishing que empezaban por www.inmobiliapro.es).
 * Clave: APP_FIRMA_ENLACES o, si no hay, derivada de la clave de servicio de Supabase.
 */
@Component
public class FirmaEnlaces {

    private final byte[] clave;

    public FirmaEnlaces(@Value("${app.firma-enlaces:${supabase.key:}}") String secreto) {
        byte[] k;
        if (secreto == null || secreto.isBlank()) {
            k = new byte[32];
            new SecureRandom().nextBytes(k); // sin secreto (pruebas): firmas válidas solo hasta reiniciar
        } else {
            try {
                k = MessageDigest.getInstance("SHA-256").digest(("firma-enlaces:" + secreto).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        this.clave = k;
    }

    public String firmar(String... partes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clave, "HmacSHA256"));
            byte[] h = mac.doFinal(String.join("\n", partes).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 22);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean valida(String firma, String... partes) {
        if (firma == null || firma.isBlank()) return false;
        return MessageDigest.isEqual(firmar(partes).getBytes(StandardCharsets.UTF_8), firma.getBytes(StandardCharsets.UTF_8));
    }
}
