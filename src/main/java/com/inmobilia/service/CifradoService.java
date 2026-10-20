package com.inmobilia.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado de datos sensibles guardados en la base de datos (AES-256-GCM).
 *
 * La clave vive SOLO en la variable de entorno CIFRADO_KEY (32 bytes en Base64), nunca
 * en la BD: si alguien se llevara una copia de la base de datos, los secretos de 2FA no
 * le servirían. Formato guardado: "enc:v1:" + Base64(iv[12] + texto cifrado + etiqueta).
 *
 * Sin CIFRADO_KEY se guarda en claro (compatible hacia atrás) y se avisa en el log.
 * ⚠️ NO cambiar ni borrar CIFRADO_KEY una vez en uso: los secretos cifrados dejarían de
 * poder leerse (los afectados entrarían con sus códigos de recuperación y reactivarían 2FA).
 */
@Service
@Slf4j
public class CifradoService {

    private static final String PREFIJO = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RND = new SecureRandom();

    private final SecretKeySpec clave;

    public CifradoService(@Value("${CIFRADO_KEY:}") String claveBase64) {
        SecretKeySpec k = null;
        if (claveBase64 != null && !claveBase64.isBlank()) {
            try {
                byte[] b = Base64.getDecoder().decode(claveBase64.trim());
                if (b.length == 32) k = new SecretKeySpec(b, "AES");
                else log.error("CIFRADO_KEY debe tener 32 bytes en Base64 (tiene {}): se guardará sin cifrar", b.length);
            } catch (IllegalArgumentException e) {
                log.error("CIFRADO_KEY no es Base64 válido: se guardará sin cifrar");
            }
        } else {
            log.warn("CIFRADO_KEY no configurada: los secretos de 2FA se guardan sin cifrar");
        }
        this.clave = k;
    }

    public boolean activo() { return clave != null; }

    public static boolean estaCifrado(String v) { return v != null && v.startsWith(PREFIJO); }

    /** Cifra si hay clave; si no, devuelve el texto tal cual. */
    public String cifrar(String texto) {
        if (texto == null || clave == null || estaCifrado(texto)) return texto;
        try {
            byte[] iv = new byte[IV_BYTES];
            RND.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            return PREFIJO + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar", e);
        }
    }

    /** Descifra lo cifrado; lo que estaba en claro se devuelve igual. null si no se puede descifrar. */
    public String descifrar(String valor) {
        if (valor == null || !estaCifrado(valor)) return valor;
        if (clave == null) {
            log.error("Hay datos cifrados pero falta CIFRADO_KEY: no se pueden leer");
            return null;
        }
        try {
            byte[] todo = Base64.getDecoder().decode(valor.substring(PREFIJO.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, todo, 0, IV_BYTES));
            return new String(c.doFinal(todo, IV_BYTES, todo.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("No se pudo descifrar un dato (¿CIFRADO_KEY cambiada?)");
            return null;
        }
    }
}
