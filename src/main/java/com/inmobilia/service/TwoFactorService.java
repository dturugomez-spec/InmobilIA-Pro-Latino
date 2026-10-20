package com.inmobilia.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verificación en dos pasos (2FA) con app de autenticación (Google Authenticator,
 * Microsoft Authenticator, Authy, 1Password…).
 *
 * Columnas en users: totp_secret, totp_enabled, totp_recovery_codes (hashes SHA-256
 * de los códigos de recuperación, separados por comas) y require_2fa (en la fila del
 * admin de la inmobiliaria: obliga a todo el equipo).
 */
@Service
@RequiredArgsConstructor
public class TwoFactorService {

    public static final String EMISOR = "InmobilIA Pro";
    public static final int NUM_CODIGOS = 10;
    private static final String ALFABETO_CODIGOS = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RND = new SecureRandom();

    private final TotpService totp;
    private final SupabaseService supabase;
    private final CifradoService cifrado;

    /** Secreto TOTP en claro (descifrado si está guardado cifrado). null si no se puede leer. */
    public String secretoDe(Map<String, Object> user) {
        Object v = user == null ? null : user.get("totp_secret");
        return presente(v) ? cifrado.descifrar(String.valueOf(v)) : null;
    }

    /** Lo que se guarda en BD: cifrado si CIFRADO_KEY está configurada. */
    public String secretoParaGuardar(String secretoPlano) { return cifrado.cifrar(secretoPlano); }

    /** Secreto antiguo guardado en claro que ya se puede cifrar. */
    public boolean necesitaCifrar(Map<String, Object> user) {
        Object v = user == null ? null : user.get("totp_secret");
        return cifrado.activo() && presente(v) && !CifradoService.estaCifrado(String.valueOf(v));
    }

    /** Último paso de 30 s aceptado por usuario: un mismo código no sirve dos veces. */
    private final Map<String, Long> ultimoPaso = new ConcurrentHashMap<>();

    public static boolean activo(Map<String, Object> user) {
        return user != null && verdadero(user.get("totp_enabled")) && presente(user.get("totp_secret"));
    }

    /** ¿La inmobiliaria obliga a todo su equipo a usar 2FA? */
    public boolean exigidoEnOrg(String orgId) {
        if (orgId == null || orgId.isBlank()) return false;
        List<Map<String, Object>> rows = supabase.findBy("users", Map.of("id", orgId));
        return !rows.isEmpty() && verdadero(rows.get(0).get("require_2fa"));
    }

    public String nuevoSecreto() { return totp.generateSecret(); }

    public String urlAlta(String cuenta, String secreto) { return totp.otpAuthUrl(EMISOR, cuenta, secreto); }

    /** Comprueba un código de la app. Rechaza reutilizar un código ya aceptado. */
    public boolean verificarCodigo(String uid, String secreto, String codigo) {
        long paso = totp.verifyStep(secreto, codigo);
        if (paso < 0) return false;
        Long previo = ultimoPaso.get(uid);
        if (previo != null && paso <= previo) return false;
        ultimoPaso.put(uid, paso);
        return true;
    }

    /** Genera códigos de recuperación de un solo uso (formato xxxx-xxxx). */
    public List<String> generarCodigosRecuperacion() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < NUM_CODIGOS; i++) {
            StringBuilder sb = new StringBuilder(9);
            for (int j = 0; j < 8; j++) {
                if (j == 4) sb.append('-');
                sb.append(ALFABETO_CODIGOS.charAt(RND.nextInt(ALFABETO_CODIGOS.length())));
            }
            out.add(sb.toString());
        }
        return out;
    }

    public static String hashesParaGuardar(List<String> codigos) {
        return String.join(",", codigos.stream().map(TwoFactorService::hashCodigo).toList());
    }

    public static int codigosRestantes(Map<String, Object> user) {
        Object v = user == null ? null : user.get("totp_recovery_codes");
        if (!presente(v)) return 0;
        return (int) java.util.Arrays.stream(String.valueOf(v).split(",")).filter(s -> !s.isBlank()).count();
    }

    /**
     * Si el código es uno de recuperación válido, devuelve la nueva lista de hashes SIN él
     * (para guardarla: cada código sirve una vez). Si no vale, null.
     */
    public static String consumirCodigoRecuperacion(Map<String, Object> user, String codigo) {
        Object v = user == null ? null : user.get("totp_recovery_codes");
        if (!presente(v) || codigo == null) return null;
        String h = hashCodigo(codigo);
        List<String> quedan = new ArrayList<>();
        boolean usado = false;
        for (String s : String.valueOf(v).split(",")) {
            if (s.isBlank()) continue;
            if (!usado && MessageDigest.isEqual(s.getBytes(StandardCharsets.US_ASCII), h.getBytes(StandardCharsets.US_ASCII))) {
                usado = true;
            } else {
                quedan.add(s);
            }
        }
        return usado ? String.join(",", quedan) : null;
    }

    static String hashCodigo(String codigo) {
        String norm = codigo.toLowerCase().replaceAll("[^a-z0-9]", "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** QR en SVG generado aquí: el secreto nunca sale a un servicio de terceros. */
    public String qrSvg(String contenido) {
        try {
            BitMatrix m = new QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            int w = m.getWidth(), h = m.getHeight();
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (m.get(x, y)) path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h
                    + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Código QR para la app de autenticación\">"
                    + "<rect width=\"" + w + "\" height=\"" + h + "\" fill=\"#fff\"/>"
                    + "<path fill=\"#0f172a\" d=\"" + path + "\"/></svg>";
        } catch (Exception e) {
            return "";
        }
    }

    /** Secreto en grupos de 4 para teclearlo a mano si no se puede escanear. */
    public static String formatearSecreto(String s) {
        return s == null ? "" : s.replaceAll("(.{4})(?!$)", "$1 ");
    }

    static boolean verdadero(Object o) {
        return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)));
    }

    static boolean presente(Object o) {
        return o != null && !String.valueOf(o).isBlank() && !"null".equals(String.valueOf(o));
    }
}
