package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Sistema de facturación verificable (RD 1007/2023 · AEAT Verifactu).
 *
 * Cada factura genera un hash SHA-256 que encadena con la anterior.
 * Esto crea una cadena inmutable: cualquier modificación rompe la cadena
 * y se detecta inmediatamente.
 *
 * Genera también el payload del QR que la AEAT exige incluir en la factura impresa.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerifactuService {

    private final SupabaseService supabase;

    /**
     * Calcula el hash de la factura siguiendo el formato AEAT:
     * SHA-256(NIF emisor + serie + numero + fecha + total + hash_anterior).
     */
    public String calculateHash(Map<String, Object> invoice, String previousHash) {
        String emisorNif = str(invoice.get("emisor_nif"));
        String serie     = str(invoice.get("serie"));
        Object num       = invoice.get("numero");
        String fecha     = str(invoice.get("fecha"));
        if (fecha.length() > 10) fecha = fecha.substring(0, 10);
        String total     = str(invoice.get("total"));
        String prev      = previousHash == null ? "0".repeat(64) : previousHash;

        String concatenated = String.join("|",
                emisorNif,
                serie,
                String.valueOf(num),
                fecha,
                total,
                prev);

        return sha256(concatenated);
    }

    /**
     * Genera el payload del QR exigido por AEAT.
     * Formato: NIF|Serie/Num|Fecha|Total|Hash (8 primeros)
     */
    public String generateQrPayload(Map<String, Object> invoice, String hash) {
        String emisorNif = str(invoice.get("emisor_nif"));
        String fullNum   = str(invoice.get("full_number"));
        String fecha     = str(invoice.get("fecha"));
        if (fecha.length() > 10) fecha = fecha.substring(0, 10);
        String total     = str(invoice.get("total"));
        String shortHash = hash != null && hash.length() >= 8 ? hash.substring(0, 8) : "";

        // URL al verificador (cuando sea oficial será sede.aeat.gob.es)
        return String.format("https://sede.agenciatributaria.gob.es/verifica?nif=%s&num=%s&fecha=%s&total=%s&h=%s",
                urlEnc(emisorNif), urlEnc(fullNum), urlEnc(fecha), urlEnc(total), shortHash);
    }

    /** Recupera el último hash de la cadena de facturas del usuario. */
    public String getLastHash(String userId) {
        try {
            var invoices = supabase.findBy("invoices", Map.of("user_id", userId));
            return invoices.stream()
                    .filter(i -> i.get("verifactu_chain_id") != null)
                    .max((a, b) -> {
                        try {
                            int ca = Integer.parseInt(String.valueOf(a.get("verifactu_chain_id")));
                            int cb = Integer.parseInt(String.valueOf(b.get("verifactu_chain_id")));
                            return Integer.compare(ca, cb);
                        } catch (Exception e) { return 0; }
                    })
                    .map(i -> str(i.get("verifactu_hash")))
                    .filter(s -> !s.isBlank())
                    .orElse(null);
        } catch (Exception e) { return null; }
    }

    /** Siguiente número de cadena (chain_id) para el usuario. */
    public int getNextChainId(String userId) {
        try {
            var invoices = supabase.findBy("invoices", Map.of("user_id", userId));
            return invoices.stream()
                    .map(i -> i.get("verifactu_chain_id"))
                    .filter(java.util.Objects::nonNull)
                    .mapToInt(o -> {
                        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
                    })
                    .max().orElse(0) + 1;
        } catch (Exception e) { return 1; }
    }

    /**
     * Verifica si la cadena Verifactu de un usuario es íntegra
     * (cada hash coincide con el cálculo).
     */
    public Map<String, Object> verifyChain(String userId) {
        try {
            var invoices = supabase.findBy("invoices", Map.of("user_id", userId)).stream()
                    .filter(i -> i.get("verifactu_chain_id") != null)
                    .sorted((a, b) -> {
                        int ca = parseInt(a.get("verifactu_chain_id"));
                        int cb = parseInt(b.get("verifactu_chain_id"));
                        return Integer.compare(ca, cb);
                    })
                    .toList();

            String prevHash = null;
            int verified = 0, broken = 0;
            for (var inv : invoices) {
                String calc = calculateHash(inv, prevHash);
                String stored = str(inv.get("verifactu_hash"));
                if (calc.equals(stored)) verified++; else broken++;
                prevHash = stored;
            }
            return Map.of(
                    "ok", broken == 0,
                    "verified", verified,
                    "broken", broken,
                    "total", invoices.size()
            );
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ─── helpers ───
    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static int parseInt(Object o) { try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; } }
    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
